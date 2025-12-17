#!/usr/bin/env python2
# -*- coding: utf-8 -*-
"""
Persistent Camera Daemon for Head-Based Perception System.

Keeps both RGB and Depth cameras subscribed permanently.
Provides synchronized frames via HTTP endpoint.

Performance optimizations:
- No subscribe/unsubscribe overhead per frame
- YUV422 native colorspace for RGB (fastest)
- Synchronized RGB + Depth + Head Angles
- Minimal latency (~30ms vs ~200ms)

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
import struct

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

# Cached latest frames (written by background capture threads)
rgb_cache_meta = None
rgb_cache_raw = ""
rgb_cache_ts = 0.0

depth_cache_meta = None
depth_cache_raw = ""
depth_cache_ts = 0.0

rgb_thread = None
depth_thread = None

# Camera settings
# RGB: Camera 0 (Top), Resolution 1 (QVGA 320x240), ColorSpace 11 (RGB), FPS 15
# Note: ColorSpace 11 = kRGBColorSpace (3 bytes/pixel) - same as working capture script
RGB_CAMERA = 0      # Top camera
RGB_RESOLUTION = 1  # QVGA (320x240)
RGB_COLORSPACE = 11  # kRGBColorSpace (3 bytes/pixel) - matches working capture
RGB_FPS = 15

# Depth: Camera 2, Resolution 1 (QQVGA 160x120), ColorSpace 17 (Depth), FPS 10
DEPTH_CAMERA = 2    # Depth camera
DEPTH_RESOLUTION = 1  # QQVGA (160x120 or 160x90)
DEPTH_COLORSPACE = 17  # Depth
DEPTH_FPS = 10


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


def _capture_rgb_loop():
    """Continuously capture RGB frames into cache (so HTTP handlers are cheap)."""
    global rgb_cache_meta, rgb_cache_raw, rgb_cache_ts
    interval = 1.0 / float(RGB_FPS) if RGB_FPS else 0.1
    while running:
        try:
            res = get_rgb_frame_raw()
            if res:
                meta, raw_bytes = res
                with lock:
                    rgb_cache_meta = meta
                    rgb_cache_raw = raw_bytes
                    rgb_cache_ts = time.time()
        except Exception as e:
            print("[Daemon] RGB capture loop error: {}".format(e))
        time.sleep(interval)


def _capture_depth_loop():
    """Continuously capture Depth frames into cache."""
    global depth_cache_meta, depth_cache_raw, depth_cache_ts
    interval = 1.0 / float(DEPTH_FPS) if DEPTH_FPS else 0.1
    while running:
        try:
            res = get_depth_frame_raw()
            if res:
                meta, raw_bytes = res
                with lock:
                    depth_cache_meta = meta
                    depth_cache_raw = raw_bytes
                    depth_cache_ts = time.time()
        except Exception as e:
            print("[Daemon] Depth capture loop error: {}".format(e))
        time.sleep(interval)


def start_capture_threads():
    """Start background capture threads."""
    global rgb_thread, depth_thread
    if rgb_thread is None or not rgb_thread.is_alive():
        rgb_thread = threading.Thread(target=_capture_rgb_loop)
        rgb_thread.daemon = True
        rgb_thread.start()
    if depth_thread is None or not depth_thread.is_alive():
        depth_thread = threading.Thread(target=_capture_depth_loop)
        depth_thread.daemon = True
        depth_thread.start()


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
            "head_pitch_rad": head_pitch_rad
        }
    except Exception as e:
        print("[Daemon] Head angles error: {}".format(e))
        return None


def get_rgb_frame_raw():
    """Get current RGB frame (raw bytes, no base64)."""
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
        raw_bytes = str(data) if data else ""
        
        meta = {
            "width": width,
            "height": height,
            "layers": layers,
            "colorspace": colorspace,  # 11 = RGB
            "timestamp": timestamp_s + timestamp_us / 1000000.0,
        }
        return (meta, raw_bytes)
    except Exception as e:
        print("[Daemon] RGB frame error: {}".format(e))
        return None


def get_rgb_frame_json():
    """Get RGB frame as JSON payload (base64-encoded data)."""
    res = get_rgb_frame_raw()
    if not res:
        return None
    meta, raw_bytes = res
    meta["data"] = base64.b64encode(raw_bytes) if raw_bytes else ""
    return meta


def get_depth_frame_raw():
    """Get current Depth frame (raw bytes, no base64)."""
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
        raw_bytes = str(data) if data else ""
        meta = {
            "width": width,
            "height": height,
            "timestamp": timestamp_s + timestamp_us / 1000000.0,
        }
        return (meta, raw_bytes)
    except Exception as e:
        print("[Daemon] Depth frame error: {}".format(e))
        return None


def get_depth_frame_json():
    """Get Depth frame as JSON payload (base64-encoded data)."""
    res = get_depth_frame_raw()
    if not res:
        return None
    meta, raw_bytes = res
    meta["data"] = base64.b64encode(raw_bytes) if raw_bytes else ""
    return meta


def get_synchronized_frame_json():
    """Get synchronized RGB + Depth + Head Angles (JSON/base64)."""
    # Use cached frames (fast). Head angles are read on-demand.
    start = time.time()
    head = get_head_angles()
    with lock:
        rgb_meta = rgb_cache_meta
        rgb_raw = rgb_cache_raw
        depth_meta = depth_cache_meta
        depth_raw = depth_cache_raw
    rgb = None
    depth = None
    if rgb_meta is not None:
        rgb = dict(rgb_meta)
        rgb["data"] = base64.b64encode(rgb_raw) if rgb_raw else ""
    if depth_meta is not None:
        depth = dict(depth_meta)
        depth["data"] = base64.b64encode(depth_raw) if depth_raw else ""
    capture_time = (time.time() - start) * 1000  # ms
    return {
        "head": head,
        "rgb": rgb,
        "depth": depth,
        "capture_ms": capture_time,
        "timestamp": time.time()
    }


def get_synchronized_frame_raw():
    """Get synchronized head + (rgb meta/raw) + (depth meta/raw) with no base64."""
    start = time.time()
    head = get_head_angles() or {}
    with lock:
        rgb_meta = rgb_cache_meta
        rgb_raw = rgb_cache_raw
        depth_meta = depth_cache_meta
        depth_raw = depth_cache_raw
    rgb_res = (rgb_meta, rgb_raw) if rgb_meta is not None else None
    depth_res = (depth_meta, depth_raw) if depth_meta is not None else None
    capture_time = (time.time() - start) * 1000  # ms
    return head, rgb_res, depth_res, capture_time


def build_frame_bin():
    """
    Build a binary payload for a synchronized frame.

    Format (little-endian):
      magic      : 4s   = b'PFR1'
      head_len   : I
      rgb_w      : H
      rgb_h      : H
      rgb_len    : I
      depth_w    : H
      depth_h    : H
      depth_len  : I
    Followed by:
      head_json bytes (utf-8)
      rgb bytes (raw RGB, colorspace=11, len=rgb_w*rgb_h*3)
      depth bytes (raw depth payload as provided by NAOqi)
    """
    head, rgb_res, depth_res, _capture_ms = get_synchronized_frame_raw()

    rgb_meta, rgb_raw = rgb_res if rgb_res else ({}, "")
    depth_meta, depth_raw = depth_res if depth_res else ({}, "")

    head_json = json.dumps(head or {})
    # Python2: json.dumps returns 'str' (bytes). Python3: returns 'str' (unicode).
    try:
        head_bytes = head_json if isinstance(head_json, str) else head_json.encode("utf-8")
    except Exception:
        head_bytes = head_json.encode("utf-8")

    rgb_w = int(rgb_meta.get("width") or 0)
    rgb_h = int(rgb_meta.get("height") or 0)

    depth_w = int(depth_meta.get("width") or 0)
    depth_h = int(depth_meta.get("height") or 0)

    header = struct.pack(
        "<4sIHHIHHI",
        "PFR1",
        len(head_bytes),
        rgb_w,
        rgb_h,
        len(rgb_raw),
        depth_w,
        depth_h,
        len(depth_raw),
    )
    return header + head_bytes + rgb_raw + depth_raw


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
    
    def do_GET(self):
        global running
        
        if self.path == '/frame':
            # Synchronized RGB + Depth + Head Angles
            frame = get_synchronized_frame_json()
            self.send_json(frame)
        
        elif self.path == '/frame_bin':
            # Synchronized frame as binary payload (fast path)
            payload = build_frame_bin()
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            
        elif self.path == '/rgb':
            # RGB only
            rgb = get_rgb_frame_json()
            self.send_json({"rgb": rgb, "timestamp": time.time()})
            
        elif self.path == '/depth':
            # Depth only
            depth = get_depth_frame_json()
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
                "rgb_colorspace": "RGB",
                "timestamp": time.time()
            })
            
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
    print("[Daemon] Endpoints: /frame, /frame_bin, /rgb, /depth, /head, /status, /shutdown")
    
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

        # Start background capture threads (major latency reduction for HTTP endpoints)
        start_capture_threads()
        
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

