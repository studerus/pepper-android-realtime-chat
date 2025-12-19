#!/usr/bin/env python3
"""Quick database diagnostic"""
import sys
sys.path.insert(0, '/home/nao/python_packages')
import numpy as np
from face_database import load_database, list_faces

print("=== Face Database Check ===")
faces = list_faces()
print(f"Faces: {faces}")

db = load_database()
print(f"Encodings: {len(db['encodings'])}")

for i, enc in enumerate(db['encodings']):
    arr = np.array(enc)
    print(f"  [{i}] shape={arr.shape} norm={np.linalg.norm(arr):.3f}")
