"""Offline regression tests for canonical Modrinth filename replacement."""
import copy
import hashlib
import importlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

p = importlib.import_module("publish-modrinth")


class FakeModrinth:
    def __init__(self, version, fail=None):
        self.version = copy.deepcopy(version)
        self.fail = fail
        self.events = []
        self.uploads = {}
        self.downloads_verified = set()

    def request(self, method, route, *, payload=None):
        self.events.append(method)
        if method == "GET":
            return copy.deepcopy(self.version)
        if method == "PATCH":
            self.version.update(payload)
        elif method == "DELETE":
            assert route.startswith("/version_file/"), "Must never delete a version"
            digest = route.split("/version_file/", 1)[1].split("?", 1)[0]
            matches = [row for row in self.version["files"] if row["hashes"]["sha512"] == digest]
            assert len(matches) == 1, "Deletion hash must be unambiguous"
            old = matches[0]
            remaining = [row for row in self.version["files"] if row is not old]
            assert any(row["hashes"]["sha512"] in self.downloads_verified for row in remaining)
            if self.fail == "DELETE_STAGING" and "-uploading-" in old["filename"]:
                raise RuntimeError("Simulated staging deletion failure")
            self.version["files"].remove(old)
            if self.fail == "LOST_DELETE_RESPONSE":
                self.fail = None
                raise RuntimeError("Simulated lost response after deletion")

    def multipart(self, route, payload, path, *, filename=None):
        self.events.append("UPLOAD")
        staging = "-uploading-" in filename
        if self.fail == "UPLOAD" or (self.fail == "FINAL_UPLOAD" and not staging):
            raise RuntimeError("Simulated upload failure")
        assert route == f"/version/{self.version['id']}/file"
        assert filename not in {row["filename"] for row in self.version["files"]}
        digest = p.sha(path, "sha512")
        assert digest not in {row["hashes"]["sha512"] for row in self.version["files"]}
        self.uploads[filename] = path.read_bytes()
        self.version["files"].append({"filename": filename, "primary": False,
                                      "size": path.stat().st_size,
                                      "hashes": {"sha512": digest}})
        if self.fail == "LOST_UPLOAD_RESPONSE":
            raise RuntimeError("Simulated lost response after successful upload")

    def verify_download(self, file, expected):
        assert hashlib.sha512(self.uploads[file["filename"]]).hexdigest() == expected
        if self.fail == "STALE_CDN" and "-uploading-" not in file["filename"]:
            raise RuntimeError("Simulated stale CDN content")
        self.downloads_verified.add(expected)


class ReplacementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.target = next(iter(p.targets()))
        self.path = self.root / Path(self.target.relative).name
        with zipfile.ZipFile(self.path, "w") as jar:
            jar.writestr("Example.class", b"new verified class")
            jar.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        self.backups = self.root / "backups"
        self.folder = self.backups / p.version_number(self.target)
        self.folder.mkdir(parents=True)
        self.configure_original()

    def configure_original(self, same_content=False):
        filename = self.path.stem + "-previoushash.jar" if same_content else self.path.name
        old_path = self.folder / filename
        if same_content:
            old_path.write_bytes(self.path.read_bytes())
        else:
            with zipfile.ZipFile(old_path, "w") as jar:
                jar.writestr("Example.class", b"old class")
        self.original = p.payload(self.target)
        self.original.update(id="existing-version", downloads=20,
                             files=[{"filename": filename, "primary": True,
                                     "size": old_path.stat().st_size,
                                     "hashes": {"sha512": p.sha(old_path, "sha512")}}])
        (self.folder / "metadata.json").write_text(json.dumps(self.original), encoding="utf-8")

    def run_replacement(self, client):
        with patch.object(p, "verify_file_download", side_effect=client.verify_download):
            p.replace_version_file(client, self.target, self.path, client.version, self.backups)

    def assert_replaced(self, client):
        self.assertEqual(client.version["id"], self.original["id"])
        self.assertEqual(client.version["downloads"], 20)
        self.assertEqual(len(client.version["files"]), 1)
        final = p.remote_file(client.version)
        self.assertEqual(final["filename"], self.path.name)
        self.assertEqual(final["hashes"]["sha512"], p.sha(self.path, "sha512"))
        self.assertEqual(client.uploads[self.path.name], self.path.read_bytes())

    def test_same_name_update_keeps_canonical_filename_and_exact_bytes(self):
        client = FakeModrinth(self.original)
        self.run_replacement(client)
        self.assert_replaced(client)
        self.assertEqual(client.events.count("UPLOAD"), 2)

    def test_same_content_rename_keeps_canonical_filename_and_exact_bytes(self):
        self.configure_original(same_content=True)
        client = FakeModrinth(self.original)
        self.run_replacement(client)
        self.assert_replaced(client)

    def test_staging_upload_failure_keeps_original(self):
        client = FakeModrinth(self.original, "UPLOAD")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.assertEqual(client.version, self.original)
        self.assertNotIn("DELETE", client.events)

    def test_final_upload_failure_keeps_identical_jar_entries_and_resumes(self):
        client = FakeModrinth(self.original, "FINAL_UPLOAD")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.assertEqual(len(client.version["files"]), 1)
        temporary = next((self.folder / "staging").glob("*.jar"))
        with zipfile.ZipFile(self.path) as expected, zipfile.ZipFile(temporary) as actual:
            self.assertEqual(actual.namelist(), expected.namelist())
            self.assertTrue(all(actual.read(name) == expected.read(name) for name in expected.namelist()))
        client.fail = None
        self.run_replacement(client)
        self.assert_replaced(client)

    def test_lost_upload_response_is_reconciled(self):
        client = FakeModrinth(self.original, "LOST_UPLOAD_RESPONSE")
        self.run_replacement(client)
        self.assert_replaced(client)

    def test_lost_delete_response_resumes(self):
        client = FakeModrinth(self.original, "LOST_DELETE_RESPONSE")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.run_replacement(client)
        self.assert_replaced(client)

    def test_cleanup_failure_resumes_without_reupload(self):
        client = FakeModrinth(self.original, "DELETE_STAGING")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        client.fail = None
        self.run_replacement(client)
        self.assert_replaced(client)
        self.assertEqual(client.events.count("UPLOAD"), 2)

    def test_stale_download_keeps_verified_staging_file(self):
        client = FakeModrinth(self.original, "STALE_CDN")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.assertEqual(len(client.version["files"]), 2)
        self.assertTrue(any("-uploading-" in row["filename"] for row in client.version["files"]))
        client.fail = None
        self.run_replacement(client)
        self.assert_replaced(client)

    def test_unrelated_remote_file_blocks_mutation(self):
        client = FakeModrinth(self.original)
        client.version["files"].append({"filename": "other.jar", "primary": False,
                                        "hashes": {"sha512": hashlib.sha512(b"other").hexdigest()}})
        with self.assertRaisesRegex(RuntimeError, "Remote files changed"):
            self.run_replacement(client)
        self.assertEqual(client.events, ["GET"])


if __name__ == "__main__":
    unittest.main()
