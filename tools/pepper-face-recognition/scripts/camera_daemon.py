#!/usr/bin/env python2
# -*- coding: utf-8 -*-
"""
Persistent Camera Daemon for Head-Based Perception System.

Keeps both RGB and Depth cameras subscribed permanently.
Provides synchronized frames via HTTP endpoint.

Performance optimizations:
- No subscribe/unsubscribe overhead per frame
- YUV422 native colorspace for RGB (fastest)
- Synchronized RGB + Depth + Head Angles via Threading & Timestamp Matching
- Minimal latency (~15-20ms vs ~200ms)

Usage:
    PYTHONPATH=/opt/aldebaran/lib/python2.7/site-packages python2 camera_daemon.py

HTTP Endpoints (port 5050):
    GET /frame       - Get synchronized RGB + Depth + Head Angles
    GET /rgb         - Get RGB frame only
    GET /depth       - Get Depth frame only
    GET /status      - Get daemon status
    GET /shutdown    - Shutdown daemon gracefully
"""

from __future__ import print_function
import sys
import json
import math
import time
import base64
import threading
from collections import deque

sys.path.insert(0, '/opt/aldebaran/lib/python2.7/site-packages')

# HTTP server imports
from BaseHTTPServer import HTTPServer, BaseHTTPRequestHandler

PORT = 5050

# Global state
session = None
video = None
memory = None
rgb_subscriber = None
depth_subscriber = None
running = True
lock = threading.Lock()

# Camera settings
# RGB: Camera 0 (Top), ColorSpace 13 (BGR), FPS 15
# Resolution: 0=QQVGA(160x120), 1=QVGA(320x240), 2=VGA(640x480)
RGB_CAMERA = 0      # Top camera
RGB_RESOLUTION = 1  # Default: QVGA (320x240)
RGB_COLORSPACE = 13  # kBGRColorSpace (3 bytes/pixel)
RGB_FPS = 15

# Resolution names for logging
RESOLUTION_NAMES = {0: "QQVGA(160x120)", 1: "QVGA(320x240)", 2: "VGA(640x480)"}

# Depth: Camera 2, Resolution 1 (QQVGA 160x120), ColorSpace 17 (Depth), FPS 10
DEPTH_CAMERA = 2    # Depth camera
DEPTH_RESOLUTION = 1  # QQVGA (160x120 or 160x90)
DEPTH_COLORSPACE = 17  # Depth
DEPTH_FPS = 10

# Camera Offsets (Parallax correction)
# Top Camera Z: 0.1631m, Depth Camera Z: 0.1194m -> Delta Z = 0.0437m
# Top Camera X: 0.0868m, Depth Camera X: 0.05138m -> Delta X = 0.03542m
CAM_OFFSETS = {
    "dx": 0.03542,
    "dy": 0.00000,
    "dz": 0.04370
}

# Depth Buffer for synchronization
class DepthBuffer(object):
    def __init__(self, maxlen=5):
        self.buffer = deque(maxlen=maxlen)
        self.lock = threading.Lock()
    
    def add(self, frame):
        if not frame:
            return
        with self.lock:
            self.buffer.append(frame)
            
    def get_best_match(self, timestamp, max_delta_s=0.1):
        """Find depth frame closest to the given timestamp."""
        with self.lock:
            if not self.buffer:
                return None
            
            best_frame = None
            min_diff = float('inf')
            
            # Search backwards (newest first)
            for frame in reversed(self.buffer):
                ts = frame.get('timestamp', 0)
                diff = abs(timestamp - ts)
                
                if diff < min_diff:
                    min_diff = diff
                    best_frame = frame
                
                # If we're getting further away, stop searching (assuming sorted by time)
                # But due to network jitter, buffer might not be strictly sorted, so exhaustive search is safer for small calc
                
            if best_frame and min_diff < max_delta_s:
                return best_frame
            
            # Fallback: Return newest if within tolerance
            if self.buffer and (time.time() - self.buffer[-1]['timestamp']) < max_delta_s:
                 return self.buffer[-1]
                 
            return None

depth_buffer = DepthBuffer(maxlen=5)


def init_naoqi():
    """Initialize NAOqi connection and subscribe to cameras."""
    global session, video, memory, rgb_subscriber, depth_subscriber
    
    import qi
    session = qi.Session()
    session.connect("tcp://127.0.0.1:9559")
    
    video = session.service("ALVideoDevice")
    memory = session.service("ALMemory")
    
    # Subscribe to RGB camera (persistent)
    rgb_subscriber = video.subscribeCamera(
        "perception_rgb",
        RGB_CAMERA,
        RGB_RESOLUTION,
        RGB_COLORSPACE,
        RGB_FPS
    )
    print("[Daemon] RGB camera subscribed: {}".format(rgb_subscriber))
    
    # Subscribe to Depth camera (persistent)
    depth_subscriber = video.subscribeCamera(
        "perception_depth",
        DEPTH_CAMERA,
        DEPTH_RESOLUTION,
        DEPTH_COLORSPACE,
        DEPTH_FPS
    )
    print("[Daemon] Depth camera subscribed: {}".format(depth_subscriber))
    
    return True


def cleanup_naoqi():
    """Unsubscribe from cameras and cleanup."""
    global video, rgb_subscriber, depth_subscriber
    
    if video:
        try:
            if rgb_subscriber:
                video.unsubscribe(rgb_subscriber)
                print("[Daemon] RGB camera unsubscribed")
            if depth_subscriber:
                video.unsubscribe(depth_subscriber)
                print("[Daemon] Depth camera unsubscribed")
        except Exception as e:
            print("[Daemon] Cleanup error: {}".format(e))


def set_camera_resolution(new_resolution):
    """Change RGB camera resolution. Requires resubscribe."""
    global RGB_RESOLUTION, rgb_subscriber, video
    
    if new_resolution not in [0, 1, 2]:
        print("[Daemon] Invalid resolution: {}".format(new_resolution))
        return False
    
    if new_resolution == RGB_RESOLUTION:
        return True  # No change needed
    
    with lock:
        try:
            # Unsubscribe old
            if rgb_subscriber and video:
                video.unsubscribe(rgb_subscriber)
                print("[Daemon] RGB camera unsubscribed for resolution change")
            
            # Update resolution
            RGB_RESOLUTION = new_resolution
            
            # Resubscribe with new resolution
            rgb_subscriber = video.subscribeCamera(
                "perception_rgb",
                RGB_CAMERA,
                RGB_RESOLUTION,
                RGB_COLORSPACE,
                RGB_FPS
            )
            print("[Daemon] RGB camera resubscribed at {}: {}".format(
                RESOLUTION_NAMES.get(new_resolution, str(new_resolution)),
                rgb_subscriber
            ))
            return True
            
        except Exception as e:
            print("[Daemon] Resolution change error: {}".format(e))
            return False


def get_head_angles():
    """Read current head angles from ALMemory."""
    if not memory:
        return None
    
    try:
        head_yaw_rad = memory.getData("Device/SubDeviceList/HeadYaw/Position/Sensor/Value")
        head_pitch_rad = memory.getData("Device/SubDeviceList/HeadPitch/Position/Sensor/Value")
        
        return {
            "head_yaw": math.degrees(head_yaw_rad),
            "head_pitch": math.degrees(head_pitch_rad),
            "head_yaw_rad": head_yaw_rad,
            "head_pitch_rad": head_pitch_rad,
            "camera_offsets": CAM_OFFSETS
        }
    except Exception as e:
        print("[Daemon] Head angles error: {}".format(e))
        return None


def get_rgb_frame(raw_data=False):
    """Get current RGB frame."""
    if not video or not rgb_subscriber:
        return None
    
    try:
        image = video.getImageRemote(rgb_subscriber)
        if image is None:
            return None
        
        width = image[0]
        height = image[1]
        layers = image[2]
        colorspace = image[3]
        timestamp_s = image[4]
        timestamp_us = image[5]
        data = image[6]
        
        # Return raw data if requested
        if raw_data:
            return {
                "width": width,
                "height": height,
                "layers": layers,
                "colorspace": colorspace,
                "timestamp": timestamp_s + timestamp_us / 1000000.0,
                "data": str(data) if data else ""
            }
        
        # Base64 encode the raw data
        data_b64 = base64.b64encode(str(data)) if data else ""
        
        return {
            "width": width,
            "height": height,
            "layers": layers,
            "colorspace": colorspace,  # 0 = YUV422
            "timestamp": timestamp_s + timestamp_us / 1000000.0,
            "data": data_b64
        }
    except Exception as e:
        print("[Daemon] RGB frame error: {}".format(e))
        return None


def get_depth_frame(raw_data=False):
    """Get current Depth frame."""
    if not video or not depth_subscriber:
        return None
    
    try:
        image = video.getImageRemote(depth_subscriber)
        if image is None:
            return None
        
        width = image[0]
        height = image[1]
        timestamp_s = image[4]
        timestamp_us = image[5]
        data = image[6]
        
        # Return raw data if requested
        if raw_data:
            return {
                "width": width,
                "height": height,
                "timestamp": timestamp_s + timestamp_us / 1000000.0,
                "data": str(data) if data else ""
            }
        
        # Base64 encode the raw depth data (16-bit)
        data_b64 = base64.b64encode(str(data)) if data else ""
        
        return {
            "width": width,
            "height": height,
            "timestamp": timestamp_s + timestamp_us / 1000000.0,
            "data": data_b64
        }
    except Exception as e:
        print("[Daemon] Depth frame error: {}".format(e))
        return None


def fetch_frame_threaded(func, result_container, key, raw_data):
    """Helper to run frame fetch in thread."""
    try:
        res = func(raw_data=raw_data)
        result_container[key] = res
    except Exception as e:
        print("[Daemon] Thread error fetching {}: {}".format(key, e))
        result_container[key] = None


def get_synchronized_frame():
    """
    Get synchronized RGB + Depth + Head Angles.
    Uses parallel fetching and timestamp matching.
    """
    with lock:
        start = time.time()
        
        results = {}
        threads = []
        
        # Create threads for parallel fetching
        t_rgb = threading.Thread(target=fetch_frame_threaded, args=(get_rgb_frame, results, 'rgb', False))
        t_depth = threading.Thread(target=fetch_frame_threaded, args=(get_depth_frame, results, 'depth', False))
        
        threads.append(t_rgb)
        threads.append(t_depth)
        
        # Perform HEAD fetch in main thread (very fast, just mem read)
        # We do this while threads are starting/running to maximize parallel overlap
        t_rgb.start()
        t_depth.start()
        
        head = get_head_angles()
        
        # Wait for threads
        for t in threads:
            t.join()
            
        rgb = results.get('rgb')
        new_depth = results.get('depth')
        
        # Add new depth to buffer
        if new_depth:
            depth_buffer.add(new_depth)
            
        # Synchronization Logic:
        # 1. Use current RGB timestamp as reference
        # 2. Find best matching Depth from buffer
        
        final_depth = None
        sync_delta = 0
        
        if rgb:
            rgb_ts = rgb.get('timestamp', 0)
            final_depth = depth_buffer.get_best_match(rgb_ts)
            
            if final_depth:
                sync_delta = abs(rgb_ts - final_depth.get('timestamp', 0)) * 1000 # ms
        else:
            # Fallback if no RGB (should rarely happen)
            final_depth = new_depth
            
        capture_time = (time.time() - start) * 1000  # ms
        
        # Log sync quality if poor (> 50ms)
        if sync_delta > 50:
            print("[Daemon] Poor sync: delta={}ms".format(int(sync_delta)))
        
        return {
            "head": head,
            "rgb": rgb,
            "depth": final_depth,
            "capture_ms": capture_time,
            "sync_delta_ms": sync_delta,
            "timestamp": time.time()
        }


class CameraHandler(BaseHTTPRequestHandler):
    """HTTP request handler for camera daemon."""
    
    def log_message(self, format, *args):
        # Suppress default logging
        pass
    
    def send_json(self, data, code=200):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data))
    
    def send_binary(self, data, code=200):
        self.send_response(code)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        global running
        
        if self.path == '/frame_bin':
            # Optimized binary frame endpoint WITH THREADING & SYNC
            import struct
            
            try:
                # We need to replicate the threaded logic here for raw_data=True
                # Or refactor common logic. Let's replicate for cleaner separation of binary/json logic
                
                with lock:
                    results = {}
                    threads = []
                    
                    t_rgb = threading.Thread(target=fetch_frame_threaded, args=(get_rgb_frame, results, 'rgb', True))
                    t_depth = threading.Thread(target=fetch_frame_threaded, args=(get_depth_frame, results, 'depth', True))
                    
                    threads.append(t_rgb)
                    threads.append(t_depth)
                    
                    t_rgb.start()
                    t_depth.start()
                    
                    head = get_head_angles()
                    
                    for t in threads:
                        t.join()
                        
                    rgb = results.get('rgb')
                    new_depth = results.get('depth')
                    
                    # Buffer depth for sync?
                    # For binary endpoint which is polled, we might just want "latest pair" 
                    # OR we can assume the daemon is polled frequently enough.
                    # Implementing buffer logic for binary endpoint too:
                    if new_depth:
                         # We need to buffer the RAW depth? 
                         # Actually DepthBuffer stores whatever dict passed. 
                         # But memory usage! Storing raw strings of 160x120x2 bytes = 38KB.
                         # 5 frames = 200KB. Totally fine.
                         depth_buffer.add(new_depth)
                    
                    final_depth = None
                    if rgb:
                        rgb_ts = rgb.get('timestamp', 0)
                        final_depth = depth_buffer.get_best_match(rgb_ts)
                    if not final_depth:
                        final_depth = new_depth
                
                head_json = json.dumps(head) if head else "{}"
                head_len = len(head_json)
                
                rgb_data = ""
                rgb_w = 0
                rgb_h = 0
                if rgb and rgb.get('data'):
                    rgb_data = rgb['data']
                    rgb_w = rgb['width']
                    rgb_h = rgb['height']
                rgb_len = len(rgb_data)
                
                depth_data = ""
                depth_w = 0
                depth_h = 0
                if final_depth and final_depth.get('data'):
                    depth_data = final_depth['data']
                    depth_w = final_depth['width']
                    depth_h = final_depth['height']
                depth_len = len(depth_data)
                
                # Pack header
                # Python 2 struct.pack expects strings
                header = struct.pack("<4sIHHIHHI", "PFR1", head_len, rgb_w, rgb_h, rgb_len, depth_w, depth_h, depth_len)
                
                # Send everything as one binary blob
                self.send_binary(header + head_json + rgb_data + depth_data)
                
            except Exception as e:
                print("[Daemon] Binary endpoint error: {}".format(e))
                self.send_error(500, str(e))
            
        elif self.path == '/frame':
            # Synchronized RGB + Depth + Head Angles
            frame = get_synchronized_frame()
            self.send_json(frame)
            
        elif self.path == '/rgb':
            # RGB only
            rgb = get_rgb_frame()
            self.send_json({"rgb": rgb, "timestamp": time.time()})
            
        elif self.path == '/depth':
            # Depth only
            depth = get_depth_frame()
            self.send_json({"depth": depth, "timestamp": time.time()})
            
        elif self.path == '/head':
            # Head angles only
            head = get_head_angles()
            self.send_json({"head": head, "timestamp": time.time()})
            
        elif self.path == '/status':
            self.send_json({
                "status": "running",
                "rgb_subscriber": rgb_subscriber,
                "depth_subscriber": depth_subscriber,
                "rgb_resolution": RGB_RESOLUTION,
                "rgb_resolution_name": RESOLUTION_NAMES.get(RGB_RESOLUTION, "unknown"),
                "rgb_colorspace": "BGR",
                "timestamp": time.time(),
                "offsets": CAM_OFFSETS
            })
            
        elif self.path.startswith('/set_resolution'):
            # Parse resolution from query string: /set_resolution?res=1
            res = 1  # Default
            if '?' in self.path:
                query = self.path.split('?')[1]
                for param in query.split('&'):
                    if param.startswith('res='):
                        try:
                            res = int(param.split('=')[1])
                        except:
                            pass
            
            if set_camera_resolution(res):
                self.send_json({
                    "status": "ok",
                    "resolution": res,
                    "resolution_name": RESOLUTION_NAMES.get(res, "unknown")
                })
            else:
                self.send_json({"error": "Failed to set resolution"}, 500)
            
        elif self.path == '/shutdown':
            running = False
            self.send_json({"status": "shutting_down"})
            
        else:
            self.send_json({"error": "Unknown endpoint"}, 404)


def run_server():
    """Run the HTTP server."""
    global running
    
    server = HTTPServer(('127.0.0.1', PORT), CameraHandler)
    server.timeout = 1.0  # Allow checking running flag
    
    print("[Daemon] Camera daemon running on port {}".format(PORT))
    print("[Daemon] Endpoints: /frame_bin, /frame, /status, /set_resolution?res=N, /shutdown")
    
    while running:
        server.handle_request()
    
    print("[Daemon] Server stopped")


def main():
    global running
    
    print("[Daemon] Starting Camera Daemon...")
    
    try:
        if not init_naoqi():
            print("[Daemon] Failed to initialize NAOqi")
            sys.exit(1)
        
        run_server()
        
    except KeyboardInterrupt:
        print("\n[Daemon] Interrupted")
    except Exception as e:
        print("[Daemon] Error: {}".format(e))
    finally:
        cleanup_naoqi()
        print("[Daemon] Daemon stopped")


if __name__ == "__main__":
    main()
