import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import face_database as db


class FaceDatabaseTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.images = Path(tmp.name)
        paths = patch.multiple(db, IMAGES_DIR=tmp.name, DATABASE_PATH=str(self.images / "faces.pkl"))
        paths.start()
        self.addCleanup(paths.stop)
        db.save_database(dict(names=["Max", "Max", "Max_Mueller"], encodings=[1, 2, 3]))
        for name in ("Max_0.jpg", "Max_1.jpg", "Max.jpg", "Max_Mueller_0.jpg", "Max_backup.jpg"):
            (self.images / name).write_bytes(name.encode())

    def test_remove_person_leaves_other_person_and_unrelated_file(self):
        self.assertEqual(2, db.get_face_image_count("Max"))
        self.assertEqual(2, db.remove_face("Max"))
        self.assertEqual({"Max_Mueller": 1}, db.list_faces())
        self.assertEqual({"Max_Mueller_0.jpg", "Max_backup.jpg"}, {p.name for p in self.images.glob("*.jpg")})

    def test_remove_index_only_renumbers_exact_owner(self):
        self.assertTrue(db.remove_face_by_index("Max", 0))
        self.assertEqual(b"Max_1.jpg", (self.images / "Max_0.jpg").read_bytes())
        self.assertEqual(b"Max_Mueller_0.jpg", (self.images / "Max_Mueller_0.jpg").read_bytes())
        self.assertEqual(["Max", "Max_Mueller"], db.load_database()["names"])

    def test_numeric_name_does_not_delete_parent_photo_as_legacy(self):
        db.add_face("Max_0", 4)
        (self.images / "Max_0_0.jpg").touch()
        self.assertEqual(1, db.remove_face("Max_0"))
        self.assertFalse((self.images / "Max_0_0.jpg").exists())
        self.assertEqual(b"Max_0.jpg", (self.images / "Max_0.jpg").read_bytes())
