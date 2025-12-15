#!/usr/bin/env python3
"""
Face Database Manager for Pepper
Stores and loads face encodings with associated names and thumbnail images.
"""

import os
import sys
import json
import pickle
import cv2

# Add custom packages path
sys.path.insert(0, '/home/nao/python_packages')
os.environ['LD_LIBRARY_PATH'] = '/home/nao/python_packages/libs:' + os.environ.get('LD_LIBRARY_PATH', '')

DATABASE_PATH = '/home/nao/face_data/faces.pkl'
IMAGES_DIR = '/home/nao/face_data/images'


def ensure_database_dir():
    """Create database directory if it doesn't exist."""
    os.makedirs(os.path.dirname(DATABASE_PATH), exist_ok=True)
    os.makedirs(IMAGES_DIR, exist_ok=True)


def load_database():
    """Load the face database from disk."""
    if not os.path.exists(DATABASE_PATH):
        return {'names': [], 'encodings': []}
    
    with open(DATABASE_PATH, 'rb') as f:
        return pickle.load(f)


def save_database(db):
    """Save the face database to disk."""
    ensure_database_dir()
    with open(DATABASE_PATH, 'wb') as f:
        pickle.dump(db, f)


def get_safe_filename(name):
    """Convert name to safe filename."""
    return "".join(x for x in name if x.isalnum() or x in (' ', '-', '_')).strip().replace(' ', '_')


def save_face_image(name, image):
    """Save a face thumbnail image."""
    ensure_database_dir()
    safe_name = get_safe_filename(name)
    path = os.path.join(IMAGES_DIR, f"{safe_name}.jpg")
    cv2.imwrite(path, image)
    return path


def delete_face_image(name):
    """Delete the face thumbnail image."""
    safe_name = get_safe_filename(name)
    path = os.path.join(IMAGES_DIR, f"{safe_name}.jpg")
    if os.path.exists(path):
        os.remove(path)


def add_face(name, encoding, image=None):
    """Add a new face to the database and optionally save image."""
    db = load_database()
    
    # Remove existing entries for this name if you want to update (optional strategy)
    # For now, we allow multiple encodings, but update the image
    
    db['names'].append(name)
    db['encodings'].append(encoding)
    save_database(db)
    
    if image is not None:
        save_face_image(name, image)
        
    return len(db['names'])


def remove_face(name):
    """Remove all faces with the given name."""
    db = load_database()
    indices_to_remove = [i for i, n in enumerate(db['names']) if n == name]
    
    if not indices_to_remove:
        return 0
        
    for i in reversed(indices_to_remove):
        del db['names'][i]
        del db['encodings'][i]
    
    save_database(db)
    delete_face_image(name)
    
    return len(indices_to_remove)


def list_faces():
    """List all registered faces."""
    db = load_database()
    # Count occurrences of each name
    name_counts = {}
    for name in db['names']:
        name_counts[name] = name_counts.get(name, 0) + 1
    return name_counts


def get_all_encodings():
    """Get all encodings and names for recognition."""
    db = load_database()
    return db['names'], db['encodings']


if __name__ == '__main__':
    # Test the database
    print("Face Database Status:")
    faces = list_faces()
    if faces:
        for name, count in faces.items():
            print(f"  - {name}: {count} encoding(s)")
            # Check if image exists
            safe_name = get_safe_filename(name)
            img_path = os.path.join(IMAGES_DIR, f"{safe_name}.jpg")
            has_img = "Yes" if os.path.exists(img_path) else "No"
            print(f"    Image: {has_img}")
    else:
        print("  No faces registered yet.")
