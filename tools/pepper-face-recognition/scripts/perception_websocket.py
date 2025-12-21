#!/usr/bin/env python3
"""
WebSocket Server for real-time perception streaming.
Runs alongside the HTTP server on port 5002.

Features:
- Real-time streaming of tracked people data
- Configurable settings (recognition threshold, tracker tolerance, etc.)
- Face management (register, delete, list)
- Bidirectional communication for all perception operations

Message Types (Client → Server):
- get_settings: Request current settings
- set_settings: Update settings {data: {...}}
- register_face: Register new face {data: {name: "..."}}
- delete_face: Delete face {data: {name: "..."}}
- list_faces: Request list of known faces
- ping: Keep-alive

Message Types (Server → Client):
- settings: Current settings {data: {...}}
- people: Tracked people update {data: {...}}
- faces: List of known faces {data: [...]}
- face_registered: Registration result {data: {success, name, error?}}
- face_deleted: Deletion result {data: {success, name, error?}}
- pong: Keep-alive response
- error: Error message {data: {message: "..."}}
"""

import asyncio
import json
import time
import threading
from dataclasses import dataclass, asdict
from typing import Set, Optional

# Add custom packages path
import sys
import os
sys.path.insert(0, '/home/nao/python_packages')

import websockets

# Will be set by the main server
_tracking_callback = None
_get_settings_callback = None
_set_settings_callback = None
_register_face_callback = None
_delete_face_callback = None
_list_faces_callback = None

# Connected WebSocket clients
CLIENTS: Set[websockets.WebSocketServerProtocol] = set()
CLIENTS_LOCK = threading.Lock()

# Event loop reference for cross-thread broadcasting
_ws_event_loop = None

# Current settings (shared with main server)
@dataclass
class PerceptionSettings:
    # Tracker settings
    max_angle_distance: float = 15.0      # degrees - how far a face can move between frames
    track_timeout_ms: int = 3000          # ms - when to remove lost tracks
    min_track_age_ms: int = 300           # ms - minimum age before track is reported
    
    # Recognition settings  
    recognition_threshold: float = 0.65   # cosine distance threshold (lower = stricter)
    recognition_cooldown_ms: int = 3000   # ms - time between recognition attempts
    
    # Gaze detection settings
    gaze_center_tolerance: float = 0.15   # how much off-center is still "looking at robot"
    
    # Streaming settings
    update_interval_ms: int = 150         # ms - target update interval (was 700, lowered for smoother tracking)
    
    # Camera settings
    # Resolution: 0=QQVGA(160x120), 1=QVGA(320x240), 2=VGA(640x480)
    camera_resolution: int = 1            # Default: QVGA (320x240)
    
    def to_dict(self):
        return asdict(self)
    
    @classmethod
    def from_dict(cls, data: dict):
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


CURRENT_SETTINGS = PerceptionSettings()
SETTINGS_LOCK = threading.Lock()


def get_settings() -> PerceptionSettings:
    """Get current settings (thread-safe)."""
    with SETTINGS_LOCK:
        return CURRENT_SETTINGS


def update_settings(new_settings: dict) -> PerceptionSettings:
    """Update settings and return the new state (thread-safe)."""
    global CURRENT_SETTINGS
    with SETTINGS_LOCK:
        for key, value in new_settings.items():
            if hasattr(CURRENT_SETTINGS, key):
                setattr(CURRENT_SETTINGS, key, value)
        return CURRENT_SETTINGS


async def broadcast_message(message: dict):
    """Send message to all connected clients."""
    if not CLIENTS:
        return
    
    msg_str = json.dumps(message)
    with CLIENTS_LOCK:
        clients = list(CLIENTS)
    
    if clients:
        await asyncio.gather(
            *[client.send(msg_str) for client in clients],
            return_exceptions=True
        )


async def broadcast_people_update(people_data: dict):
    """Broadcast people update to all clients."""
    await broadcast_message({
        "type": "people",
        "data": people_data,
        "timestamp": int(time.time() * 1000)
    })


async def handle_client(websocket: websockets.WebSocketServerProtocol, path: str):
    """Handle a single WebSocket client connection."""
    client_id = id(websocket)
    print(f"[WS] Client {client_id} connected from {websocket.remote_address}")
    
    with CLIENTS_LOCK:
        CLIENTS.add(websocket)
    
    try:
        # Send current settings on connect
        settings = get_settings()
        await websocket.send(json.dumps({
            "type": "settings",
            "data": settings.to_dict()
        }))
        
        # Handle incoming messages
        async for message in websocket:
            try:
                data = json.loads(message)
                msg_type = data.get("type")
                
                if msg_type == "get_settings":
                    # Client requests current settings
                    settings = get_settings()
                    await websocket.send(json.dumps({
                        "type": "settings",
                        "data": settings.to_dict()
                    }))
                    
                elif msg_type == "set_settings":
                    # Client updates settings
                    new_values = data.get("data", {})
                    settings = update_settings(new_values)
                    print(f"[WS] Settings updated: {new_values}")
                    
                    # Notify the main server about settings change
                    if _set_settings_callback:
                        _set_settings_callback(settings)
                    
                    # Broadcast new settings to all clients
                    await broadcast_message({
                        "type": "settings",
                        "data": settings.to_dict()
                    })
                    
                elif msg_type == "register_face":
                    # Register a new face
                    name = data.get("data", {}).get("name", "")
                    if not name:
                        await websocket.send(json.dumps({
                            "type": "face_registered",
                            "data": {"success": False, "name": "", "error": "Name is required"}
                        }))
                    elif _register_face_callback:
                        success, error = _register_face_callback(name)
                        await websocket.send(json.dumps({
                            "type": "face_registered",
                            "data": {"success": success, "name": name, "error": error}
                        }))
                        # Broadcast updated face list to all clients
                        if success and _list_faces_callback:
                            faces = _list_faces_callback()
                            await broadcast_message({
                                "type": "faces",
                                "data": faces
                            })
                    else:
                        await websocket.send(json.dumps({
                            "type": "face_registered",
                            "data": {"success": False, "name": name, "error": "Not available"}
                        }))
                        
                elif msg_type == "delete_face":
                    # Delete a face
                    name = data.get("data", {}).get("name", "")
                    if not name:
                        await websocket.send(json.dumps({
                            "type": "face_deleted",
                            "data": {"success": False, "name": "", "error": "Name is required"}
                        }))
                    elif _delete_face_callback:
                        success, error = _delete_face_callback(name)
                        await websocket.send(json.dumps({
                            "type": "face_deleted",
                            "data": {"success": success, "name": name, "error": error}
                        }))
                        # Broadcast updated face list to all clients
                        if success and _list_faces_callback:
                            faces = _list_faces_callback()
                            await broadcast_message({
                                "type": "faces",
                                "data": faces
                            })
                    else:
                        await websocket.send(json.dumps({
                            "type": "face_deleted",
                            "data": {"success": False, "name": name, "error": "Not available"}
                        }))
                        
                elif msg_type == "list_faces":
                    # List all known faces
                    if _list_faces_callback:
                        faces = _list_faces_callback()
                        await websocket.send(json.dumps({
                            "type": "faces",
                            "data": faces
                        }))
                    else:
                        await websocket.send(json.dumps({
                            "type": "faces",
                            "data": []
                        }))
                    
                elif msg_type == "ping":
                    # Keep-alive ping
                    await websocket.send(json.dumps({"type": "pong"}))
                    
                else:
                    print(f"[WS] Unknown message type: {msg_type}")
                    await websocket.send(json.dumps({
                        "type": "error",
                        "data": {"message": f"Unknown message type: {msg_type}"}
                    }))
                    
            except json.JSONDecodeError:
                print(f"[WS] Invalid JSON from client {client_id}")
            except Exception as e:
                print(f"[WS] Error handling message: {e}")
                
    except websockets.exceptions.ConnectionClosed:
        print(f"[WS] Client {client_id} disconnected")
    finally:
        with CLIENTS_LOCK:
            CLIENTS.discard(websocket)


def run_websocket_server(host: str = "0.0.0.0", port: int = 5002):
    """Run the WebSocket server (blocking)."""
    global _ws_event_loop
    
    print(f"[WS] Starting WebSocket server on port {port}...")
    
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _ws_event_loop = loop  # Save reference for cross-thread broadcasting
    
    # Use reuse_address to avoid "Address already in use" errors
    start_server = websockets.serve(
        handle_client, 
        host, 
        port,
        reuse_address=True
    )
    
    loop.run_until_complete(start_server)
    print(f"[WS] WebSocket server running on ws://{host}:{port}")
    loop.run_forever()


def start_websocket_server_thread(host: str = "0.0.0.0", port: int = 5002):
    """Start WebSocket server in a background thread."""
    thread = threading.Thread(
        target=run_websocket_server,
        args=(host, port),
        daemon=True
    )
    thread.start()
    return thread


# Callback setters for integration with main server
def set_settings_callback(callback):
    """Set callback for when settings are changed via WebSocket."""
    global _set_settings_callback
    _set_settings_callback = callback


def set_face_callbacks(register_cb, delete_cb, list_cb):
    """Set callbacks for face management operations."""
    global _register_face_callback, _delete_face_callback, _list_faces_callback
    _register_face_callback = register_cb
    _delete_face_callback = delete_cb
    _list_faces_callback = list_cb


def get_client_count() -> int:
    """Get number of connected WebSocket clients."""
    with CLIENTS_LOCK:
        return len(CLIENTS)


# For broadcasting from the main server
def schedule_broadcast(people_data: dict):
    """Schedule a broadcast from outside the asyncio loop (thread-safe)."""
    global _ws_event_loop
    
    if _ws_event_loop is None:
        return
    
    if not CLIENTS:
        return
        
    try:
        asyncio.run_coroutine_threadsafe(
            broadcast_people_update(people_data),
            _ws_event_loop
        )
    except Exception as e:
        print(f"[WS] Broadcast error: {e}")


if __name__ == "__main__":
    # Test standalone
    print("Starting WebSocket server standalone (for testing)...")
    run_websocket_server()

