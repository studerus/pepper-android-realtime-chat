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
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# Add custom packages path
sys.path.insert(0, '/home/nao/python_packages')
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')

import cv2
import numpy as np

# Import our modules
from face_database import add_face, remove_face, list_faces, get_all_encodings, get_safe_filename, IMAGES_DIR
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
RECOGNITION_THRESHOLD = 0.8  # Default, overridable (cosine distance: lower=more similar)

PORT = 5000
WS_PORT = 5002  # Changed to 5002 to avoid conflicts

# Camera Daemon URL (persistent camera subscriptions)
CAMERA_DAEMON_URL = 'http://127.0.0.1:5050'
CAMERA_DAEMON_AVAILABLE = False

# Fallback to subprocess if daemon not available
SENSOR_SCRIPT = '/home/nao/face_data/get_sensors.py'


def get_tracking_interval():
    """Get current tracking interval from settings."""
    if WEBSOCKET_AVAILABLE:
        settings = get_settings()
        return settings.update_interval_ms / 1000.0
    return 0.5  # Default 500ms (faster updates)


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
                name, confidence = recognize_single_face(image, raw_face)
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
            recognition_cooldown_ms=settings.recognition_cooldown_ms
        )
    else:
        TRACKER = FaceTracker(
            max_angle_distance=15.0,
            track_timeout_ms=3000,
            recognition_cooldown_ms=3000
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
    global TRACKER, RECOGNITION_THRESHOLD
    
    with TRACKER_LOCK:
        if TRACKER:
            TRACKER.max_angle_distance = settings.max_angle_distance
            TRACKER.track_timeout_ms = settings.track_timeout_ms
            TRACKER.recognition_cooldown_ms = settings.recognition_cooldown_ms
            TRACKER.gaze_threshold = settings.gaze_center_tolerance
    
    RECOGNITION_THRESHOLD = settings.recognition_threshold
    
    # Apply camera resolution if changed
    if hasattr(settings, 'camera_resolution'):
        set_camera_resolution(settings.camera_resolution)
    
    print(f"[Settings] Applied: threshold={settings.recognition_threshold}, "
          f"max_dist={settings.max_angle_distance}°, timeout={settings.track_timeout_ms}ms")


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


def get_synchronized_frame():
    """
    Get synchronized RGB + Depth + Head Angles from Camera Daemon.
    Uses optimized binary protocol for minimal latency.
    Returns dict with 'rgb_image', 'depth_data', 'head_yaw', 'head_pitch', etc.
    """
    try:
        import urllib.request
        req = urllib.request.urlopen(f"{CAMERA_DAEMON_URL}/frame_bin", timeout=1.5)
        blob = req.read()

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
        depth_bytes = blob[offset:offset + depth_len]

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


def detect_faces_only(image):
    """
    Run face detection without recognition.
    Returns list of face data including raw detector output for later recognition.
    """
    height, width = image.shape[:2]
    DETECTOR.setInputSize((width, height))
    
    _, faces = DETECTOR.detect(image)
    
    if faces is None:
        return []
    
    results = []
    for face in faces:
        results.append({
            'location': {
                'left': int(face[0]),
                'top': int(face[1]),
                'right': int(face[0] + face[2]),
                'bottom': int(face[1] + face[3])
            },
            'raw_face': face  # Keep full detector output with landmarks
        })
    
    return results


def recognize_single_face(image, raw_face):
    """
    Recognize a single face given the raw detector output (with landmarks).
    Returns (name, confidence).
    """
    # Use cached encodings (avoids reloading database every time)
    known_names, known_encodings = get_cached_encodings()
    
    if len(known_encodings) == 0:
        return "Unknown", 0.0
    
    if raw_face is None:
        print("[Recognition] Error: raw_face is None")
        return "Unknown", 0.0
    
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
        
        # Log scores for debugging
        scores_str = ", ".join([f"{n}={s:.3f}" for n, s in sorted(all_scores.items(), key=lambda x: x[1])])
        print(f"[Recognition] Distances: {scores_str} | Best: {best_name} (dist={best_score:.3f}, thr={threshold:.2f})")
        
        confidence = max(0.0, 1.0 - best_score) if best_score != float('inf') else 0.0
        return best_name, confidence
        
    except Exception as e:
        print(f"[Recognition] Error: {e}")
        return "Unknown", 0.0


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
        
        # Fallback to subprocess if daemon not available or failed
        if image is None:
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
        
        # 3. Detect faces
        t1 = time.time()
        faces = detect_faces_only(image)
        t_detect = (time.time() - t1) * 1000
        
        # 4. Update tracker
        t2 = time.time()
        TRACKER.update(
            faces=faces,
            image_width=width,
            image_height=height,
            head_yaw_deg=head_yaw,
            head_pitch_deg=head_pitch,
            depth_data=depth_data,
            depth_width=depth_width,
            depth_height=depth_height
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
        
        # Debug timing (only print if really slow)
        if update_time > 800:
            print(f"[Timing] SLOW: frame={t_frame:.0f}ms, detect={t_detect:.0f}ms, track={t_track:.0f}ms, recog={t_recog:.0f}ms, total={update_time}ms")
        
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


def ws_register_face(name):
    """WebSocket callback: Register a face from current camera view."""
    try:
        # Capture image from camera
        image = get_synchronized_frame()
        if image is None:
            return False, "Failed to capture image"
        
        success, message = register_process(image, name)
        return success, message if not success else None
    except Exception as e:
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
        
    # Save face and image
    add_face(name, feature, aligned_face)
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
                        'update_interval_ms': 500
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
            # Convert to list of objects
            face_list = []
            for name, count in faces.items():
                face_list.append({
                    'name': name,
                    'count': count,
                    'image_url': f"/faces/image?name={name}"
                })
            self._send_json({'faces': face_list})
            
        elif parsed.path == '/faces/image':
            name = params.get('name', [None])[0]
            if not name:
                self._send_json({'error': 'Missing name parameter'}, 400)
                return
                
            safe_name = get_safe_filename(name)
            img_path = os.path.join(IMAGES_DIR, f"{safe_name}.jpg")
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


