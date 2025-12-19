#!/usr/bin/env python3
"""
Face Database Manager for Pepper
Stores and loads face encodings with associated names and thumbnail images.
Supports multiple images per person with indexed filenames.
"""

import os
import sys
import json
import pickle
import cv2
import glob

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


def get_face_image_count(name):
    """Get number of images for a person."""
    safe_name = get_safe_filename(name)
    pattern = os.path.join(IMAGES_DIR, f"{safe_name}_*.jpg")
    return len(glob.glob(pattern))


def get_face_image_paths(name):
    """Get all image paths for a person, sorted by index."""
    safe_name = get_safe_filename(name)
    pattern = os.path.join(IMAGES_DIR, f"{safe_name}_*.jpg")
    paths = glob.glob(pattern)
    # Sort by index number
    paths.sort(key=lambda p: int(os.path.basename(p).rsplit('_', 1)[1].replace('.jpg', '')))
    return paths


def save_face_image(name, image):
    """Save a face thumbnail image with incrementing index."""
    ensure_database_dir()
    safe_name = get_safe_filename(name)
    
    # Find next available index
    index = get_face_image_count(name)
    path = os.path.join(IMAGES_DIR, f"{safe_name}_{index}.jpg")
    cv2.imwrite(path, image)
    return path


def delete_all_face_images(name):
    """Delete ALL images for a person (including legacy format)."""
    safe_name = get_safe_filename(name)
    
    # Delete new format: name_0.jpg, name_1.jpg, etc.
    for path in get_face_image_paths(name):
        if os.path.exists(path):
            os.remove(path)
    
    # Delete legacy format: name.jpg (from old code)
    legacy_path = os.path.join(IMAGES_DIR, f"{safe_name}.jpg")
    if os.path.exists(legacy_path):
        os.remove(legacy_path)
        print(f"[DB] Deleted legacy image: {legacy_path}")


def delete_face_image_by_index(name, index):
    """Delete a specific image by index and renumber remaining images."""
    safe_name = get_safe_filename(name)
    target_path = os.path.join(IMAGES_DIR, f"{safe_name}_{index}.jpg")
    
    print(f"[DB] Delete request: name={name}, index={index}, target={target_path}")
    
    if not os.path.exists(target_path):
        print(f"[DB] ERROR: File not found: {target_path}")
        # List existing files for debug
        existing = get_face_image_paths(name)
        print(f"[DB] Existing files: {existing}")
        return False
    
    # Delete the target image
    os.remove(target_path)
    print(f"[DB] Deleted: {target_path}")
    
    # Renumber remaining images to close the gap
    paths = get_face_image_paths(name)
    print(f"[DB] Remaining files before renumber: {paths}")
    for new_idx, old_path in enumerate(paths):
        new_path = os.path.join(IMAGES_DIR, f"{safe_name}_{new_idx}.jpg")
        if old_path != new_path:
            print(f"[DB] Renaming: {old_path} -> {new_path}")
            os.rename(old_path, new_path)
    
    return True


def add_face(name, encoding, image=None):
    """Add a new face to the database and optionally save image."""
    db = load_database()
    
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
    delete_all_face_images(name)
    
    return len(indices_to_remove)


def remove_face_by_index(name, index):
    """Remove a specific encoding+image by index."""
    db = load_database()
    
    # Find all indices for this name
    indices_for_name = [i for i, n in enumerate(db['names']) if n == name]
    
    if index < 0 or index >= len(indices_for_name):
        return False
    
    # Get the actual database index
    db_index = indices_for_name[index]
    
    # Delete from database
    del db['names'][db_index]
    del db['encodings'][db_index]
    save_database(db)
    
    # Delete image and renumber
    delete_face_image_by_index(name, index)
    
    return True


def list_faces():
    """List all registered faces with image counts."""
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
            img_count = get_face_image_count(name)
            print(f"    Images: {img_count}")
    else:
        print("  No faces registered yet.")
