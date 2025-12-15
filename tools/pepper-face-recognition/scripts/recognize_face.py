#!/usr/bin/env python3
"""
Recognize faces from Pepper's camera using OpenCV (YuNet + SFace).
"""

import os
import sys
import json
import numpy as np

# Add custom packages path BEFORE importing cv2
sys.path.insert(0, '/home/nao/python_packages')
# OpenCV headless might need some libraries, usually included in wheel or apt packages
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')

import cv2
from face_database import get_all_encodings

# Model paths (deployed to this folder)
YUNET_PATH = '/home/nao/face_data/face_detection_yunet.onnx'
SFACE_PATH = '/home/nao/face_data/face_recognition_sface.onnx'

# Thresholds
# SFace cosine similarity threshold: 0.363
# We can be slightly looser or stricter. 0.363 is standard.
RECOGNITION_THRESHOLD = 0.363
CONFIDENCE_THRESHOLD = 0.8 # For detection

# NAOqi capture script settings
CAPTURE_SCRIPT = '/home/nao/face_data/capture_image.py'
IMAGE_PATH = '/tmp/face_capture.ppm'


def get_face_models():
    """Initialize OpenCV face detector and recognizer."""
    if not os.path.exists(YUNET_PATH) or not os.path.exists(SFACE_PATH):
        raise RuntimeError("Model files not found in /home/nao/face_data/")

    # YuNet: model_path, config, input_size, score_threshold, nms_threshold, top_k
    detector = cv2.FaceDetectorYN.create(
        YUNET_PATH, "", (320, 240), CONFIDENCE_THRESHOLD, 0.3, 5000
    )
    recognizer = cv2.FaceRecognizerSF.create(SFACE_PATH, "")
    return detector, recognizer


def capture_image_from_camera():
    """Capture an image using NAOqi (via Python 2.7)."""
    import subprocess
    
    # Python 2.7 script to access ALVideoDevice
    capture_script_content = '''#!/usr/bin/env python2
import sys
sys.path.append("/opt/aldebaran/lib/python2.7/site-packages")
import qi
import os

session = qi.Session()
try:
    session.connect("tcp://127.0.0.1:9559")
except Exception:
    print("Error connecting to Naoqi")
    sys.exit(1)

video = session.service("ALVideoDevice")

# Subscribe to camera (top camera=0, QVGA=1, RGB=11, FPS=10)
name = "face_rec_" + str(os.getpid())
sub = video.subscribeCamera(name, 0, 1, 11, 10)
try:
    img = video.getImageRemote(sub)
finally:
    video.unsubscribe(sub)

if img is None:
    print("No image received")
    sys.exit(1)

width, height = img[0], img[1]
data = img[6]

# Save as PPM
with open("/tmp/face_capture.ppm", "wb") as f:
    f.write("P6\\n{} {}\\n255\\n".format(width, height).encode())
    f.write(bytearray(data))

print("OK")
'''
    
    os.makedirs(os.path.dirname(CAPTURE_SCRIPT), exist_ok=True)
    with open(CAPTURE_SCRIPT, 'w') as f:
        f.write(capture_script_content)
    
    result = subprocess.run(['python2', CAPTURE_SCRIPT], 
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    
    if result.returncode != 0 or not os.path.exists(IMAGE_PATH):
        # sys.stderr.write("Capture failed: " + result.stderr + "\n")
        return None
    
    return IMAGE_PATH


def load_ppm_image(path):
    """Load a PPM image using OpenCV."""
    # OpenCV can read PPM directly
    img = cv2.imread(path)
    if img is None:
        raise ValueError("Failed to load image: " + path)
    return img


def recognize_faces(image):
    """
    Recognize faces in an image using OpenCV.
    """
    detector, recognizer = get_face_models()
    
    # Set input size for detector
    height, width = image.shape[:2]
    detector.setInputSize((width, height))
    
    # Detect faces
    # faces is a float32 array of shape [n, 15]
    # x, y, w, h, x_re, y_re, x_le, y_le, x_nt, y_nt, x_rcm, y_rcm, x_lcm, y_lcm, confidence
    _, faces = detector.detect(image)
    
    if faces is None:
        return [], "No faces detected"
        
    results = []
    known_names, known_encodings = get_all_encodings()
    
    for face in faces:
        # Align and extract feature
        aligned_face = recognizer.alignCrop(image, face)
        encoding = recognizer.feature(aligned_face)
        
        name = "Unknown"
        confidence = 0.0
        
        if len(known_encodings) > 0:
            # SFace uses Cosine Similarity (1) or L2 Distance (0)
            # We use Cosine Similarity. Match if score > threshold.
            best_score = 0.0
            
            for known_name, known_encoding in zip(known_names, known_encodings):
                # Ensure encoding is numpy array float32
                k_enc = np.array(known_encoding, dtype=np.float32) if not isinstance(known_encoding, np.ndarray) else known_encoding
                
                # 1 = Cosine Similarity
                score = recognizer.match(k_enc, encoding, 1)
                
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
        
    return results, None


def recognize_from_camera():
    """Capture image from camera and recognize faces."""
    image_path = capture_image_from_camera()
    if not image_path:
        return {'error': 'Failed to capture image from camera'}
    
    try:
        image = load_ppm_image(image_path)
        results, error = recognize_faces(image)
        
        if error:
            return {'error': error, 'faces': []}
        
        return {'faces': results}
    except Exception as e:
        return {'error': str(e)}


def recognize_from_file(image_path):
    """Recognize faces from an image file."""
    try:
        image = cv2.imread(image_path)
        if image is None:
            return {'error': 'Failed to load image file'}
            
        results, error = recognize_faces(image)
        
        if error:
            return {'error': error, 'faces': []}
        
        return {'faces': results}
    except Exception as e:
        return {'error': str(e)}


if __name__ == '__main__':
    if len(sys.argv) >= 2:
        # Recognize from file
        result = recognize_from_file(sys.argv[1])
    else:
        # Recognize from camera
        # print("Capturing from camera...", file=sys.stderr)
        result = recognize_from_camera()
    
    # Output as JSON
    print(json.dumps(result))
