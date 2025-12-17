#!/usr/bin/env python3
"""
Face Tracker for Head-Based Perception System.
Tracks faces using world coordinates (relative to robot torso).

Features:
- Centroid-based tracking using world angles
- Stable track IDs across frames
- Distance measurement from depth camera
- Intelligent recognition triggers
"""

import time
import math
import base64
import struct
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# Pepper Top Camera specs
CAMERA_HFOV_DEG = 57.2  # Horizontal field of view in degrees
CAMERA_VFOV_DEG = 44.3  # Vertical field of view in degrees

# Gaze detection threshold (default, can be overridden per-tracker)
DEFAULT_GAZE_THRESHOLD = 0.35  # Head yaw ratio threshold for "looking at robot"


def calculate_head_direction(raw_face, gaze_threshold: float = DEFAULT_GAZE_THRESHOLD) -> Tuple[float, bool]:
    """
    Calculate head direction from YuNet landmarks.
    
    YuNet provides 5 landmarks in the face array:
    - face[4], face[5]: right eye (x, y)
    - face[6], face[7]: left eye (x, y)  
    - face[8], face[9]: nose tip (x, y)
    - face[10], face[11]: right mouth corner (x, y)
    - face[12], face[13]: left mouth corner (x, y)
    
    Returns:
        (head_yaw_ratio, looking_at_robot)
        - head_yaw_ratio: -1 (looking right) to +1 (looking left), 0 = center
        - looking_at_robot: True if person is looking at robot
    """
    if raw_face is None or len(raw_face) < 14:
        return 0.0, False
    
    try:
        # Extract landmarks
        right_eye_x = float(raw_face[4])
        left_eye_x = float(raw_face[6])
        nose_x = float(raw_face[8])
        
        # Calculate eye center and face width
        eye_center_x = (right_eye_x + left_eye_x) / 2
        face_width = left_eye_x - right_eye_x
        
        if face_width <= 0:
            return 0.0, False
        
        # Calculate nose offset from eye center
        # Positive = nose is to the right of eye center = looking left
        # Negative = nose is to the left of eye center = looking right
        nose_offset = nose_x - eye_center_x
        
        # Normalize to [-1, 1] range
        # Divide by half face width for normalization
        head_yaw_ratio = nose_offset / (face_width / 2)
        
        # Clamp to [-1, 1]
        head_yaw_ratio = max(-1.0, min(1.0, head_yaw_ratio))
        
        # Determine if looking at robot
        looking_at_robot = abs(head_yaw_ratio) < gaze_threshold
        
        return head_yaw_ratio, looking_at_robot
        
    except (IndexError, TypeError, ValueError):
        return 0.0, False


@dataclass
class TrackedPerson:
    """Represents a tracked person."""
    track_id: int
    name: str = "Unknown"
    confidence: float = 0.0
    world_yaw: float = 0.0      # Degrees relative to torso
    world_pitch: float = 0.0    # Degrees relative to torso
    distance: float = -1.0      # Meters (-1 if unknown)
    bbox: Dict = field(default_factory=dict)  # Bounding box in image
    raw_face: any = None        # Raw detector output with landmarks for recognition
    looking_at_robot: bool = False  # Is person looking at the robot?
    head_yaw_ratio: float = 0.0     # Head direction: -1=right, 0=center, +1=left
    last_seen_ms: int = 0
    last_recognition_ms: int = 0
    recognition_attempts: int = 0


class FaceTracker:
    """
    Tracks faces across frames using world coordinates.
    
    World coordinates are calculated from:
    - Head angles (yaw, pitch) from robot sensors
    - Face position in camera image
    - Camera field of view
    """
    
    def __init__(self,
                 max_angle_distance: float = 15.0,  # Max degrees to consider same person
                 track_timeout_ms: int = 3000,       # Remove track after this time
                 recognition_cooldown_ms: int = 3000, # Min time between recognition attempts
                 gaze_threshold: float = DEFAULT_GAZE_THRESHOLD  # Gaze detection threshold
                 ):
        self.max_angle_distance = max_angle_distance
        self.track_timeout_ms = track_timeout_ms
        self.recognition_cooldown_ms = recognition_cooldown_ms
        self.gaze_threshold = gaze_threshold
        
        self.tracks: Dict[int, TrackedPerson] = {}
        self.next_track_id = 1
        
        # For recognition scheduling
        self.pending_recognition: List[int] = []  # Track IDs needing recognition
    
    def _pixel_to_angle(self, 
                        pixel_x: float, 
                        pixel_y: float,
                        image_width: int,
                        image_height: int) -> Tuple[float, float]:
        """
        Convert pixel position to angle offset from image center.
        
        Returns (yaw_offset, pitch_offset) in degrees.
        Positive yaw = left in image = left of robot
        Positive pitch = up in image = above robot eye level
        """
        # Normalize to [-0.5, 0.5]
        norm_x = (pixel_x / image_width) - 0.5
        norm_y = (pixel_y / image_height) - 0.5
        
        # Convert to angle offset
        # Note: Image X increases to right, but robot yaw increases to left
        # So we negate the X offset
        yaw_offset = -norm_x * CAMERA_HFOV_DEG
        pitch_offset = -norm_y * CAMERA_VFOV_DEG  # Image Y increases downward
        
        return yaw_offset, pitch_offset
    
    def _calculate_world_angles(self,
                                face_center_x: float,
                                face_center_y: float,
                                image_width: int,
                                image_height: int,
                                head_yaw_deg: float,
                                head_pitch_deg: float) -> Tuple[float, float]:
        """
        Calculate world angles (relative to torso) for a face.
        
        Returns (world_yaw, world_pitch) in degrees.
        """
        yaw_offset, pitch_offset = self._pixel_to_angle(
            face_center_x, face_center_y, image_width, image_height
        )
        
        world_yaw = head_yaw_deg + yaw_offset
        world_pitch = head_pitch_deg + pitch_offset
        
        return world_yaw, world_pitch
    
    def _angle_distance(self, 
                        yaw1: float, pitch1: float,
                        yaw2: float, pitch2: float) -> float:
        """Calculate angular distance between two positions (simplified)."""
        # Simple Euclidean distance in angle space
        return math.sqrt((yaw1 - yaw2)**2 + (pitch1 - pitch2)**2)
    
    def _find_closest_track(self, 
                           world_yaw: float, 
                           world_pitch: float) -> Optional[int]:
        """
        Find the closest existing track to the given position.
        Returns track_id or None if no close track found.
        """
        now_ms = int(time.time() * 1000)
        best_track_id = None
        best_distance = float('inf')
        
        for track_id, track in self.tracks.items():
            # Skip tracks that have timed out
            if now_ms - track.last_seen_ms > self.track_timeout_ms:
                continue
            
            distance = self._angle_distance(
                world_yaw, world_pitch,
                track.world_yaw, track.world_pitch
            )
            
            if distance < best_distance and distance < self.max_angle_distance:
                best_distance = distance
                best_track_id = track_id
        
        return best_track_id
    
    def _get_depth_at_point(self,
                           depth_data: bytes,
                           depth_width: int,
                           depth_height: int,
                           face_center_x: float,
                           face_center_y: float,
                           image_width: int,
                           image_height: int) -> float:
        """
        Get depth value at a point, mapping from RGB to depth coordinates.
        Returns distance in meters, or -1 if invalid.
        """
        if not depth_data or depth_width <= 0 or depth_height <= 0:
            return -1.0
        
        # Map RGB coordinates to depth coordinates
        # (They may have different resolutions)
        depth_x = int(face_center_x * depth_width / image_width)
        depth_y = int(face_center_y * depth_height / image_height)
        
        # Clamp to valid range
        depth_x = max(0, min(depth_x, depth_width - 1))
        depth_y = max(0, min(depth_y, depth_height - 1))
        
        # Calculate byte offset (16-bit depth = 2 bytes per pixel)
        offset = (depth_y * depth_width + depth_x) * 2
        
        if offset + 2 > len(depth_data):
            return -1.0
        
        # Read 16-bit depth value (little-endian, millimeters)
        try:
            depth_mm = struct.unpack('<H', depth_data[offset:offset+2])[0]
            
            # Convert to meters, filter invalid values
            if depth_mm == 0 or depth_mm > 10000:  # 0 or >10m is invalid
                return -1.0
            
            return depth_mm / 1000.0
        except:
            return -1.0
    
    def update(self,
               faces: List[Dict],          # From face detection
               image_width: int,
               image_height: int,
               head_yaw_deg: float,
               head_pitch_deg: float,
               depth_data: Optional[bytes] = None,
               depth_width: int = 0,
               depth_height: int = 0) -> List[TrackedPerson]:
        """
        Update tracks with new face detections.
        
        Args:
            faces: List of detected faces with 'location' and 'raw_face' (detector output)
            image_width, image_height: RGB image dimensions
            head_yaw_deg, head_pitch_deg: Current head angles in degrees
            depth_data: Raw depth image bytes (16-bit per pixel)
            depth_width, depth_height: Depth image dimensions
        
        Returns:
            List of currently tracked people
        """
        now_ms = int(time.time() * 1000)
        matched_track_ids = set()
        
        # Process each detected face
        for face in faces:
            loc = face.get('location', {})
            left = loc.get('left', 0)
            top = loc.get('top', 0)
            right = loc.get('right', 0)
            bottom = loc.get('bottom', 0)
            raw_face = face.get('raw_face')  # Raw detector output with landmarks
            
            # Calculate face center
            face_center_x = (left + right) / 2
            face_center_y = (top + bottom) / 2
            
            # Calculate world angles
            world_yaw, world_pitch = self._calculate_world_angles(
                face_center_x, face_center_y,
                image_width, image_height,
                head_yaw_deg, head_pitch_deg
            )
            
            # Get depth/distance
            distance = -1.0
            if depth_data:
                distance = self._get_depth_at_point(
                    depth_data, depth_width, depth_height,
                    face_center_x, face_center_y,
                    image_width, image_height
                )
            
            # Find matching track or create new one
            track_id = self._find_closest_track(world_yaw, world_pitch)
            
            # Calculate head direction (looking at robot?)
            head_yaw_ratio, looking_at_robot = calculate_head_direction(raw_face, self.gaze_threshold)
            
            if track_id is None:
                # New track
                track_id = self.next_track_id
                self.next_track_id += 1
                
                self.tracks[track_id] = TrackedPerson(
                    track_id=track_id,
                    world_yaw=world_yaw,
                    world_pitch=world_pitch,
                    distance=distance,
                    bbox={'x': left, 'y': top, 'w': right-left, 'h': bottom-top},
                    raw_face=raw_face,
                    looking_at_robot=looking_at_robot,
                    head_yaw_ratio=head_yaw_ratio,
                    last_seen_ms=now_ms
                )
                
                # Schedule recognition for new track
                self.pending_recognition.append(track_id)
                gaze = "LOOKING" if looking_at_robot else "AWAY"
                print(f"[Tracker] New track {track_id} at yaw={world_yaw:.1f}°, pitch={world_pitch:.1f}° ({gaze})")
            else:
                # Update existing track
                track = self.tracks[track_id]
                track.world_yaw = world_yaw
                track.world_pitch = world_pitch
                if distance > 0:
                    track.distance = distance
                track.bbox = {'x': left, 'y': top, 'w': right-left, 'h': bottom-top}
                track.raw_face = raw_face  # Update raw_face for recognition
                track.looking_at_robot = looking_at_robot
                track.head_yaw_ratio = head_yaw_ratio
                track.last_seen_ms = now_ms
            
            matched_track_ids.add(track_id)
        
        # Remove stale tracks
        stale_ids = []
        for track_id, track in self.tracks.items():
            if now_ms - track.last_seen_ms > self.track_timeout_ms:
                stale_ids.append(track_id)
        
        for track_id in stale_ids:
            print(f"[Tracker] Removing stale track {track_id}")
            del self.tracks[track_id]
            if track_id in self.pending_recognition:
                self.pending_recognition.remove(track_id)
        
        return list(self.tracks.values())
    
    def get_tracks_needing_recognition(self) -> List[int]:
        """
        Get track IDs that need face recognition.
        
        Recognition is needed when:
        1. Track is new (in pending_recognition)
        2. Track is still "Unknown" and cooldown has passed
        """
        now_ms = int(time.time() * 1000)
        result = []
        
        # New tracks always need recognition
        for track_id in self.pending_recognition:
            if track_id in self.tracks:
                result.append(track_id)
        
        # Unknown tracks need periodic recognition attempts
        for track_id, track in self.tracks.items():
            if track.name == "Unknown":
                time_since_last = now_ms - track.last_recognition_ms
                if time_since_last >= self.recognition_cooldown_ms:
                    if track_id not in result:
                        result.append(track_id)
        
        return result
    
    def mark_recognition_pending(self, track_id: int):
        """Mark a track as having recognition in progress (avoid re-queueing)."""
        if track_id in self.tracks:
            # Update last_recognition_ms to prevent re-queueing
            self.tracks[track_id].last_recognition_ms = int(time.time() * 1000)
            # Remove from pending list
            if track_id in self.pending_recognition:
                self.pending_recognition.remove(track_id)
    
    def set_recognition_result(self, track_id: int, name: str, confidence: float):
        """Update a track with recognition result."""
        if track_id not in self.tracks:
            return
        
        now_ms = int(time.time() * 1000)
        track = self.tracks[track_id]
        track.name = name
        track.confidence = confidence
        track.last_recognition_ms = now_ms
        track.recognition_attempts += 1
        
        # Remove from pending if it was there
        if track_id in self.pending_recognition:
            self.pending_recognition.remove(track_id)
        
        print(f"[Tracker] Track {track_id} recognized as '{name}' (conf={confidence:.2f})")
    
    def get_track_bbox(self, track_id: int) -> Optional[Dict]:
        """Get bounding box for a track (for recognition cropping)."""
        if track_id not in self.tracks:
            return None
        return self.tracks[track_id].bbox
    
    def get_track_raw_face(self, track_id: int):
        """Get raw detector output for a track (for recognition with landmarks)."""
        if track_id not in self.tracks:
            return None
        return self.tracks[track_id].raw_face
    
    def to_dict(self) -> List[Dict]:
        """Convert all tracks to dictionary format for API response."""
        return [
            {
                'track_id': t.track_id,
                'name': t.name,
                'confidence': t.confidence,
                'world_yaw': round(t.world_yaw, 2),
                'world_pitch': round(t.world_pitch, 2),
                'distance': round(t.distance, 2) if t.distance > 0 else -1,
                'looking_at_robot': t.looking_at_robot,
                'head_direction': round(t.head_yaw_ratio, 2),  # -1=right, 0=center, +1=left
                'bbox': t.bbox,
                'last_seen_ms': t.last_seen_ms
            }
            for t in self.tracks.values()
        ]


