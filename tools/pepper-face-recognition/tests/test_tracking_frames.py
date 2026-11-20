import io
import struct
import sys
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import face_recognition_server as server
from face_tracker import FaceTracker


class TrackingFramesTest(unittest.TestCase):
    def test_recovery_window_starts_at_transfer_and_expires_before_matching(self):
        faces = [{'location': {'left': 140, 'right': 180, 'top': 80, 'bottom': 120}}]
        for return_at, same_id in [(16.49, True), (16.5, False), (18, False)]:
            with self.subTest(return_at=return_at), patch('face_tracker.time.time', return_value=10) as clock:
                tracker = FaceTracker(confirm_count=2, track_timeout_ms=3000, lost_buffer_ms=2500)
                def frame(detections):
                    tracker.update(detections, 320, 240, 0, 0)
                frame(faces)
                frame(faces)
                old_id, = tracker.tracks
                clock.return_value = 14
                frame([])
                self.assertNotIn(old_id, tracker.tracks)
                self.assertEqual([old_id], [t.track_id for t in tracker.lost_tracks])
                clock.return_value = return_at
                frame(faces)
                frame(faces)
                current_id, = tracker.tracks
                self.assertEqual(same_id, old_id == current_id)
                self.assertFalse(tracker.lost_tracks)

    def test_timing_is_aggregated_and_counters_reset(self):
        timing = server.TrackingTiming(clock=Mock(side_effect=[0, 1, 59, 60, 121]))
        with patch('builtins.print') as log:
            for duration in (10, 30):
                timing.record(duration, 1, 2, 3, 4, 5)
            log.assert_not_called()
            timing.record(20, 1, 2, 3, 4, 5)
            log.assert_called_once_with('[Timing] frames=3, fps=0.1, mean=20.0ms')
            timing.record(12, 1, 2, 3, 4, 5)
            self.assertEqual('[Timing] frames=1, fps=0.0, mean=12.0ms', log.call_args[0][0])

    def test_debug_keeps_per_frame_timings(self):
        timing = server.TrackingTiming(debug=True)
        with patch('builtins.print') as log:
            timing.record(15, 1, 2, 3, 4, 5)
            log.assert_called_once_with('[Timing] frame=1ms, resize=2ms, detect=3ms, '
                                        'track=4ms, recog=5ms, total=15ms')

    def reader(self):
        with patch.object(server.SharedMemoryReader, 'setup'):
            reader = server.SharedMemoryReader()
        header = struct.pack('<4sIdIIII', b'PSHM', 7, time.time(), 1, 1, 3, 2)
        reader.m = io.BytesIO(header.ljust(64, b'\0') + b'{}abc')
        return reader

    def test_duplicate_skips_tracking_http_and_capture_but_stall_allows_recovery(self):
        reader = self.reader()
        with patch.object(server, 'SHM_READER', reader), patch.object(server, 'CAMERA_DAEMON_AVAILABLE', True), \
                patch.object(server, 'get_synchronized_frame_http') as http, \
                patch.object(server, 'ThreadPoolExecutor') as fallback, patch.object(server, 'TRACKER') as tracker:
            self.assertEqual((1, 1, 3), server.get_synchronized_frame()['rgb_image'].shape)
            self.assertEqual((None, 0), server.update_tracking_once())
            self.assertIsNone(server.get_synchronized_frame())  # Non-tracking API still returns frame or None.
            http.assert_not_called()
            fallback.assert_not_called()
            tracker.update.assert_not_called()
            reader.last_progress -= server.FRAME_STALL_SECONDS + 1
            self.assertIs(http.return_value, server.get_synchronized_frame(for_tracking=True))
            http.assert_called_once()

    def test_unavailable_frame_still_uses_capture_fallback(self):
        with patch.object(server, 'CAMERA_DAEMON_AVAILABLE', True), \
                patch.object(server, 'get_synchronized_frame', return_value=None), \
                patch.object(server, 'capture_image_from_camera', return_value=None) as capture, \
                patch.object(server, 'get_sensor_data', return_value=None) as sensors, patch.object(server, 'TRACKER'):
            server.update_tracking_once()
            capture.assert_called_once()
            sensors.assert_called_once_with(True)

    def test_skipped_frame_keeps_cache_and_waits_even_at_max_speed(self):
        existing = [{'track_id': 42}]
        with patch.object(server, 'TRACKING_RUNNING', True), patch.object(server, 'CACHED_PEOPLE', existing), \
                patch.object(server, 'update_tracking_once', return_value=(None, 0)), \
                patch.object(server, 'get_tracking_interval', return_value=0), \
                patch.object(server, 'get_client_count', create=True) as clients, \
                patch.object(server.time, 'sleep', side_effect=lambda _: setattr(server, 'TRACKING_RUNNING', False)) as wait:
            server.background_tracking_loop()
            self.assertIs(existing, server.CACHED_PEOPLE)
            clients.assert_not_called()
            wait.assert_called_once()
            self.assertGreater(wait.call_args[0][0], 0)

    def test_daemon_http_formats(self):
        head = b'{"head_yaw":12}'
        for header, depth in [(struct.pack('<4sIHHI', b'PFR2', len(head), 1, 1, 3), b''),
                              (struct.pack('<4sIHHIHHI', b'PFR1', len(head), 1, 1, 3, 1, 1, 2), b'xy')]:
            response = Mock(status=200)
            response.read.return_value = header + head + b'abc' + depth
            with patch.object(server, 'get_persistent_connection') as connect:
                connect.return_value.getresponse.return_value = response
                frame = server.get_synchronized_frame_http()
                self.assertEqual(12, frame['head_yaw'])
                self.assertEqual(b'abc', frame['rgb_image'].tobytes())
                self.assertEqual(depth or None, frame['depth_data'])

    def test_newly_confirmed_and_retained_tracks_are_distinguished(self):
        tracker = FaceTracker(confirm_count=2)
        faces = [{'location': {'left': 140, 'right': 180, 'top': 80, 'bottom': 120}}]
        tracker.update(faces, 320, 240, 0, 0)
        tracker.update(faces, 320, 240, 0, 0)
        self.assertTrue(tracker.to_dict()[0]['position_observed'])
        tracker.update([], 320, 240, 0, 0)
        self.assertFalse(tracker.to_dict()[0]['position_observed'])
        tracker.update(faces, 320, 240, 0, 0, pose_observed=False)
        self.assertFalse(tracker.to_dict()[0]['position_observed'])
