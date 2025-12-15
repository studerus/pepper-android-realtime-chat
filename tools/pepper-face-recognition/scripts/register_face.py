#!/usr/bin/env python3
"""
Register a face from Pepper's camera using OpenCV (YuNet + SFace).
Usage: python3 register_face.py "Person Name"
"""

import os
import sys
import numpy as np

# Add custom packages path BEFORE importing cv2
sys.path.insert(0, '/home/nao/python_packages')
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')

import cv2
from face_database import add_face
from recognize_face import capture_image_from_camera, get_face_models, load_ppm_image

def register_face_from_camera(name):
    """Capture image from camera and register the face."""
    print(f"Capturing image for '{name}'...")
    
    image_path = capture_image_from_camera()
    if not image_path:
        print("Failed to capture image from camera.")
        return False
    
    print("Loading image...")
    try:
        image = load_ppm_image(image_path)
    except Exception as e:
        print(f"Error loading image: {e}")
        return False
    
    print("Detecting faces...")
    try:
        detector, recognizer = get_face_models()
        
        height, width = image.shape[:2]
        detector.setInputSize((width, height))
        
        _, faces = detector.detect(image)
        
        if faces is None or len(faces) == 0:
            print("No face detected in the image.")
            return False
        
        # Use largest face
        target_face = faces[0]
        if len(faces) > 1:
            print(f"Warning: {len(faces)} faces detected. Using the largest one.")
            # faces format: x, y, w, h ...
            # Area = w * h
            faces_sorted = sorted(faces, key=lambda f: f[2] * f[3], reverse=True)
            target_face = faces_sorted[0]
        
        print("Extracting face features...")
        aligned_face = recognizer.alignCrop(image, target_face)
        feature = recognizer.feature(aligned_face)
        
        if feature is None:
            print("Could not extract face features.")
            return False
            
        # Feature is a numpy array (1, 128) float32
        # We store it as is (or as list)
        
        print("Saving to database...")
        count = add_face(name, feature)
        
        print(f"Success! '{name}' registered. Total faces in database: {count}")
        return True
        
    except Exception as e:
        print(f"Error during processing: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python3 register_face.py <name>")
        sys.exit(1)
    
    name = sys.argv[1]
    register_face_from_camera(name)
