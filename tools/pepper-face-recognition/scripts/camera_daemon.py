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
import mmap
import os
import struct
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

# Shared Memory settings
SHM_PATH = "/dev/shm/pepper_shm"
SHM_SIZE = 4 * 1024 * 1024  # 4MB

class SharedMemoryManager(object):
    def __init__(self):
        self.f = None
        self.m = None
        self.seq_id = 0
        self.setup()
        
    def setup(self):
        try:
            # Open file for SHM
            self.f = open(SHM_PATH, "w+b")
            # Resize to SHM_SIZE
            self.f.seek(SHM_SIZE - 1)
            self.f.write(b'\0')
            self.f.flush()
            # Map memory
            self.m = mmap.mmap(self.f.fileno(), SHM_SIZE)
            print("[Daemon] Shared Memory initialized at {}".format(SHM_PATH))
        except Exception as e:
            print("[Daemon] Failed to init SHM: {}".format(e))
            
    def write(self, head, rgb):
        if not self.m:
            return
            
        try:
            # Prepare data
            head_json = json.dumps(head) if head else "{}"
            head_bytes = head_json.encode('utf-8')
            
            rgb_bytes = rgb['data'] if rgb else b""
            rgb_w = rgb['width'] if rgb else 0
            rgb_h = rgb['height'] if rgb else 0
            rgb_len = len(rgb_bytes)
            
            head_len = len(head_bytes)
            
            # Check size
            total_size = 64 + head_len + rgb_len
            if total_size > SHM_SIZE:
                print("[Daemon] SHM Overflow! Needed {} bytes".format(total_size))
                return

            self.seq_id += 1
            timestamp = time.time()
            
            # Write Header (64 bytes fixed)
            # Magic(4), Seq(4), Ts(8), RGB_W(4), RGB_H(4), RGB_L(4), Head_L(4)
            # Format: < (little endian), 4s (4 chars), I (uint), d (double), 4 * I (uint)
            header = struct.pack("<4sIdIIII", 
                b"PSHM", self.seq_id, timestamp,
                rgb_w, rgb_h, rgb_len,
                head_len
            )
            
            # Write everything in one go (seek 0)
            self.m.seek(0)
            self.m.write(header)
            # Padding to 64 bytes
            self.m.seek(64)
            self.m.write(head_bytes)
            self.m.write(rgb_bytes)
            
        except Exception as e:
            print("[Daemon] SHM Write Error: {}".format(e))
            try:
                self.m.close()
                self.f.close()
                self.setup()
            except:
                pass

    def cleanup(self):
        if self.m:
            self.m.close()
        if self.f:
            self.f.close()
        try:
            os.remove(SHM_PATH)
        except:
            pass

shm_manager = SharedMemoryManager()

# Top Camera Z: 0.1631m, Depth Camera Z: 0.1194m -> Delta Z = 0.0437m
# Top Camera X: 0.0868m, Depth Camera X: 0.05138m -> Delta X = 0.03542m
CAM_OFFSETS = {
    "dx": 0.03542,
    "dy": 0.00000,
    "dz": 0.04370
}

# Background Polling
class PollingThread(threading.Thread):
    def __init__(self):
        threading.Thread.__init__(self)
        self.daemon = True
        self.last_frame = None
        
    def run(self):
        print("[Daemon] Polling thread started")
        while running:
            try:
                # Fetch synchronized frame
                frame_data = get_synchronized_frame_internal()
                
                if frame_data:
                    self.last_frame = frame_data
                    
                    # Push to Shared Memory
                    shm_manager.write(
                        frame_data['head'],
                        frame_data['rgb']
                    )
                
                # Sleep a tiny bit to prevent CPU hogging
                time.sleep(0.01)
                
            except Exception as e:
                print("[Daemon] Polling error: {}".format(e))
                time.sleep(1.0)

polling_thread = None

def init_naoqi():
    """Initialize NAOqi connection and subscribe to cameras."""
    global session, video, memory, rgb_subscriber, polling_thread
    
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
    
    # Start polling thread
    polling_thread = PollingThread()
    polling_thread.start()
    
    return True



def cleanup_naoqi():
    """Unsubscribe from cameras and cleanup."""
    global video, rgb_subscriber
    
    if video:
        try:
            if rgb_subscriber:
                video.unsubscribe(rgb_subscriber)
                print("[Daemon] RGB camera unsubscribed")
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
    """Deprecated: Depth removed for performance."""
    return None


def fetch_frame_threaded(func, result_container, key, raw_data):
    """Helper to run frame fetch in thread."""
    try:
        res = func(raw_data=raw_data)
        result_container[key] = res
    except Exception as e:
        print("[Daemon] Thread error fetching {}: {}".format(key, e))
        result_container[key] = None


def get_synchronized_frame_internal():
    """
    Internal version: Get synchronized RGB + Head Angles.
    """
    with lock:
        start = time.time()
        
        # Fetch RGB
        # No need for threads if we only fetch one image
        rgb = get_rgb_frame(raw_data=True)
        head = get_head_angles()
        
        capture_time = (time.time() - start) * 1000  # ms
        
        return {
            "head": head,
            "rgb": rgb,
            "capture_ms": capture_time,
            "timestamp": time.time()
        }


def get_synchronized_frame():
    """
    Public API: Returns the latest cached frame from PollingThread if available.
    """
    if polling_thread and polling_thread.last_frame:
        return polling_thread.last_frame
    
    # Fallback to direct fetch if thread not ready
    return get_synchronized_frame_internal()


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
                # Use internal sync logic
                frame = get_synchronized_frame()
                
                head = frame.get('head')
                rgb = frame.get('rgb')
                
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
                
                # Pack header (No depth)
                # Format: <4sIHHII (Magic, HeadL, RGBW, RGBH, RGBL, Reserved=0) - Keep 24 bytes?
                # Original was <4sIHHIHHI (24 bytes).
                # To maintain compatibility with unknown clients, we could send 0s for depth.
                # But this endpoint is only used by our server.
                # Let's match new SHM struct-like format or just keep simple binary.
                
                # We'll use a simpler binary format here matching what the new Server expects via HTTP fallback
                # Magic(4) + HeadL(4) + RGBW(2) + RGBH(2) + RGBL(4) = 16 Bytes
                # NOTE: The server HTTP fallback must match this!
                header = struct.pack("<4sIHHI", "PFR2", head_len, rgb_w, rgb_h, rgb_len)
                
                # Send everything as one binary blob
                self.send_binary(header + head_json + rgb_data)
                
            except Exception as e:
                print("[Daemon] Binary endpoint error: {}".format(e))
                self.send_error(500, str(e))
            
        elif self.path == '/frame':
            # Synchronized RGB + Head Angles
            frame = get_synchronized_frame()
            # frame['rgb']['data'] is raw bytes, we need base64 for JSON
            if frame.get('rgb'):
                frame['rgb']['data'] = base64.b64encode(frame['rgb']['data'])
            self.send_json(frame)
            
        elif self.path == '/rgb':
            # RGB only
            rgb = get_rgb_frame()
            self.send_json({"rgb": rgb, "timestamp": time.time()})
            
        elif self.path == '/depth':
            # Depth removed
            self.send_json({"error": "Depth disabled"}, 404)
            
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
        shm_manager.cleanup()
        cleanup_naoqi()
        print("[Daemon] Daemon stopped")


if __name__ == "__main__":
    main()
