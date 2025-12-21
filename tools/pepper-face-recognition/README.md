# Pepper Head-Based Perception System

This directory contains the complete perception system running on Pepper's head computer (Intel Atom E3845). It provides real-time face detection, recognition, tracking, and gaze detection via WebSocket streaming.

## System Architecture

The system must run on **Python 3.7+** for OpenCV support, but Pepper's OS is **Python 2.7**.
Additionally, while the CPU is 64-bit, the **Userspace is 32-bit (i386)**.

**Solution:** We cross-compile a standalone Python 3.7 environment with OpenCV/YuNet/SFace in a 32-bit Docker container and deploy it to the robot.

## Overview

- **Detection**: YuNet CNN for robust face detection
- **Recognition**: SFace for 128-dimensional feature extraction  
- **Tracking**: Stable track IDs across frames using angle-based matching
- **Gaze Detection**: Determines if a person is looking at the robot
- **Streaming**: Real-time WebSocket updates (~3-5 Hz)
- **Storage**: Face embeddings stored in `/home/nao/face_data/faces.pkl`

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PEPPER HEAD (Python)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────────┐     ┌─────────────────────────────────────────┐  │
│  │   camera_daemon.py   │     │       face_recognition_server.py        │  │
│  │   (Python 2.7)       │     │       (Python 3.7 / i386)               │  │
│  │                      │     │                                         │  │
│  │ • Persistent camera  │     │ • Face detection (YuNet)                │  │
│  │   subscription       │     │ • Face recognition (SFace)              │  │
│  │ • RGB + Head sync    │     │ • Face tracking (FaceTracker)           │  │
│  │ • Shared Memory      │     │ • Gaze detection                        │  │
│  │   Writer (/dev/shm)  │====▶│ • Shared Memory Reader                  │  │
│  └──────────────────────┘     │                                         │  │
│                               │ HTTP Server @5000 (Legacy/Fallback)     │  │
│                               └────────────────┬────────────────────────┘  │
│                                                │                            │
│                               ┌────────────────▼────────────────────────┐  │
│                               │      perception_websocket.py            │  │
│                               │      (WebSocket Server @5002)           │  │
│                               │                                         │  │
│                               │ • Real-time people streaming            │  │
│                               │ • Settings sync                         │  │
│                               │ • Face registration commands            │  │
│                               │ • Bidirectional communication           │  │
│                               └────────────────┬────────────────────────┘  │
└────────────────────────────────────────────────┼────────────────────────────┘
                                                 │
                                    WebSocket (ws://198.18.0.1:5002)
                                                 │
┌────────────────────────────────────────────────┼────────────────────────────┐
│                            PEPPER TABLET (Android)                          │
├────────────────────────────────────────────────┼────────────────────────────┤
│                               ┌────────────────▼────────────────────────┐  │
│                               │    PerceptionWebSocketClient.kt         │  │
│                               │                                         │  │
│                               │ • WebSocket connection (OkHttp)         │  │
│                               │ • Auto-reconnect                        │  │
│                               │ • Kotlin Flows for reactive updates     │  │
│                               └────────────────┬────────────────────────┘  │
│                                                │                            │
│                               ┌────────────────▼────────────────────────┐  │
│                               │       PerceptionService.kt              │  │
│                               │                                         │  │
│                               │ • Flow collection                       │  │
│                               │ • Event detection                       │  │
│                               │ • UI updates                            │  │
│                               └────────────────┬────────────────────────┘  │
│                                                │                            │
│                               ┌────────────────▼────────────────────────┐  │
│                               │        UI (Dashboard, Event Rules)      │  │
│                               └─────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Installation

### Prerequisites
- Docker (Desktop or Engine)
- PowerShell
- 4GB+ RAM for building

### Steps

1. **Build Packages (Docker)**
   This compiles Python 3.7, OpenCV 4.x, and NumPy for i386 architecture.
   ```powershell
   .\build.ps1
   ```

2. **Deploy to Pepper**
   Copies the compiled packages and models to the robot.
   ```powershell
   .\deploy.ps1 -PepperIP "10.95.65.123"
   ```

3. **Verify**
   The deploy script runs a self-test automatically.

## Usage on Pepper

```bash
# 1. Start Camera Daemon (Python 2.7)
python2 /home/nao/face_data/camera_daemon.py &

# 2. Start Perception Server (Python 3.7)
# Set PYTHONPATH to include our custom packages
export PYTHONPATH=/home/nao/python_packages:$PYTHONPATH
export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH

cd /home/nao/face_data
python3 face_recognition_server.py
```

## Performance

### By Resolution

| Resolution | Detection | Total Loop | Update Rate | Use Case |
|------------|-----------|------------|-------------|----------|
| QQVGA (160×120) | ~80-120ms | ~100-150ms | ~7-10 Hz | Fast tracking, lower accuracy |
| **QVGA (320×240)** | ~120-220ms | ~150-250ms | ~4-6 Hz | **Recommended balance** |
| VGA (640×480) | ~600-900ms | ~650-950ms | ~1-1.5 Hz | Best recognition, slow tracking |

**Note:** Face registration automatically switches to VGA temporarily for better quality embeddings, then restores the previous resolution.

### Component Breakdown (QVGA)

| Stage | Time | Notes |
|-------|------|-------|
| Frame Capture | < 1ms | **Shared Memory (mmap)** from camera_daemon |
| Face Detection | ~120-220ms | YuNet CNN (i386) - Main bottleneck |
| Face Tracking | ~2-5ms | Angle-based matching (Kalman) |
| Face Recognition | ~1000-1300ms | SFace (Async/Threaded) - **Does not block tracking** |

### Why 32-bit?
Pepper's Atom E3845 CPU supports 64-bit, but the NAOqi OS uses a **32-bit userspace**. Without root access (`sudo`), we cannot run a 64-bit chroot or container. Therefore, we must use 32-bit binaries.

## Optimizations Implemented

- **Shared Memory (Zero-Copy)**: `camera_daemon.py` writes frames to `/dev/shm`, `face_recognition_server.py` reads them instantly. Eliminates HTTP/TCP overhead (~80ms -> <1ms).
- **Persistent Camera Subscription**: No subscribe/unsubscribe overhead per frame
- **BGR Colorspace**: Direct from NAOqi, no conversion needed
- **Async Recognition**: Non-blocking, runs in separate thread
- **Cached Encodings**: Face database cached with 5s TTL
- **WebSocket Streaming**: Push-based, not polling

## Tracking Robustness

The face tracker uses a simplified, robust approach:

- **World-Position Matching (70cm)**: When a face reappears after camera motion or temporary occlusion, it's matched to existing tracks based on 3D world position (yaw, pitch, distance)
- **Confirmation System**: New tracks require 3 consecutive detections before being created, filtering out motion blur artifacts
- **Lost Track Buffer (2.5s)**: Tracks that disappear are buffered for 2.5 seconds, allowing recovery during head movements
- **Fair Recognition Queue**: Unknown faces are recognized in oldest-first order, ensuring all faces get attention

## Why OpenCV instead of dlib?

Pepper's head runs on an Intel Atom E3845 CPU, which lacks modern instruction sets like SSE4 and AVX. 
- **dlib**: Requires these instructions or complex cross-compilation, leading to "Illegal instruction" crashes.
- **OpenCV (YuNet + SFace)**: Runs efficiently on older hardware using CNNs optimized for edge devices.

## Prerequisites

- **Docker** installed and running
  - Windows/macOS: Install [Docker Desktop](https://www.docker.com/products/docker-desktop/)
  - Linux: Install via package manager (`sudo apt install docker.io`)
- **SSH access** to Pepper (default user: `nao`, default password: `nao`)
- Pepper connected to the same network as your PC

## Quick Start

### 1. Build the packages (one-time, < 5 minutes)

```bash
cd tools/pepper-face-recognition

# Linux / macOS
chmod +x build.sh deploy.sh
./build.sh

# Windows PowerShell
.\build.ps1
```

This creates:
- `output/packages.zip` - Python packages (opencv-python-headless, numpy, websockets)
- `output/models/` - ONNX models for face detection and recognition
- `output/libs/` - Extracted native libraries needed by OpenCV

### 2. Deploy to Pepper

```bash
# Linux / macOS
./deploy.sh <PEPPER_IP>

# Windows PowerShell
.\deploy.ps1 -PepperIP "<PEPPER_IP>"
```

This automatically:
1. Copies all files to Pepper
2. Extracts and sets up the packages
3. Copies required native libraries
4. Tests the installation

### 3. Configure Android App

Set in `local.properties`:
```properties
PEPPER_SSH_PASSWORD=nao
```

The Android app will automatically start the server via SSH when needed.

## Server Components

### camera_daemon.py (Python 2.7)

Persistent camera subscription daemon running on port 5050:
- Subscribes once to RGB and Depth cameras
- Provides synchronized frames via binary HTTP endpoint
- Eliminates subscribe/unsubscribe latency per frame

### face_recognition_server.py (Python 3.7)

Main perception server:
- HTTP API on port 5000 (legacy/fallback)
- WebSocket streaming via perception_websocket.py
- Face detection, recognition, and tracking
- Gaze detection based on face position and head angles

### perception_websocket.py (Python 3.7)

WebSocket server on port 5002 for real-time streaming:
- Bidirectional communication
- People updates pushed to clients
- Settings synchronization
- Face management commands

## WebSocket API

### Connection

```
ws://198.18.0.1:5002
```

### Message Types (Client → Server)

| Type | Data | Description |
|------|------|-------------|
| `get_settings` | - | Request current settings |
| `set_settings` | `{max_angle_distance, recognition_threshold, ...}` | Update settings |
| `register_face` | `{name: "..."}` | Register new face from camera |
| `delete_face` | `{name: "..."}` | Delete a face |
| `list_faces` | - | Request face list |
| `ping` | - | Keep-alive |

### Message Types (Server → Client)

| Type | Data | Description |
|------|------|-------------|
| `people` | `{people: [...], timestamp}` | Tracked people update |
| `settings` | `{max_angle_distance, ...}` | Current settings |
| `faces` | `[{name, count}, ...]` | List of known faces |
| `face_registered` | `{success, name, error?}` | Registration result |
| `face_deleted` | `{success, name, error?}` | Deletion result |
| `pong` | - | Keep-alive response |

### People Update Format

```json
{
  "type": "people",
  "data": {
    "people": [
      {
        "track_id": 42,
        "name": "John",
        "looking_at_robot": true,
        "head_direction": "looking",
        "world_yaw": -5.2,
        "world_pitch": 3.1,
        "distance": 1.5,
        "last_seen_ms": 1702831200000,
        "time_since_seen_ms": 50,
        "track_age_ms": 15000,
        "gaze_duration_ms": 3500
      }
    ]
  },
  "timestamp": 1702831200100
}
```

| Field | Type | Description |
|-------|------|-------------|
| `track_id` | int | Stable ID for this tracked person |
| `name` | string | Recognized name or "Unknown" |
| `looking_at_robot` | bool | Is person looking at the robot? |
| `head_direction` | float | Head yaw ratio (-1=right, 0=center, +1=left) |
| `world_yaw` | float | Horizontal angle from robot (degrees) |
| `world_pitch` | float | Vertical angle from robot (degrees) |
| `distance` | float | Estimated distance in meters |
| `last_seen_ms` | long | Timestamp when last seen |
| `time_since_seen_ms` | long | Milliseconds since last seen |
| `track_age_ms` | long | How long this person has been tracked |
| `gaze_duration_ms` | long | How long person is looking at robot (0 if not looking) |

## HTTP API (Legacy/Fallback)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/recognize` | Capture image and recognize faces |
| GET | `/people` | Get tracked people with positions and gaze |
| GET | `/faces` | List all registered faces with image URLs |
| GET | `/faces/image?name=X&index=N` | Get specific thumbnail image (index optional, default 0) |
| GET | `/settings` | Get current perception settings |
| POST | `/faces?name=X` | Register new face from camera (adds to existing) |
| POST | `/settings` | Update perception settings |
| DELETE | `/faces?name=X` | Delete all images for a face |
| DELETE | `/faces/image?name=X&index=N` | Delete specific image by index |

### Face List Response Format

```json
{
  "faces": [
    {
      "name": "John",
      "count": 3,
      "image_count": 3,
      "image_urls": [
        "/faces/image?name=John&index=0",
        "/faces/image?name=John&index=1",
        "/faces/image?name=John&index=2"
      ]
    }
  ]
}
```

### Multi-Image Registration

Each person can have multiple registered images from different angles. The Android app displays these as a horizontally scrollable thumbnail gallery with individual delete buttons.

## Configurable Settings

All settings can be changed via WebSocket or HTTP and are applied immediately:

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| `recognition_threshold` | 0.65 | 0.3-0.9 | Cosine distance threshold (lower = stricter) |
| `recognition_cooldown_ms` | 3000 | 2000-10000 | Time between recognition attempts per track (should be > recognition time) |
| `max_angle_distance` | 15.0 | 5-30 | Max angle movement to match same person (degrees) |
| `track_timeout_ms` | 3000 | 1000-10000 | Time before removing lost tracks |
| `gaze_center_tolerance` | 0.15 | 0.05-0.5 | How off-center is still "looking at robot" |
| `update_interval_ms` | 150 | 50-2000 | Server update rate |
| `camera_resolution` | 1 | 0-2 | 0=QQVGA(160x120), 1=QVGA(320x240), 2=VGA(640x480) |

## Android Integration

### PerceptionWebSocketClient

Handles all communication with the head server:

```kotlin
// Injected via Hilt
val perceptionWebSocketClient: PerceptionWebSocketClient

// Connect (auto-reconnect on disconnect)
perceptionWebSocketClient.connect()

// Collect people updates
perceptionWebSocketClient.peopleUpdates.collect { update ->
    update.people.forEach { person ->
        Log.i("Person", "${person.name} at ${person.distance}m")
    }
}

// Update settings (applied immediately)
perceptionWebSocketClient.updateSettings(newSettings)

// Face management
perceptionWebSocketClient.registerFace("John Doe")
perceptionWebSocketClient.deleteFace("John Doe")
```

### Event Detection

The `PerceptionService` detects these events from the tracking data:

| Event | Trigger |
|-------|---------|
| `PERSON_APPEARED` | New track ID detected |
| `PERSON_DISAPPEARED` | Track ID not seen for timeout |
| `PERSON_RECOGNIZED` | Name assigned to track |
| `PERSON_LOOKING` | Gaze directed at robot |
| `PERSON_STOPPED_LOOKING` | Gaze directed away |
| `PERSON_APPROACHED_CLOSE` | Distance < 1.5m |
| `PERSON_APPROACHED_INTERACTION` | Distance < 3.0m |

### Event Rule Conditions

Events can be filtered with conditions. Available fields:

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `personname` | string | Recognized name | `personname = John` |
| `distance` | number | Distance in meters | `distance < 2.0` |
| `peoplecount` | number | Number of tracked people | `peoplecount = 1` |
| `islooking` | boolean | Is looking at robot | `islooking = true` |
| `gazeduration` | number | Gaze duration in ms | `gazeduration > 3000` |
| `trackage` | number | Tracking duration in ms | `trackage > 10000` |
| `robotstate` | string | IDLE, LISTENING, THINKING, SPEAKING | `robotstate = LISTENING` |

**Example Rule:**
- Event: `PERSON_LOOKING`
- Condition: `gazeduration > 3000`
- Template: "Hello! You've been looking at me for {gazeDuration}. Can I help you?"

This rule only fires when someone has been looking at the robot for **more than 3 seconds**, avoiding reactions to brief glances.

### Template Placeholders

| Placeholder | Description |
|-------------|-------------|
| `{personName}` | Recognized name or "Unknown" |
| `{distance}` | Distance (e.g., "1.5m") |
| `{isLooking}` | "true" or "false" |
| `{gazeDuration}` | Gaze duration (e.g., "5s") |
| `{trackAge}` | Tracking duration (e.g., "30s") |
| `{peopleCount}` | Number of people |
| `{robotState}` | Current robot state |
| `{timestamp}` | Current time (HH:mm:ss) |

### Dashboard UI

The People & Faces dashboard shows:
- **Live Tab**: Currently detected people with:
  - Name (recognized or "Unknown")
  - Distance (meters)
  - Position (Left/Front/Right with angle)
  - Gaze (👀 Looking with duration, or ← → ↔ Away)
  - Duration (how long tracked: "5s", "1m 30s", "1h 5m")
- **Radar Tab**: Visual representation of people around the robot
- **Faces Tab**: Registered faces with thumbnails, add/delete
- **Settings Tab**: Real-time configurable perception parameters

## Manual Server Control

### Start Server

```bash
ssh nao@<PEPPER_IP>
cd /home/nao/face_data
nohup bash run_server.sh > server.log 2>&1 &
```

### Stop Server

```bash
ssh nao@<PEPPER_IP>
pkill -f camera_daemon.py
pkill -f face_recognition_server.py
```

### View Logs

```bash
ssh nao@<PEPPER_IP>
tail -f /home/nao/face_data/server.log
```

## Troubleshooting

### "cannot open shared object file"

```bash
export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH
```

### "ImportError: No module named cv2"

```bash
export PYTHONPATH=/home/nao/python_packages
```

### WebSocket not connecting

1. Check if server is running: `ps aux | grep python3`
2. Check server logs: `tail -f /home/nao/face_data/server.log`
3. Ensure port 5002 is not blocked

### No faces detected

1. Ensure adequate lighting
2. Person should face the robot directly
3. Check camera resolution setting (higher = better quality, slower)

## Hardware Details

- **Pepper's Head CPU:** Intel Atom E3845 (32-bit, i386 Linux)
- **Python Versions:** 2.7 (NAOqi), 3.7 (recognition)
- **OS:** Gentoo-based Linux (NAOqi OS)
- **Models:** YuNet (Detection), SFace (Recognition) - ONNX format
- **Server IP (from Tablet):** 198.18.0.1
- **Ports:** 5000 (HTTP), 5002 (WebSocket), 5050 (Camera Daemon)

