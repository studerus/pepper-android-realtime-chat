#!/usr/bin/env python3
"""
Face Tracker for Head-Based Perception System.
Tracks faces using world coordinates (relative to robot torso).

Features:
- Kalman Filter for motion prediction (yaw, pitch, distance)
- 3D Matching (Angle + Depth) for robust ID persistence
- Parallax correction for depth/RGB alignment
- Smart Track Recovery for temporary occlusions
- Gaze Smoothing
"""

import time
import math
import struct
import numpy as np
import cv2
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# Pepper Top Camera specs
CAMERA_HFOV_DEG = 57.2
CAMERA_VFOV_DEG = 44.3

# Optical Distance Estimation (Pinhole Camera Model)
# Average human face width ~15cm
AVERAGE_FACE_WIDTH_M = 0.15
# Focal length in pixels for QVGA (320x240): f = (width/2) / tan(HFOV/2)
# f = 160 / tan(28.6°) ≈ 293 pixels
FOCAL_LENGTH_QVGA = 293.0

# Default Gaze Threshold
DEFAULT_GAZE_THRESHOLD = 0.35

# Weights for matching cost function
W_ANGLE = 1.0     # 1 degree diff = cost 1.0
W_DEPTH = 0.0     # DISABLED - depth sensor too unreliable, using optical estimation instead
W_SIG = 50.0      # heavy weight for signature mismatch (prevents ID swaps)
MATCH_THRESHOLD = 30.0  # Max cost to match of angle + depth (signature adds extra penalty)

# Simplified Tracker Constants
WORLD_POSITION_MATCH_M = 0.70  # 70cm threshold for world-position matching (accommodates sit/stand)
NEW_TRACK_CONFIRMATIONS = 3    # Require this many consecutive detections before creating track
LOST_BUFFER_MS = 2500          # Buffer lost tracks for 2.5 seconds

# Offsets (will be updated from daemon if available, else defaults)
DEFAULT_OFFSETS = {"dx": 0.035, "dy": 0.0, "dz": 0.044}

@dataclass
class FaceSignature:
    """Geometric signature of a face (invariant to scale/distance)."""
    r_eye_dist: float = 0.0          # Eye Dist / Face Width
    r_eye_nose: float = 0.0          # Eye Center to Nose / Face Height
    r_nose_mouth: float = 0.0        # Nose to Mouth / Face Height
    
    def distance_to(self, other: 'FaceSignature') -> float:
        return math.sqrt(
            (self.r_eye_dist - other.r_eye_dist)**2 +
            (self.r_eye_nose - other.r_eye_nose)**2 +
            (self.r_nose_mouth - other.r_nose_mouth)**2
        )

def calculate_signature(raw_face) -> Optional[FaceSignature]:
    """Compute geometric signature from YuNet landmarks."""
    if raw_face is None or len(raw_face) < 14:
        return None
        
    try:
        # Landmarks:
        # 4-5: Right Eye
        # 6-7: Left Eye
        # 8-9: Nose
        # 10-11: Right Mouth
        # 12-13: Left Mouth
        
        re_x, re_y = float(raw_face[4]), float(raw_face[5])
        le_x, le_y = float(raw_face[6]), float(raw_face[7])
        n_x, n_y = float(raw_face[8]), float(raw_face[9])
        rm_x, rm_y = float(raw_face[10]), float(raw_face[11])
        lm_x, lm_y = float(raw_face[12]), float(raw_face[13])
        
        # Dimensions
        eye_dist = math.sqrt((le_x - re_x)**2 + (le_y - re_y)**2)
        face_width = eye_dist * 2.5 # Approximate
        if face_width < 1: return None
        
        # Eye Center
        ec_x, ec_y = (re_x + le_x)/2, (re_y + le_y)/2
        
        # Mouth Center
        mc_x, mc_y = (rm_x + lm_x)/2, (rm_y + lm_y)/2
        
        # Height (Eye Center to Mouth Center approx)
        face_height = math.sqrt((mc_x - ec_x)**2 + (mc_y - ec_y)**2)
        if face_height < 1: return None
        
        # Ratios
        r1 = eye_dist / face_width
        
        dist_eye_nose = math.sqrt((n_x - ec_x)**2 + (n_y - ec_y)**2)
        r2 = dist_eye_nose / face_height
        
        dist_nose_mouth = math.sqrt((mc_x - n_x)**2 + (mc_y - n_y)**2)
        r3 = dist_nose_mouth / face_height
        
        return FaceSignature(r1, r2, r3)
        
    except Exception:
        return None

def calculate_head_direction(raw_face, gaze_threshold: float = DEFAULT_GAZE_THRESHOLD) -> Tuple[float, bool]:
    """Calculate head direction from YuNet landmarks."""
    if raw_face is None or len(raw_face) < 14:
        return 0.0, False
    
    try:
        right_eye_x = float(raw_face[4])
        left_eye_x = float(raw_face[6])
        nose_x = float(raw_face[8])
        
        eye_center_x = (right_eye_x + left_eye_x) / 2
        face_width = left_eye_x - right_eye_x
        
        if face_width <= 0:
            return 0.0, False
        
        nose_offset = nose_x - eye_center_x
        head_yaw_ratio = nose_offset / (face_width / 2)
        head_yaw_ratio = max(-1.0, min(1.0, head_yaw_ratio))
        
        looking_at_robot = abs(head_yaw_ratio) < gaze_threshold
        return head_yaw_ratio, looking_at_robot
        
    except Exception:
        return 0.0, False


@dataclass
class TrackedPerson:
    """Represents a tracked person with Kalman Filter state."""
    track_id: int
    name: str = "Unknown"
    confidence: float = 0.0
    
    # State
    world_yaw: float = 0.0
    world_pitch: float = 0.0
    distance: float = -1.0
    
    # Display
    bbox: Dict = field(default_factory=dict)
    raw_face: any = None
    signature: Optional[FaceSignature] = None
    
    # Gaze w/ Smoothing
    looking_at_robot: bool = False
    head_yaw_ratio: float = 0.0
    _gaze_smooth_val: float = 0.0
    looking_since_ms: int = 0  # Timestamp when gaze started (0 = not looking)
    
    # Meta
    last_seen_ms: int = 0
    first_seen_ms: int = 0
    last_recognition_ms: int = 0
    recognition_attempts: int = 0
    is_lost: bool = False
    
    # Kalman Filter
    # State: [yaw, pitch, distance, v_yaw, v_pitch, v_dist]
    kf: cv2.KalmanFilter = field(init=False, repr=False)
    
    def __post_init__(self):
        # Initialize Kalman Filter
        self.kf = cv2.KalmanFilter(6, 3) # 6 state vars, 3 measurements
        self.kf.transitionMatrix = np.eye(6, dtype=np.float32)
        # Prediction logic handles dt updates
        
        self.kf.measurementMatrix = np.array([
            [1, 0, 0, 0, 0, 0],
            [0, 1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0, 0]
        ], dtype=np.float32)
        
        # Process Noise Cov (Q) - Trust model prediction?
        self.kf.processNoiseCov = np.eye(6, dtype=np.float32) * 0.03
        
        # Measurement Noise Cov (R) - Trust measurements?
        self.kf.measurementNoiseCov = np.eye(3, dtype=np.float32) * 0.5
        
        # Initial state
        self.kf.statePost = np.array([
            [self.world_yaw],
            [self.world_pitch],
            [max(0.5, self.distance)],
            [0.0],
            [0.0],
            [0.0]
        ], dtype=np.float32)
        
        self._gaze_smooth_val = self.head_yaw_ratio

    def predict(self, dt_sec: float):
        """Predict next state."""
        # Update transition matrix with time delta
        if dt_sec > 0:
            self.kf.transitionMatrix[0, 3] = dt_sec
            self.kf.transitionMatrix[1, 4] = dt_sec
            self.kf.transitionMatrix[2, 5] = dt_sec
        
        prediction = self.kf.predict()
        self.world_yaw = float(prediction[0])
        self.world_pitch = float(prediction[1])
        # Only update distance if valid
        if prediction[2] > 0.3:
            self.distance = float(prediction[2])

    def correct(self, yaw, pitch, distance):
        """Correct state with measurement."""
        meas = np.array([[yaw], [pitch], [distance]], dtype=np.float32)
        self.kf.correct(meas)
        
        # Read back corrected state
        self.world_yaw = float(self.kf.statePost[0])
        self.world_pitch = float(self.kf.statePost[1])
        if self.kf.statePost[2] > 0.3:
            self.distance = float(self.kf.statePost[2])
            
    def update_gaze(self, new_ratio, threshold):
        """Apply EMA smoothing to gaze and track gaze duration."""
        alpha = 0.3
        self._gaze_smooth_val = (alpha * new_ratio) + ((1 - alpha) * self._gaze_smooth_val)
        self.head_yaw_ratio = self._gaze_smooth_val
        
        was_looking = self.looking_at_robot
        self.looking_at_robot = abs(self.head_yaw_ratio) < threshold
        
        # Track when gaze started
        now_ms = int(time.time() * 1000)
        if self.looking_at_robot and not was_looking:
            # Just started looking
            self.looking_since_ms = now_ms
        elif not self.looking_at_robot:
            # Stopped looking
            self.looking_since_ms = 0


class FaceTracker:
    def __init__(self,
                 max_angle_distance: float = 15.0,  # Legacy param (used for validation)
                 track_timeout_ms: int = 3000,
                 recognition_cooldown_ms: int = 3000,
                 gaze_threshold: float = DEFAULT_GAZE_THRESHOLD
                 ):
        self.max_angle_distance = max_angle_distance
        self.track_timeout_ms = track_timeout_ms
        self.recognition_cooldown_ms = recognition_cooldown_ms
        self.gaze_threshold = gaze_threshold
        
        self.tracks: Dict[int, TrackedPerson] = {}
        self.lost_tracks: List[TrackedPerson] = [] # Buffer for recovery
        self.pending_tracks: Dict[int, dict] = {}  # Unconfirmed detections {temp_id: {'det': det, 'count': n, 'first_ms': ms}}
        self.next_track_id = 1
        self.next_pending_id = 1
        self.pending_recognition: List[int] = []
        
        self.last_update_time = time.time()
        self.cam_offsets = DEFAULT_OFFSETS
    
    def set_camera_offsets(self, offsets):
        if offsets:
            self.cam_offsets = offsets

    def _pixel_to_angle(self, px, py, w, h):
        """Convert pixel to angle offset from center."""
        norm_x = (px / w) - 0.5
        norm_y = (py / h) - 0.5
        yaw = -norm_x * CAMERA_HFOV_DEG
        pitch = -norm_y * CAMERA_VFOV_DEG
        return yaw, pitch
    
    def _calculate_cost(self, track: TrackedPerson, det_yaw, det_pitch, det_dist, det_sig):
        """
        Calculate matching cost (lower is better).
        Combines Angular Distance, Depth Distance, and Geometric Signature.
        """
        # 1. Angular Distance
        d_yaw = track.world_yaw - det_yaw
        d_pitch = track.world_pitch - det_pitch
        angle_dist = math.sqrt(d_yaw**2 + d_pitch**2)
        
        # 2. Depth Distance
        depth_cost = 0.0
        if track.distance > 0 and det_dist > 0:
            depth_diff = abs(track.distance - det_dist)
            depth_cost = depth_diff * W_DEPTH
        else:
            # Penalty if depth is missing/mismatching state
            depth_cost = 2.0 # Equivalent to ~15cm diff
            
        # 3. Geometric Signature Penalty (Identity Check)
        sig_cost = 0.0
        if track.signature and det_sig:
            sig_dist = track.signature.distance_to(det_sig)
            # If signature is very different (>0.1), apply massive penalty
            # e.g. 0.15 diff * 50 = 7.5 cost added
            sig_cost = sig_dist * W_SIG
            
        return (angle_dist * W_ANGLE) + depth_cost + sig_cost

    def _estimate_distance_from_face_width(self, face, image_width):
        """
        Estimate distance using optical pinhole camera model.
        More reliable than depth sensor for Pepper.
        
        Formula: distance = (real_face_width * focal_length) / pixel_width
        """
        loc = face.get('location', {})
        face_width_px = loc.get('right', 0) - loc.get('left', 0)
        
        if face_width_px <= 0:
            return -1.0
            
        # Scale focal length based on actual image width (might not be QVGA)
        # QVGA = 320px, so scale factor = image_width / 320
        focal_length = FOCAL_LENGTH_QVGA * (image_width / 320.0)
        
        distance_m = (AVERAGE_FACE_WIDTH_M * focal_length) / face_width_px
        
        # Clamp to reasonable range (0.3m - 5.0m)
        distance_m = max(0.3, min(5.0, distance_m))
        
        return distance_m

    def _calculate_world_distance(self, track: TrackedPerson, det_yaw: float, det_pitch: float, det_dist: float) -> float:
        """
        Calculate approximate world-space distance between track and detection.
        Uses spherical coordinates (yaw, pitch, distance) to estimate 3D position difference.
        Returns distance in meters.
        """
        # Convert to approximate Cartesian (simplified - assumes small angles)
        # Track position
        t_dist = max(0.5, track.distance)
        t_yaw_rad = math.radians(track.world_yaw)
        t_pitch_rad = math.radians(track.world_pitch)
        t_x = t_dist * math.sin(t_yaw_rad)
        t_y = t_dist * math.sin(t_pitch_rad)
        t_z = t_dist * math.cos(t_yaw_rad) * math.cos(t_pitch_rad)
        
        # Detection position
        d_dist = max(0.5, det_dist)
        d_yaw_rad = math.radians(det_yaw)
        d_pitch_rad = math.radians(det_pitch)
        d_x = d_dist * math.sin(d_yaw_rad)
        d_y = d_dist * math.sin(d_pitch_rad)
        d_z = d_dist * math.cos(d_yaw_rad) * math.cos(d_pitch_rad)
        
        # Euclidean distance
        return math.sqrt((t_x - d_x)**2 + (t_y - d_y)**2 + (t_z - d_z)**2)

    def update(self, faces, image_width, image_height, head_yaw, head_pitch, 
               depth_data=None, depth_width=0, depth_height=0, camera_offsets=None):
        
        now = time.time()
        dt = now - self.last_update_time
        self.last_update_time = now
        
        if camera_offsets:
            self.set_camera_offsets(camera_offsets)
            
        # 1. Predict all tracks
        for track in self.tracks.values():
            track.predict(dt)
            
        # 2. Prepare Detections
        detections = []
        for face in faces:
            loc = face.get('location', {})
            cx = (loc.get('left') + loc.get('right')) / 2
            cy = (loc.get('top') + loc.get('bottom')) / 2
            
            # Angles
            y_off, p_off = self._pixel_to_angle(cx, cy, image_width, image_height)
            w_yaw = head_yaw + y_off
            w_pitch = head_pitch + p_off
            
            # Distance (optical estimation, not depth sensor)
            dist = self._estimate_distance_from_face_width(face, image_width)
            
            # Gaze
            raw = face.get('raw_face')
            ratio, looking = calculate_head_direction(raw, self.gaze_threshold)
            
            detections.append({
                'uuid': id(face), # Temp ID
                'face': face,
                'yaw': w_yaw,
                'pitch': w_pitch,
                'dist': dist,
                'ratio': ratio,
                'looking': looking,
                'signature': calculate_signature(face.get('raw_face'))
            })
            
        # 3. Global Matching (Greedy Best-First)
        # Calculate all-pairs costs
        matches = [] # (cost, track_id, det_idx)
        
        for tid, track in self.tracks.items():
            for didx, det in enumerate(detections):
                cost = self._calculate_cost(track, det['yaw'], det['pitch'], det['dist'], det['signature'])
                if cost < MATCH_THRESHOLD:
                    matches.append((cost, tid, didx))
                    
        # Sort by cost (lowest first)
        matches.sort(key=lambda x: x[0])
        
        assigned_tracks = set()
        assigned_dets = set()
        
        # Apply matches
        for cost, tid, didx in matches:
            if tid in assigned_tracks or didx in assigned_dets:
                continue
                
            # Match found!
            assigned_tracks.add(tid)
            assigned_dets.add(didx)
            
            # Update Track
            track = self.tracks[tid]
            det = detections[didx]
            face = det['face']
            loc = face.get('location')
            
            # Kalman Correct
            track.correct(det['yaw'], det['pitch'], det['dist'])
            
            # Update info
            track.bbox = {'x': loc['left'], 'y': loc['top'], 
                          'w': loc['right']-loc['left'], 'h': loc['bottom']-loc['top']}
            track.raw_face = face.get('raw_face')
            
            # Only update signature if face is relatively frontal (reliable landmarks)
            # ratio ranges -1 to 1. 0.6 means < ~35 degrees yaw
            if det['signature'] and abs(det['ratio']) < 0.6:
                track.signature = det['signature']
                
            track.last_seen_ms = int(now * 1000)
            track.is_lost = False
            
            # Gaze Smooth
            track.update_gaze(det['ratio'], self.gaze_threshold)
            
        # 4. Handle Unmatched Detections (Recovery or Pending New Tracks)
        for didx, det in enumerate(detections):
            if didx in assigned_dets:
                continue
                
            # === RECOVERY: Try to match with lost or unmatched active tracks ===
            recovered_track = None
            best_world_dist = WORLD_POSITION_MATCH_M
            
            # Check 1: World-Position Match against LOST tracks (PRIMARY)
            for lt in self.lost_tracks:
                world_dist = self._calculate_world_distance(lt, det['yaw'], det['pitch'], det['dist'])
                if world_dist < best_world_dist:
                    best_world_dist = world_dist
                    recovered_track = lt
            
            # Check 2: World-Position Match against ACTIVE unmatched tracks
            if not recovered_track:
                for tid, track in self.tracks.items():
                    if tid in assigned_tracks:
                        continue
                    world_dist = self._calculate_world_distance(track, det['yaw'], det['pitch'], det['dist'])
                    if world_dist < best_world_dist:
                        best_world_dist = world_dist
                        recovered_track = track
            
            if recovered_track:
                # Recovery Success
                is_from_lost = recovered_track in self.lost_tracks
                if is_from_lost:
                    self.lost_tracks.remove(recovered_track)
                    self.tracks[recovered_track.track_id] = recovered_track
                    print(f"[Tracker] Recovered track {recovered_track.track_id} (world_dist={best_world_dist:.2f}m)")
                else:
                    print(f"[Tracker] Re-associated track {recovered_track.track_id} (world_dist={best_world_dist:.2f}m)")
                
                # Update the track
                recovered_track.correct(det['yaw'], det['pitch'], det['dist'])
                loc = det['face'].get('location')
                recovered_track.bbox = {'x': loc['left'], 'y': loc['top'],
                                        'w': loc['right']-loc['left'], 'h': loc['bottom']-loc['top']}
                recovered_track.raw_face = det['face'].get('raw_face')
                if det['signature'] and abs(det['ratio']) < 0.6:
                    recovered_track.signature = det['signature']
                recovered_track.last_seen_ms = int(now * 1000)
                recovered_track.is_lost = False
                recovered_track.update_gaze(det['ratio'], self.gaze_threshold)
                assigned_tracks.add(recovered_track.track_id)
                assigned_dets.add(didx)
            else:
                # === NEW TRACK: Requires confirmation (multiple consecutive detections) ===
                # Check if this detection matches an existing pending track
                matched_pending = None
                for pid, pdata in self.pending_tracks.items():
                    p_yaw = pdata['yaw']
                    p_pitch = pdata['pitch']
                    p_dist = pdata['dist']
                    # Simple angle-based matching for pending tracks
                    angle_diff = math.sqrt((p_yaw - det['yaw'])**2 + (p_pitch - det['pitch'])**2)
                    if angle_diff < 10.0:  # Within 10 degrees
                        matched_pending = pid
                        break
                
                if matched_pending:
                    # Increment confirmation count
                    pdata = self.pending_tracks[matched_pending]
                    pdata['count'] += 1
                    pdata['yaw'] = det['yaw']
                    pdata['pitch'] = det['pitch']
                    pdata['dist'] = det['dist']
                    pdata['det'] = det
                    pdata['last_ms'] = int(now * 1000)
                    
                    if pdata['count'] >= NEW_TRACK_CONFIRMATIONS:
                        # Confirmed! Create actual track
                        tid = self.next_track_id
                        self.next_track_id += 1
                        
                        new_track = TrackedPerson(
                            track_id=tid,
                            world_yaw=det['yaw'],
                            world_pitch=det['pitch'],
                            distance=det['dist'],
                            bbox={},
                            raw_face=det['face'].get('raw_face'),
                            signature=det['signature'],
                            last_seen_ms=int(now * 1000),
                            first_seen_ms=pdata['first_ms']
                        )
                        loc = det['face'].get('location')
                        new_track.bbox = {'x': loc['left'], 'y': loc['top'], 
                                          'w': loc['right']-loc['left'], 'h': loc['bottom']-loc['top']}
                        new_track.update_gaze(det['ratio'], self.gaze_threshold)
                        
                        self.tracks[tid] = new_track
                        self.pending_recognition.append(tid)
                        del self.pending_tracks[matched_pending]
                        print(f"[Tracker] New track {tid} confirmed after {NEW_TRACK_CONFIRMATIONS} detections")
                else:
                    # Create new pending track
                    pid = self.next_pending_id
                    self.next_pending_id += 1
                    self.pending_tracks[pid] = {
                        'yaw': det['yaw'],
                        'pitch': det['pitch'],
                        'dist': det['dist'],
                        'det': det,
                        'count': 1,
                        'first_ms': int(now * 1000),
                        'last_ms': int(now * 1000)
                    }

        # Cleanup stale pending tracks (not seen for 500ms)
        stale_pending = [pid for pid, pdata in self.pending_tracks.items() 
                        if (int(now * 1000) - pdata['last_ms']) > 500]
        for pid in stale_pending:
            del self.pending_tracks[pid]

        # 5. Handle Lost Tracks
        current_ids = list(self.tracks.keys())
        for tid in current_ids:
            if tid not in assigned_tracks:
                track = self.tracks[tid]
                since_seen = (int(now * 1000) - track.last_seen_ms)
                
                if since_seen > self.track_timeout_ms:
                    # Move to lost buffer instead of deleting immediately
                    if not track.is_lost:
                         track.is_lost = True
                         self.lost_tracks.append(track)
                         del self.tracks[tid]
                         print(f"[Tracker] Track {tid} lost (buffered)")
        
        # Cleanup Lost Buffer (older than LOST_BUFFER_MS)
        self.lost_tracks = [t for t in self.lost_tracks 
                           if (int(now*1000) - t.last_seen_ms) < LOST_BUFFER_MS]
        
        return list(self.tracks.values())

    # Passthrough methods needed by server
    def get_tracks_needing_recognition(self):
        """Return track IDs needing recognition, sorted by oldest attempt first (fair rotation)."""
        now_ms = int(time.time() * 1000)
        res = [t for t in self.pending_recognition if t in self.tracks]
        for tid, t in self.tracks.items():
            if t.name == "Unknown" and (now_ms - t.last_recognition_ms) > self.recognition_cooldown_ms:
                if tid not in res: res.append(tid)
        
        # Sort by last_recognition_ms (oldest first) for fair rotation
        res.sort(key=lambda tid: self.tracks[tid].last_recognition_ms if tid in self.tracks else 0)
        return res

    def mark_recognition_pending(self, tid):
        if tid in self.tracks:
            self.tracks[tid].last_recognition_ms = int(time.time() * 1000)
            if tid in self.pending_recognition: self.pending_recognition.remove(tid)

    def set_recognition_result(self, tid, name, conf):
        if tid in self.tracks:
            # Uniqueness Check: If another track has this name, reset it to Unknown
            # UNLESS it's "Unknown" (multiple Unknowns are fine)
            if name != "Unknown":
                for other_tid, track in self.tracks.items():
                    if other_tid != tid and track.name == name:
                        print(f"[Tracker] Name conflict: {name} moved from {other_tid} to {tid}")
                        track.name = "Unknown"
                        track.confidence = 0.0
            
            self.tracks[tid].name = name
            self.tracks[tid].confidence = conf
            self.tracks[tid].last_recognition_ms = int(time.time() * 1000)
    
    def get_track_raw_face(self, tid):
        return self.tracks[tid].raw_face if tid in self.tracks else None

    def to_dict(self):
        return [
            {
                'track_id': t.track_id,
                'name': t.name,
                'confidence': t.confidence,
                'world_yaw': round(t.world_yaw, 2),
                'world_pitch': round(t.world_pitch, 2),
                'distance': round(t.distance, 2),
                'looking_at_robot': t.looking_at_robot,
                'head_direction': round(t.head_yaw_ratio, 2),
                'bbox': t.bbox,
                'last_seen_ms': t.last_seen_ms,
                'time_since_seen_ms': int(time.time() * 1000) - t.last_seen_ms,
                'track_age_ms': int(time.time() * 1000) - t.first_seen_ms,
                'gaze_duration_ms': (int(time.time() * 1000) - t.looking_since_ms) if t.looking_at_robot and t.looking_since_ms > 0 else 0
            }
            for t in self.tracks.values()
        ]
