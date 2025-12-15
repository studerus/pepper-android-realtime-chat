# Pepper Face Recognition Build Tools

This directory contains tools to deploy OpenCV-based face recognition for Pepper's internal computer (Intel Atom, i386 architecture, Python 3.7).

## Overview

Local face recognition runs entirely on Pepper's head (no cloud API required):
- **Detection**: YuNet CNN for robust face detection
- **Recognition**: SFace for 128-dimensional feature extraction
- **Performance**: ~1.5-1.7 seconds per recognition (QVGA 320x240)
- **Storage**: Face embeddings stored in `/home/nao/face_data/faces.pkl`

## Why OpenCV instead of dlib?

Pepper's head runs on an Intel Atom E3845 CPU, which lacks modern instruction sets like SSE4 and AVX. 
- **dlib**: Requires these instructions or complex cross-compilation with disabled optimizations, leading to instability ("Illegal instruction" crashes).
- **OpenCV (YuNet + SFace)**: Runs efficiently on older hardware using CNNs optimized for edge devices. It is more robust against head rotation and lighting conditions than dlib's HoG detector.

This solution uses Docker to download the correct 32-bit wheels and models on a PC, then deploys them to Pepper.

## Prerequisites

- **Docker** installed and running
  - Windows/macOS: Install [Docker Desktop](https://www.docker.com/products/docker-desktop/)
  - Linux: Install via package manager (`sudo apt install docker.io` or similar)
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
- `output/packages.zip` - Python packages (opencv-python-headless, numpy)
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

## Face Recognition Usage

After deployment, the following scripts are available on Pepper in `/home/nao/face_data/`:

### Register a Face

```bash
# From Pepper's camera (person should look at the robot)
LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH \
PYTHONPATH=/home/nao/python_packages \
python3 /home/nao/face_data/register_face.py "John Doe"

# From an image file
LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH \
PYTHONPATH=/home/nao/python_packages \
python3 /home/nao/face_data/register_face.py "John Doe" /path/to/photo.jpg
```

### Recognize Faces

```bash
# From Pepper's camera
LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH \
PYTHONPATH=/home/nao/python_packages \
python3 /home/nao/face_data/recognize_face.py

# Output (JSON):
# {
#   "faces": [
#     {
#       "name": "John Doe",
#       "confidence": 0.85,
#       "location": {"top": 50, "right": 200, "bottom": 150, "left": 100}
#     }
#   ]
# }
```

### List Registered Faces

```bash
LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH \
PYTHONPATH=/home/nao/python_packages \
python3 /home/nao/face_data/face_database.py
```

### How It Works

1. **Detection (YuNet)**: Detects faces in the image (robust to rotation/occlusion).
2. **Recognition (SFace)**: Extracts a 128-dimensional feature vector.
3. **Matching**: Uses Cosine Similarity to compare against stored faces.
4. **Storage**: Face data is stored in `/home/nao/face_data/faces.pkl`.

## Usage in Python Scripts

When running Python scripts on Pepper that use these libraries, you must set the environment variables:

```bash
export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH
export PYTHONPATH=/home/nao/python_packages
python3 your_script.py
```

Or inside Python:
```python
import sys
import os

# Add library paths
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')
sys.path.insert(0, '/home/nao/python_packages')

import cv2
# ... your code
```

## Troubleshooting

### "cannot open shared object file"

Make sure `LD_LIBRARY_PATH` is set correctly:
```bash
export LD_LIBRARY_PATH=/home/nao/python_packages/libs:$LD_LIBRARY_PATH
```
If a specific library is still missing, check `output/libs` on your PC and copy it manually to `/home/nao/python_packages/libs/`.

### "ImportError: No module named cv2"

Make sure `PYTHONPATH` includes `/home/nao/python_packages`.

## REST API Server

A persistent HTTP server runs on port 5000 for fast face recognition requests.

### Automatic Server Management

The Android app **automatically starts the server via SSH** when needed:
- When opening the Face Management overlay
- When the PerceptionService needs to recognize faces
- No manual server start required!

**Prerequisites**: Set `PEPPER_SSH_PASSWORD=nao` in your `local.properties` file.

### Manual Start (if needed)

```bash
ssh nao@<PEPPER_IP>
nohup bash /home/nao/face_data/run_server.sh > /home/nao/face_data/server.log 2>&1 &
```

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/recognize` | Capture image and recognize faces |
| GET | `/faces` | List all registered faces |
| GET | `/faces/image?name=X` | Get thumbnail image of a face |
| POST | `/faces?name=X` | Register new face from camera |
| DELETE | `/faces?name=X` | Delete a registered face |

### Example Responses

**GET /recognize**
```json
{
  "faces": [
    {
      "name": "John Doe",
      "confidence": 0.97,
      "location": {"left": 153, "top": 82, "right": 188, "bottom": 129}
    }
  ],
  "timing": {"capture": 0.36, "total": 1.56}
}
```

**GET /faces**
```json
{
  "faces": [
    {"name": "John Doe", "count": 1, "image_url": "/faces/image?name=John%20Doe"}
  ]
}
```

## Android Integration

The Android app integrates with the face recognition server via `LocalFaceRecognitionService`:

1. **Face Management UI**: Access via the 👤 icon in the top bar
   - View registered faces with thumbnails
   - Register new faces (person looks at Pepper's camera)
   - Delete existing faces

2. **Dashboard Integration**: Recognized faces appear in the Human Perception Dashboard
   - Shows recognized name next to demographics
   - Green highlighting for identified individuals

3. **Architecture**:
```
┌─────────────────────────────────────────┐
│         Android Tablet                   │
│  ┌─────────────────────────────────┐    │
│  │  LocalFaceRecognitionService    │    │
│  │  (HTTP Client → Port 5000)      │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
                    │
                    │ HTTP (WiFi: 198.18.0.1)
                    ▼
┌─────────────────────────────────────────┐
│         Pepper Head (NAOqi)              │
│  ┌─────────────────────────────────┐    │
│  │  face_recognition_server.py     │    │
│  │  (Port 5000)                    │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

## Architecture Details

- **Pepper's Head CPU:** Intel Atom E3845 (32-bit capable, running i386 Linux)
- **Python Version:** 3.7 (pre-installed on Pepper)
- **OS:** Gentoo-based Linux (NAOqi OS)
- **Models:** YuNet (Detection), SFace (Recognition) - ONNX format
- **Server IP (from Tablet):** 198.18.0.1:5000
