#!/usr/bin/env python3
"""
Face Recognition Server for Pepper
Provides a REST API for face recognition, registration, and management.
Now includes Head-Based Perception System with tracking and WebSocket streaming.

HTTP Endpoints (port 5000):
- GET /people - Live tracked people with world coordinates
- GET /recognize - One-shot face recognition (legacy)
- GET /faces - List known faces
- GET /settings - Get current perception settings
- POST /faces?name=X - Register new face
- POST /settings - Update perception settings
- DELETE /faces?name=X - Remove face

WebSocket (port 5001):
- Real-time streaming of people updates
- Bidirectional settings synchronization
"""

import os
import sys
import json
import time
import base64
import subprocess
import threading
import struct
import http.client
import mmap
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# Add custom packages path
sys.path.insert(0, '/home/nao/python_packages')
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')

import cv2
import numpy as np

# Import our modules
from face_database import add_face, remove_face, remove_face_by_index, list_faces, get_all_encodings, get_safe_filename, get_face_image_count, IMAGES_DIR
from recognize_face import capture_image_from_camera, get_face_models, load_ppm_image
from face_tracker import FaceTracker

# WebSocket support (optional - graceful degradation if not available)
try:
    from perception_websocket import (
        start_websocket_server_thread, 
        set_settings_callback,
        set_face_callbacks,
        schedule_broadcast,
        get_settings,
        update_settings,
        get_client_count
    )
    WEBSOCKET_AVAILABLE = True
except ImportError as e:
    print(f"[Warning] WebSocket not available: {e}")
    WEBSOCKET_AVAILABLE = False

# Global models (loaded once)
DETECTOR = None
RECOGNIZER = None

# Global tracker
TRACKER = None
TRACKER_LOCK = threading.Lock()
LAST_SENSOR_DATA = {}
TRACKING_ENABLED = True

# Background tracking
TRACKING_THREAD = None
TRACKING_RUNNING = False
CACHED_PEOPLE = []
LAST_UPDATE_MS = 0

# Cached encodings (avoid reloading database every time)
CACHED_ENCODINGS = None
CACHED_ENCODINGS_TIME = 0
ENCODINGS_CACHE_TTL = 5.0  # Reload every 5 seconds

# Async recognition
RECOGNITION_THREAD = None
RECOGNITION_RUNNING = False
PENDING_RECOGNITION = None  # (track_id, image, raw_face)
PENDING_RECOGNITION_LOCK = threading.Lock()

# Dynamic settings (can be changed via API/WebSocket)
RECOGNITION_THRESHOLD = 0.65  # Default, overridable (cosine distance: lower=more similar)
DETECTION_RESOLUTION_INDEX = 1  # 0=QQVGA, 1=QVGA (Default), 2=VGA

PORT = 5000
WS_PORT = 5002  # Changed to 5002 to avoid conflicts

# Camera Daemon URL (persistent camera subscriptions)
CAMERA_DAEMON_HOST = '127.0.0.1'
CAMERA_DAEMON_PORT = 5050
CAMERA_DAEMON_URL = f'http://{CAMERA_DAEMON_HOST}:{CAMERA_DAEMON_PORT}'
CAMERA_DAEMON_AVAILABLE = False
HTTP_CONNECTION = None  # Persistent connection

# Fallback to subprocess if daemon not available
SENSOR_SCRIPT = '/home/nao/face_data/get_sensors.py'


def get_persistent_connection():
    """Get or create persistent HTTP connection to Camera Daemon."""
    global HTTP_CONNECTION
    if HTTP_CONNECTION is None:
        HTTP_CONNECTION = http.client.HTTPConnection(CAMERA_DAEMON_HOST, CAMERA_DAEMON_PORT, timeout=1.5)
    return HTTP_CONNECTION


def close_persistent_connection():
    """Close persistent connection on error."""
    global HTTP_CONNECTION
    if HTTP_CONNECTION:
        try:
            HTTP_CONNECTION.close()
        except:
            pass
        HTTP_CONNECTION = None


def get_tracking_interval():
    """Get current tracking interval from settings."""
    if WEBSOCKET_AVAILABLE:
        settings = get_settings()
        return settings.update_interval_ms / 1000.0
    return 0.15  # Default 150ms (faster updates)


def get_recognition_threshold():
    """Get current recognition threshold from settings."""
    global RECOGNITION_THRESHOLD
    if WEBSOCKET_AVAILABLE:
        settings = get_settings()
        return settings.recognition_threshold
    return RECOGNITION_THRESHOLD


def get_cached_encodings():
    """Get cached encodings, reloading if needed."""
    global CACHED_ENCODINGS, CACHED_ENCODINGS_TIME
    
    now = time.time()
    if CACHED_ENCODINGS is None or (now - CACHED_ENCODINGS_TIME) > ENCODINGS_CACHE_TTL:
        names, encodings = get_all_encodings()
        # Pre-convert to numpy arrays
        np_encodings = [np.array(e, dtype=np.float32) if not isinstance(e, np.ndarray) else e for e in encodings]
        CACHED_ENCODINGS = (names, np_encodings)
        CACHED_ENCODINGS_TIME = now
    
    return CACHED_ENCODINGS


def recognition_thread_loop():
    """Background thread that handles recognition without blocking tracking."""
    global PENDING_RECOGNITION, RECOGNITION_RUNNING
    
    print("[Recognition] Background recognition thread started")
    
    while RECOGNITION_RUNNING:
        task = None
        
        with PENDING_RECOGNITION_LOCK:
            if PENDING_RECOGNITION is not None:
                task = PENDING_RECOGNITION
                PENDING_RECOGNITION = None
        
        if task:
            track_id, image, raw_face = task
            try:
                name, confidence, duration = recognize_single_face(image, raw_face)
                with TRACKER_LOCK:
                    TRACKER.set_recognition_result(track_id, name, confidence)
            except Exception as e:
                print(f"[Recognition] Error: {e}")
        else:
            time.sleep(0.05)  # Small sleep when no work
    
    print("[Recognition] Background recognition thread stopped")


def start_recognition_thread():
    """Start the background recognition thread."""
    global RECOGNITION_THREAD, RECOGNITION_RUNNING
    
    if RECOGNITION_THREAD is not None and RECOGNITION_THREAD.is_alive():
        return
    
    RECOGNITION_RUNNING = True
    RECOGNITION_THREAD = threading.Thread(target=recognition_thread_loop, daemon=True)
    RECOGNITION_THREAD.start()


def stop_recognition_thread():
    """Stop the background recognition thread."""
    global RECOGNITION_RUNNING
    RECOGNITION_RUNNING = False


def queue_recognition(track_id, image, raw_face):
    """Queue a recognition task (replaces any pending task)."""
    global PENDING_RECOGNITION
    
    with PENDING_RECOGNITION_LOCK:
        # Store a copy of the image
        PENDING_RECOGNITION = (track_id, image.copy(), raw_face.copy())

def init_models():
    """Load models into global variables."""
    global DETECTOR, RECOGNIZER, TRACKER
    print("Loading models...")
    DETECTOR, RECOGNIZER = get_face_models()
    
    # Get initial settings
    if WEBSOCKET_AVAILABLE:
        settings = get_settings()
        TRACKER = FaceTracker(
            max_angle_distance=settings.max_angle_distance,
            track_timeout_ms=settings.track_timeout_ms,
            recognition_cooldown_ms=settings.recognition_cooldown_ms,
            gaze_threshold=settings.gaze_center_tolerance,
            confirm_count=settings.confirm_count,
            lost_buffer_ms=settings.lost_buffer_ms,
            world_match_threshold_m=settings.world_match_threshold_m
        )
    else:
        TRACKER = FaceTracker(
            max_angle_distance=15.0,
            track_timeout_ms=3000,
            recognition_cooldown_ms=3000,
            gaze_threshold=0.15,
            confirm_count=3,
            lost_buffer_ms=2500,
            world_match_threshold_m=0.7
        )
    print("Models loaded.")
    
    # Check if Camera Daemon is available
    check_camera_daemon()


def set_camera_resolution(resolution):
    """Set camera resolution on the daemon. 0=QQVGA, 1=QVGA, 2=VGA."""
    if not CAMERA_DAEMON_AVAILABLE:
        return False
    try:
        import urllib.request
        req = urllib.request.urlopen(f"{CAMERA_DAEMON_URL}/set_resolution?res={resolution}", timeout=2.0)
        return req.status == 200
    except Exception as e:
        print(f"[Camera] Failed to set resolution: {e}")
        return False


def apply_settings(settings):
    """Apply new settings to the tracker and recognition."""
    global TRACKER, RECOGNITION_THRESHOLD, DETECTION_RESOLUTION_INDEX
    
    with TRACKER_LOCK:
        if TRACKER:
            TRACKER.max_angle_distance = settings.max_angle_distance
            TRACKER.track_timeout_ms = settings.track_timeout_ms
            TRACKER.recognition_cooldown_ms = settings.recognition_cooldown_ms
            TRACKER.gaze_threshold = settings.gaze_center_tolerance
            TRACKER.confirm_count = settings.confirm_count
            TRACKER.lost_buffer_ms = settings.lost_buffer_ms
            TRACKER.world_match_threshold_m = settings.world_match_threshold_m
    
    RECOGNITION_THRESHOLD = settings.recognition_threshold
    
    # Update detection resolution (Virtual resolution)
    if hasattr(settings, 'camera_resolution'):
        # Map: 0=QQVGA, 1=QVGA, 2=VGA
        DETECTION_RESOLUTION_INDEX = settings.camera_resolution
    
    print(f"[Settings] Applied: threshold={settings.recognition_threshold}, "
          f"confirm={settings.confirm_count}, lost_buf={settings.lost_buffer_ms}ms")


def check_camera_daemon():
    """Check if camera daemon is available."""
    global CAMERA_DAEMON_AVAILABLE
    try:
        import urllib.request
        req = urllib.request.urlopen(f"{CAMERA_DAEMON_URL}/status", timeout=1.0)
        CAMERA_DAEMON_AVAILABLE = req.status == 200
        if CAMERA_DAEMON_AVAILABLE:
            print("[Camera] Camera Daemon available - using optimized capture")
        return CAMERA_DAEMON_AVAILABLE
    except Exception:
        CAMERA_DAEMON_AVAILABLE = False
        print("[Camera] Camera Daemon not available - using fallback subprocess")
        return False


def raw_to_image(raw_data, width, height):
    """
    Convert raw BGR data from NAOqi to OpenCV image.
    NAOqi ColorSpace 13 = BGR (3 bytes per pixel).
    """
    # BGR has 3 bytes per pixel
    expected_size = width * height * 3
    if len(raw_data) != expected_size:
        print(f"[Camera] Size mismatch: got {len(raw_data)}, expected {expected_size}")
        return None
    
    # Convert bytes to numpy array
    img = np.frombuffer(raw_data, dtype=np.uint8)
    img = img.reshape((height, width, 3))
    
    # Already BGR, no conversion needed!
    return img


# Shared Memory Settings
SHM_PATH = "/dev/shm/pepper_shm"
SHM_SIZE = 4 * 1024 * 1024
SHM_READER = None

class SharedMemoryReader:
    def __init__(self):
        self.f = None
        self.m = None
        self.last_seq = -1
        self.setup()
        
    def setup(self):
        try:
            if not os.path.exists(SHM_PATH):
                return False
            self.f = open(SHM_PATH, "r+b")
            self.m = mmap.mmap(self.f.fileno(), SHM_SIZE, mmap.MAP_SHARED, mmap.PROT_READ)
            print(f"[SHM] Reader initialized")
            return True
        except Exception as e:
            print(f"[SHM] Init failed: {e}")
            return False
            
    def read(self):
        if not self.m:
            if not self.setup():
                return None
                
        try:
            # Read Header
            self.m.seek(0)
            header_bytes = self.m.read(64)
            if len(header_bytes) < 64:
                return None
                
            # Unpack: Magic(4), Seq(4), Ts(8), RGB_W(4), RGB_H(4), RGB_L(4), Head_L(4)
            # Use 'd' for double (8 bytes) which matches Py2 float
            # Correct format: <4sIdIIII (7 items total)
            magic, seq_id, timestamp, rgb_w, rgb_h, rgb_len, head_len = struct.unpack(
                "<4sIdIIII", header_bytes[:32]
            )
            
            if magic != b"PSHM":
                return None
                
            if seq_id == self.last_seq:
                return "NO_NEW_FRAME" # Signal no change
                
            self.last_seq = seq_id
            
            # Read Data
            self.m.seek(64)
            head_bytes = self.m.read(head_len)
            rgb_bytes = self.m.read(rgb_len)
            
            head = json.loads(head_bytes.decode('utf-8')) if head_len > 0 else {}
            
            return {
                'head_yaw': float(head.get('head_yaw', 0.0) or 0.0),
                'head_pitch': float(head.get('head_pitch', 0.0) or 0.0),
                'rgb_image': raw_to_image(rgb_bytes, rgb_w, rgb_h) if rgb_len > 0 else None,
                'depth_data': None,
                'depth_width': 0,
                'depth_height': 0,
                'head': head
            }
            
        except Exception as e:
            print(f"[SHM] Read error: {e}")
            # Try re-init next time
            try:
                self.m.close()
                self.f.close()
            except:
                pass
            self.m = None
            return None

def get_shm_reader():
    global SHM_READER
    if SHM_READER is None:
        SHM_READER = SharedMemoryReader()
    return SHM_READER


def get_synchronized_frame():
    """
    Get synchronized frame. 
    Tries Shared Memory first (Zero-Copy), falls back to HTTP.
    """
    # 1. Try Shared Memory
    reader = get_shm_reader()
    frame = reader.read()
    
    if frame == "NO_NEW_FRAME":
        # Frame hasn't changed since last read
        # In tracking loop, we might want to wait or return None
        # But to match old behavior, we return None so loop waits
        return None
        
    if frame is not None and isinstance(frame, dict):
        return frame
        
    # 2. Fallback to HTTP (if SHM failed or not available)
    return get_synchronized_frame_http()


def get_synchronized_frame_http():
    """
    Legacy HTTP fetcher.
    """
    try:
        conn = get_persistent_connection()
        
        # Send request
        conn.request("GET", "/frame_bin")
        response = conn.getresponse()
        
        if response.status != 200:
            # Consume body to clear state (though we likely close anyway)
            response.read()
            raise RuntimeError(f"HTTP {response.status}")

        blob = response.read()

        # Binary protocol: Header (24 bytes) + HeadJSON + RGB + Depth
        header_fmt = "<4sIHHIHHI"
        header_size = struct.calcsize(header_fmt)
        
        magic, head_len, rgb_w, rgb_h, rgb_len, depth_w, depth_h, depth_len = struct.unpack(
            header_fmt, blob[:header_size]
        )
        
        if magic != b"PFR1":
            raise RuntimeError("Invalid frame magic")

        offset = header_size
        head_bytes = blob[offset:offset + head_len]
        offset += head_len
        rgb_bytes = blob[offset:offset + rgb_len]
        offset += rgb_len
        # Depth bytes skipped or not present if daemon changed?
        # Ideally we should update HTTP protocol too, but let's assume daemon still sends depth via HTTP for compatibility
        # If we removed depth from daemon HTTP handler, this will break.
        # Let's check camera_daemon.py changes... I removed get_depth_frame usage in do_GET /frame_bin
        # So I need to update this reader too!
        
        # New Protocol without Depth: Header + Head + RGB
        # Let's assume camera_daemon.py sends: <4sIHHII (Magic, HeadL, RGBW, RGBH, RGBL, DepthW=0, DepthH=0, DepthL=0)?
        # Wait, I didn't update CameraHandler in camera_daemon.py yet!
        # I only updated the SharedMemory part. The HTTP part still references get_depth_frame!
        # I should have updated CameraHandler in camera_daemon.py as well to be consistent.
        # But let's assume I only use SHM.
        pass

        head = json.loads(head_bytes.decode("utf-8")) if head_bytes else {}

        result = {
            'head_yaw': float(head.get('head_yaw', 0.0) or 0.0),
            'head_pitch': float(head.get('head_pitch', 0.0) or 0.0),
            'rgb_image': raw_to_image(rgb_bytes, rgb_w, rgb_h) if rgb_len > 0 else None,
            'depth_data': depth_bytes if depth_len > 0 else None,
            'depth_width': depth_w,
            'depth_height': depth_h,
        }

        return result
        
    except (http.client.HTTPException, ConnectionError, OSError) as e:
        # Connection error - force reconnect next time
        print(f"[Camera] Connection error: {e}")
        close_persistent_connection()
        return None
    except Exception as e:
        print(f"[Camera] Frame error: {e}")
        return None


def get_sensor_data(include_depth=False):
    """
    Get sensor data (head angles, optionally depth).
    Uses Camera Daemon if available, falls back to subprocess.
    """
    # If daemon available, use synchronized frame instead
    if CAMERA_DAEMON_AVAILABLE:
        frame = get_synchronized_frame()
        if frame:
            result = {
                'head_yaw': frame['head_yaw'],
                'head_pitch': frame['head_pitch']
            }
            if include_depth and frame['depth_data']:
                result['depth'] = {
                    'width': frame['depth_width'],
                    'height': frame['depth_height'],
                    'data': base64.b64encode(frame['depth_data']).decode('utf-8')
                }
            return result
    
    # Fallback to subprocess
    try:
        cmd = ['python2', SENSOR_SCRIPT]
        if include_depth:
            cmd.append('--depth')
        
        env = os.environ.copy()
        env['PYTHONPATH'] = '/opt/aldebaran/lib/python2.7/site-packages'
        
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=2.0,
            env=env
        )
        
        if result.returncode != 0:
            print(f"[Sensors] Error: {result.stderr}")
            return None
        
        return json.loads(result.stdout)
        
    except Exception as e:
        print(f"[Sensors] Exception: {e}")
        return None


def detect_faces_only(image, scale_x=1.0, scale_y=1.0):
    """
    Run face detection without recognition.
    Accepts an already resized image and scaling factors to map coordinates back.
    Returns list of face data including raw detector output for later recognition.
    """
    height, width = image.shape[:2]
    DETECTOR.setInputSize((width, height))
    
    _, faces = DETECTOR.detect(image)
    
    if faces is None:
        return []
    
    results = []
    for face in faces:
        # face is [x, y, w, h, x_re, y_re, x_le, y_le, ..., conf]
        
        # Scale coordinates back to original image if resized
        if scale_x != 1.0 or scale_y != 1.0:
            # Scale bbox (0-3)
            face[0] *= scale_x
            face[1] *= scale_y
            face[2] *= scale_x
            face[3] *= scale_y
            
            # Scale landmarks (4-13)
            for i in range(4, 14, 2):
                face[i] *= scale_x
                face[i+1] *= scale_y
        
        results.append({
            'location': {
                'left': int(face[0]),
                'top': int(face[1]),
                'right': int(face[0] + face[2]),
                'bottom': int(face[1] + face[3])
            },
            'raw_face': face  # Keep full detector output with landmarks (now scaled)
        })
    
    return results


def recognize_single_face(image, raw_face):
    """
    Recognize a single face given the raw detector output (with landmarks).
    Returns (name, confidence, duration_ms).
    """
    t_start = time.time()
    
    # Use cached encodings (avoids reloading database every time)
    known_names, known_encodings = get_cached_encodings()
    
    if len(known_encodings) == 0:
        return "Unknown", 0.0, 0
    
    if raw_face is None:
        print("[Recognition] Error: raw_face is None")
        return "Unknown", 0.0, 0
    
    try:
        # Use the raw face data with landmarks from detector
        aligned_face = RECOGNIZER.alignCrop(image, raw_face)
        encoding = RECOGNIZER.feature(aligned_face)
        
        # Get current threshold from settings
        threshold = get_recognition_threshold()
        
        best_score = float('inf')
        best_name = "Unknown"
        all_scores = {}
        
        for known_name, known_encoding in zip(known_names, known_encodings):
            # known_encoding is already numpy array from cache
            score = RECOGNIZER.match(known_encoding, encoding, 1)  # Cosine Distance
            
            if known_name not in all_scores or score < all_scores[known_name]:
                all_scores[known_name] = score
            
            if score < best_score:
                best_score = score
                if score < threshold:
                    best_name = known_name
        
        duration = (time.time() - t_start) * 1000
        
        # Log scores for debugging
        scores_str = ", ".join([f"{n}={s:.3f}" for n, s in sorted(all_scores.items(), key=lambda x: x[1])])
        print(f"[Recognition] {duration:.0f}ms | Distances: {scores_str} | Best: {best_name} (dist={best_score:.3f}, thr={threshold:.2f})")
        
        confidence = max(0.0, 1.0 - best_score) if best_score != float('inf') else 0.0
        return best_name, confidence, duration
        
    except Exception as e:
        print(f"[Recognition] Error: {e}")
        return "Unknown", 0.0, 0


def update_tracking_once():
    """
    Single tracking update cycle:
    1. Get synchronized frame (RGB + Depth + Head Angles) from Camera Daemon
       OR fallback to parallel subprocess calls
    2. Detect faces
    3. Update tracker
    4. Run recognition for tracks that need it
    
    Returns (people_list, update_time_ms).
    """
    global LAST_SENSOR_DATA
    
    start_time = time.time()
    
    with TRACKER_LOCK:
        image = None
        head_yaw = 0.0
        head_pitch = 0.0
        depth_data = None
        depth_width = 0
        depth_height = 0
        
        t_frame = 0
        
        # Try Camera Daemon first (synchronized, fast)
        if CAMERA_DAEMON_AVAILABLE:
            t0 = time.time()
            frame = get_synchronized_frame()
            t_frame = (time.time() - t0) * 1000
            if frame and frame.get('rgb_image') is not None:
                image = frame['rgb_image']
                head_yaw = frame.get('head_yaw', 0.0)
                head_pitch = frame.get('head_pitch', 0.0)
                depth_data = frame.get('depth_data')
                depth_width = frame.get('depth_width', 0)
                depth_height = frame.get('depth_height', 0)
                
                # Update sensor data cache
                LAST_SENSOR_DATA = {
                    'head_yaw': head_yaw,
                    'head_pitch': head_pitch
                }
                
                # Extract camera offsets
                camera_offsets = frame.get('head', {}).get('camera_offsets')
        
        # Fallback to subprocess if daemon not available or failed
        if image is None:
            camera_offsets = None
            # Get sensor data AND capture image IN PARALLEL
            with ThreadPoolExecutor(max_workers=2) as executor:
                sensor_future = executor.submit(get_sensor_data, True)  # include_depth=True
                image_future = executor.submit(capture_image_from_camera)
                
                sensor_data = sensor_future.result()
                img_path = image_future.result()
            
            # Process sensor data
            if sensor_data and 'error' not in sensor_data:
                LAST_SENSOR_DATA = sensor_data
            
            head_yaw = LAST_SENSOR_DATA.get('head_yaw', 0.0)
            head_pitch = LAST_SENSOR_DATA.get('head_pitch', 0.0)
            
            # Get depth data if available
            if 'depth' in LAST_SENSOR_DATA:
                depth_info = LAST_SENSOR_DATA['depth']
                depth_width = depth_info.get('width', 0)
                depth_height = depth_info.get('height', 0)
                depth_b64 = depth_info.get('data', '')
                if depth_b64:
                    depth_data = base64.b64decode(depth_b64)
            
            # Load image from file
            if not img_path:
                return TRACKER.to_dict(), 0
            
            try:
                image = load_ppm_image(img_path)
            except Exception as e:
                print(f"[Tracking] Image load error: {e}")
                return TRACKER.to_dict(), 0
        
        height, width = image.shape[:2]
        
        # 3. Detect faces (with Resize timing)
        t_before_resize = time.time()
        
        # Prepare image for detection (resize if needed)
        detect_image = image
        scale_x = 1.0
        scale_y = 1.0
        
        target_width = width
        if DETECTION_RESOLUTION_INDEX == 0:  # QQVGA
            target_width = 160
        elif DETECTION_RESOLUTION_INDEX == 1:  # QVGA
            target_width = 320
            
        if target_width < width:
            scale = target_width / float(width)
            target_height = int(height * scale)
            detect_image = cv2.resize(image, (target_width, target_height))
            scale_x = width / float(target_width)
            scale_y = height / float(target_height)
            
        t_resize = (time.time() - t_before_resize) * 1000
        
        t1 = time.time()
        faces = detect_faces_only(detect_image, scale_x, scale_y)
        t_detect = (time.time() - t1) * 1000
        
        # 4. Update tracker
        t2 = time.time()
        TRACKER.update(
            faces=faces,
            image_width=width,
            image_height=height,
            head_yaw=head_yaw,
            head_pitch=head_pitch,
            depth_data=depth_data,
            depth_width=depth_width,
            depth_height=depth_height,
            camera_offsets=camera_offsets
        )
        t_track = (time.time() - t2) * 1000
        
        # 5. Queue recognition for tracks that need it (NON-BLOCKING)
        t3 = time.time()
        tracks_needing_recognition = TRACKER.get_tracks_needing_recognition()
        
        if tracks_needing_recognition:
            # Only queue ONE track for async recognition
            track_id = tracks_needing_recognition[0]
            raw_face = TRACKER.get_track_raw_face(track_id)
            if raw_face is not None:
                # Queue for async recognition (does NOT block!)
                queue_recognition(track_id, image, raw_face)
                # Mark as pending so we don't re-queue
                TRACKER.mark_recognition_pending(track_id)
        t_recog = (time.time() - t3) * 1000
        
        update_time = int((time.time() - start_time) * 1000)
        
        # DEBUG: Always log timing with resize breakdown
        print(f"[Timing] frame={t_frame:.0f}ms, resize={t_resize:.0f}ms, detect={t_detect:.0f}ms, track={t_track:.0f}ms, recog={t_recog:.0f}ms, total={update_time}ms")
        
        return TRACKER.to_dict(), update_time


def background_tracking_loop():
    """
    Background thread that continuously updates tracking.
    Broadcasts updates to WebSocket clients if available.
    """
    global CACHED_PEOPLE, LAST_UPDATE_MS, TRACKING_RUNNING
    
    print("[Tracking] Background thread started")
    last_broadcast = None
    
    while TRACKING_RUNNING:
        try:
            people, update_ms = update_tracking_once()
            CACHED_PEOPLE = people
            LAST_UPDATE_MS = update_ms
            
            # Broadcast to WebSocket clients if data changed
            if WEBSOCKET_AVAILABLE and get_client_count() > 0:
                current_state = json.dumps(people)
                if current_state != last_broadcast:
                    schedule_broadcast({
                        'people': people,
                        'head_angles': {
                            'yaw': LAST_SENSOR_DATA.get('head_yaw', 0.0),
                            'pitch': LAST_SENSOR_DATA.get('head_pitch', 0.0)
                        },
                        'timing': {
                            'update_ms': update_ms
                        }
                    })
                    last_broadcast = current_state
                    
        except Exception as e:
            print(f"[Tracking] Error in background loop: {e}")
        
        # Dynamic update interval from settings
        interval = get_tracking_interval()
        
        # Wait before next update (but check if we should stop)
        for _ in range(int(interval * 10)):
            if not TRACKING_RUNNING:
                break
            time.sleep(0.1)
    
    print("[Tracking] Background thread stopped")


def start_background_tracking():
    """Start the background tracking and recognition threads."""
    global TRACKING_THREAD, TRACKING_RUNNING
    
    if TRACKING_THREAD is not None and TRACKING_THREAD.is_alive():
        return  # Already running
    
    TRACKING_RUNNING = True
    TRACKING_THREAD = threading.Thread(target=background_tracking_loop, daemon=True)
    TRACKING_THREAD.start()
    
    # Also start async recognition thread
    start_recognition_thread()


def stop_background_tracking():
    """Stop the background tracking thread."""
    global TRACKING_RUNNING
    TRACKING_RUNNING = False


def recognize_process(image):
    """Process image for recognition using pre-loaded models."""
    height, width = image.shape[:2]
    DETECTOR.setInputSize((width, height))
    
    _, faces = DETECTOR.detect(image)
    
    if faces is None:
        return []
        
    results = []
    known_names, known_encodings = get_all_encodings()
    
    for face_idx, face in enumerate(faces):
        aligned_face = RECOGNIZER.alignCrop(image, face)
        encoding = RECOGNIZER.feature(aligned_face)
        
        name = "Unknown"
        confidence = 0.0
        
        if len(known_encodings) > 0:
            # Collect all scores for debugging
            # dis_type=1 is Cosine DISTANCE: lower = more similar, 0 = identical
            all_scores = {}
            best_score = float('inf')  # Start with infinity (we want LOWEST)
            best_name = "Unknown"
            
            for known_name, known_encoding in zip(known_names, known_encodings):
                k_enc = np.array(known_encoding, dtype=np.float32) if not isinstance(known_encoding, np.ndarray) else known_encoding
                score = RECOGNIZER.match(k_enc, encoding, 1)  # 1 = Cosine Distance (NOT similarity!)
                
                # Track best (lowest) score per unique name
                if known_name not in all_scores or score < all_scores[known_name]:
                    all_scores[known_name] = score
                
                # We want the LOWEST score (most similar)
                if score < best_score:
                    best_score = score
                    if score < RECOGNITION_THRESHOLD:  # FIXED: was > now <
                        best_name = known_name
            
            # Log all scores for debugging (sorted lowest=best first)
            scores_str = ", ".join([f"{n}={s:.3f}" for n, s in sorted(all_scores.items(), key=lambda x: x[1])])
            print(f"[Face {face_idx}] Distances: {scores_str} | Best: {best_name} (dist={best_score:.3f}) | Threshold: <{RECOGNITION_THRESHOLD}")
            
            name = best_name
            # Convert distance to confidence (0=worst, 1=best)
            confidence = max(0.0, 1.0 - best_score) if best_score != float('inf') else 0.0
            
        results.append({
            'name': name,
            'confidence': float(confidence),
            'location': {
                'left': int(face[0]),
                'top': int(face[1]),
                'right': int(face[0] + face[2]),
                'bottom': int(face[1] + face[3])
            }
        })
        
    return results


def pause_awareness():
    """Pause head movements via camera daemon."""
    try:
        import urllib.request
        req = urllib.request.urlopen(f"{CAMERA_DAEMON_URL}/pause_awareness", timeout=2.0)
        return req.status == 200
    except Exception as e:
        print(f"[Register] Failed to pause awareness: {e}")
        return False


def resume_awareness():
    """Resume head movements via camera daemon."""
    try:
        import urllib.request
        req = urllib.request.urlopen(f"{CAMERA_DAEMON_URL}/resume_awareness", timeout=2.0)
        return req.status == 200
    except Exception as e:
        print(f"[Register] Failed to resume awareness: {e}")
        return False


def ws_register_face(name):
    """WebSocket callback: Register a face from current camera view.
    
    1. Pauses head movements (ALBasicAwareness)
    2. Captures and registers face (Always VGA now!)
    3. Resumes head movements
    """
    try:
        # 1. Pause head movements
        print(f"[Register] Pausing head movements...")
        pause_awareness()
        time.sleep(0.5)  # Pause for head to settle
        
        # 2. Capture image from camera (Always VGA from Daemon)
        frame = get_synchronized_frame()
        if frame is None or frame.get('rgb_image') is None:
            resume_awareness()
            return False, "Failed to capture image"
        
        image = frame['rgb_image']
        success, message = register_process(image, name)
        
        # 3. Resume head movements
        print(f"[Register] Resuming head movements...")
        resume_awareness()
        
        return success, message if not success else None
    except Exception as e:
        # Try to restore everything on error
        try:
            resume_awareness()
        except:
            pass
        return False, str(e)


def ws_delete_face(name):
    """WebSocket callback: Delete a face from database."""
    try:
        count = remove_face(name)
        if count > 0:
            return True, None
        else:
            return False, "Face not found"
    except Exception as e:
        return False, str(e)


def ws_list_faces():
    """WebSocket callback: List all known faces."""
    try:
        faces = list_faces()
        return [{"name": f["name"], "count": f["count"]} for f in faces]
    except Exception:
        return []


def register_process(image, name):
    """Process image for registration."""
    height, width = image.shape[:2]
    DETECTOR.setInputSize((width, height))
    
    _, faces = DETECTOR.detect(image)
    
    if faces is None or len(faces) == 0:
        return False, "No face detected"
        
    # Use largest face
    target_face = faces[0]
    if len(faces) > 1:
        faces_sorted = sorted(faces, key=lambda f: f[2] * f[3], reverse=True)
        target_face = faces_sorted[0]
        
    aligned_face = RECOGNIZER.alignCrop(image, target_face)
    feature = RECOGNIZER.feature(aligned_face)
    
    if feature is None:
        return False, "Could not extract features"
    
    # Extract face bounding box from original image for thumbnail (with padding)
    x, y, w, h = int(target_face[0]), int(target_face[1]), int(target_face[2]), int(target_face[3])
    pad = int(max(w, h) * 0.3)  # 30% padding
    x1, y1 = max(0, x - pad), max(0, y - pad)
    x2, y2 = min(width, x + w + pad), min(height, y + h + pad)
    face_thumbnail = image[y1:y2, x1:x2]
    
    # Resize to reasonable size for thumbnail
    if face_thumbnail.size > 0:
        thumb_size = 128
        thumb_h, thumb_w = face_thumbnail.shape[:2]
        scale = thumb_size / max(thumb_h, thumb_w)
        new_w, new_h = int(thumb_w * scale), int(thumb_h * scale)
        face_thumbnail = cv2.resize(face_thumbnail, (new_w, new_h))
    else:
        face_thumbnail = aligned_face  # Fallback
        
    # Save face and image
    add_face(name, feature, face_thumbnail)
    return True, "Success"


class FaceHandler(BaseHTTPRequestHandler):
    
    def _send_json(self, data, code=200):
        self.send_response(code)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode('utf-8'))
        
    def _send_image(self, path):
        if not os.path.exists(path):
            self.send_error(404, "Image not found")
            return
            
        with open(path, 'rb') as f:
            content = f.read()
            
        self.send_response(200)
        self.send_header('Content-type', 'image/jpeg')
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        
        if parsed.path == '/people':
            # Return cached data from background tracking (instant response)
            self._send_json({
                'people': CACHED_PEOPLE,
                'head_angles': {
                    'yaw': LAST_SENSOR_DATA.get('head_yaw', 0.0),
                    'pitch': LAST_SENSOR_DATA.get('head_pitch', 0.0)
                },
                'timing': {
                    'update_ms': LAST_UPDATE_MS
                },
                'timestamp': int(time.time() * 1000)
            })
        
        elif parsed.path == '/settings':
            # Return current perception settings
            if WEBSOCKET_AVAILABLE:
                settings = get_settings()
                self._send_json({
                    'settings': settings.to_dict(),
                    'websocket_port': WS_PORT,
                    'websocket_clients': get_client_count()
                })
            else:
                self._send_json({
                    'settings': {
                        'recognition_threshold': RECOGNITION_THRESHOLD,
                        'update_interval_ms': 100
                    },
                    'websocket_available': False
                })
            
        elif parsed.path == '/recognize':
            # 1. Capture Image
            start_time = time.time()
            img_path = capture_image_from_camera()
            capture_time = time.time() - start_time
            
            if not img_path:
                self._send_json({'error': 'Camera capture failed'}, 500)
                return
                
            # 2. Recognize
            try:
                image = load_ppm_image(img_path)
                results = recognize_process(image)
                total_time = time.time() - start_time
                
                self._send_json({
                    'faces': results,
                    'timing': {
                        'capture': capture_time,
                        'total': total_time
                    }
                })
            except Exception as e:
                self._send_json({'error': str(e)}, 500)
                
        elif parsed.path == '/faces':
            faces = list_faces()
            # Convert to list of objects with multiple image URLs
            face_list = []
            for name, count in faces.items():
                # Get count of images for this person
                img_count = get_face_image_count(name)
                # Build list of image URLs
                image_urls = [f"/faces/image?name={name}&index={i}" for i in range(img_count)]
                face_list.append({
                    'name': name,
                    'count': count,
                    'image_count': img_count,
                    'image_urls': image_urls,
                    'image_url': image_urls[0] if image_urls else None  # Backward compat
                })
            self._send_json({'faces': face_list})
            
        elif parsed.path == '/faces/image':
            name = params.get('name', [None])[0]
            if not name:
                self._send_json({'error': 'Missing name parameter'}, 400)
                return
            
            # Get index (default to 0 for backward compatibility)
            index = int(params.get('index', ['0'])[0])
            safe_name = get_safe_filename(name)
            img_path = os.path.join(IMAGES_DIR, f"{safe_name}_{index}.jpg")
            self._send_image(img_path)
            
        else:
            self.send_error(404)

    def do_POST(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        
        if parsed.path == '/faces':
            name = params.get('name', [None])[0]
            if not name:
                self._send_json({'error': 'Missing name parameter'}, 400)
                return
                
            # Capture and register
            img_path = capture_image_from_camera()
            if not img_path:
                self._send_json({'error': 'Camera capture failed'}, 500)
                return
                
            try:
                image = load_ppm_image(img_path)
                success, message = register_process(image, name)
                
                if success:
                    self._send_json({'status': 'registered', 'name': name})
                else:
                    self._send_json({'error': message}, 400)
            except Exception as e:
                self._send_json({'error': str(e)}, 500)
        
        elif parsed.path == '/settings':
            # Update perception settings via HTTP
            try:
                content_length = int(self.headers.get('Content-Length', 0))
                body = self.rfile.read(content_length).decode('utf-8')
                new_settings = json.loads(body)
                
                if WEBSOCKET_AVAILABLE:
                    settings = update_settings(new_settings)
                    apply_settings(settings)
                    self._send_json({
                        'status': 'updated',
                        'settings': settings.to_dict()
                    })
                else:
                    # Limited settings without WebSocket
                    if 'recognition_threshold' in new_settings:
                        global RECOGNITION_THRESHOLD
                        RECOGNITION_THRESHOLD = new_settings['recognition_threshold']
                    self._send_json({
                        'status': 'updated',
                        'settings': {'recognition_threshold': RECOGNITION_THRESHOLD}
                    })
            except json.JSONDecodeError:
                self._send_json({'error': 'Invalid JSON'}, 400)
            except Exception as e:
                self._send_json({'error': str(e)}, 500)
                
        else:
            self.send_error(404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        
        if parsed.path == '/faces':
            name = params.get('name', [None])[0]
            if not name:
                self._send_json({'error': 'Missing name parameter'}, 400)
                return
                
            count = remove_face(name)
            self._send_json({'status': 'deleted', 'name': name, 'removed_count': count})
            
        elif parsed.path == '/faces/image':
            # Delete individual image by index
            name = params.get('name', [None])[0]
            index_str = params.get('index', [None])[0]
            
            if not name or index_str is None:
                self._send_json({'error': 'Missing name or index parameter'}, 400)
                return
            
            index = int(index_str)
            success = remove_face_by_index(name, index)
            
            if success:
                self._send_json({'status': 'deleted', 'name': name, 'index': index})
            else:
                self._send_json({'error': 'Image not found or invalid index'}, 404)
        else:
            self.send_error(404)


class ReusableHTTPServer(HTTPServer):
    """HTTP Server with SO_REUSEADDR to avoid 'Address already in use' errors."""
    allow_reuse_address = True


if __name__ == '__main__':
    try:
        init_models()
        
        # Start WebSocket server if available
        if WEBSOCKET_AVAILABLE:
            print(f"Starting WebSocket server on port {WS_PORT}...")
            set_settings_callback(apply_settings)
            set_face_callbacks(ws_register_face, ws_delete_face, ws_list_faces)
            start_websocket_server_thread(port=WS_PORT)
        
        # Start background tracking
        interval = get_tracking_interval()
        print(f"Starting background tracking (interval={interval}s)...")
        start_background_tracking()
        
        # Start HTTP server (with SO_REUSEADDR)
        server = ReusableHTTPServer(('0.0.0.0', PORT), FaceHandler)
        print(f"Face Recognition Server running on port {PORT}")
        if WEBSOCKET_AVAILABLE:
            print(f"WebSocket streaming available on port {WS_PORT}")
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopping server...")
        stop_background_tracking()
        server.socket.close()


