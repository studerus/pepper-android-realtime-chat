#!/usr/bin/env python3
"""
Face Recognition Server for Pepper
Provides a REST API for face recognition, registration, and management.
Persistent process to avoid startup overhead.
"""

import os
import sys
import json
import time
import cgi
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# Add custom packages path
sys.path.insert(0, '/home/nao/python_packages')
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')

import cv2
import numpy as np

# Import our modules
from face_database import add_face, remove_face, list_faces, get_all_encodings, get_safe_filename, IMAGES_DIR
from recognize_face import capture_image_from_camera, get_face_models, load_ppm_image, RECOGNITION_THRESHOLD

# Global models (loaded once)
DETECTOR = None
RECOGNIZER = None

PORT = 5000

def init_models():
    """Load models into global variables."""
    global DETECTOR, RECOGNIZER
    print("Loading models...")
    DETECTOR, RECOGNIZER = get_face_models()
    print("Models loaded.")


def recognize_process(image):
    """Process image for recognition using pre-loaded models."""
    height, width = image.shape[:2]
    DETECTOR.setInputSize((width, height))
    
    _, faces = DETECTOR.detect(image)
    
    if faces is None:
        return []
        
    results = []
    known_names, known_encodings = get_all_encodings()
    
    for face in faces:
        aligned_face = RECOGNIZER.alignCrop(image, face)
        encoding = RECOGNIZER.feature(aligned_face)
        
        name = "Unknown"
        confidence = 0.0
        
        if len(known_encodings) > 0:
            best_score = 0.0
            for known_name, known_encoding in zip(known_names, known_encodings):
                k_enc = np.array(known_encoding, dtype=np.float32) if not isinstance(known_encoding, np.ndarray) else known_encoding
                score = RECOGNIZER.match(k_enc, encoding, 1) # 1 = Cosine Similarity
                
                if score > best_score:
                    best_score = score
                    if score > RECOGNITION_THRESHOLD:
                        name = known_name
            
            confidence = best_score
            
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
        
        if parsed.path == '/recognize':
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


if __name__ == '__main__':
    try:
        init_models()
        server = HTTPServer(('0.0.0.0', PORT), FaceHandler)
        print(f"Face Recognition Server running on port {PORT}")
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopping server...")
        server.socket.close()


