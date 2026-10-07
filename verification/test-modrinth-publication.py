"""Offline regression tests for preserving versions during file replacement."""
import copy
import hashlib
import importlib
import json
from pathlib import Path
import tempfile
import unittest

p = importlib.import_module("publish-modrinth")


class FakeModrinth:
    def __init__(self, version, fail=None):
        self.version = copy.deepcopy(version)
        self.fail = fail
        self.events = []

    def request(self, method, route, *, payload=None):
        self.events.append(method)
        if method == "GET":
            return copy.deepcopy(self.version)
        if self.fail == method:
            raise RuntimeError("Simulated request failure")
        if method == "PATCH":
            # Current Modrinth ignores the removed primary_file edit field.
            self.version.update({k: v for k, v in payload.items() if k != "primary_file"})
        elif method == "DELETE":
            assert route.startswith("/version_file/"), "Must never delete a version"
            digest = route.split("/version_file/", 1)[1].split("?", 1)[0]
            old = next(row for row in self.version["files"] if row["hashes"]["sha512"] == digest)
            assert len(self.version["files"]) == 2, "Must retain a verified replacement"
            assert "PATCH" in self.events, "Must update and verify metadata first"
            self.version["files"].remove(old)

    def multipart(self, route, payload, path, *, filename=None):
        self.events.append("UPLOAD")
        if self.fail == "UPLOAD":
            raise RuntimeError("Simulated upload failure")
        assert route == f"/version/{self.version['id']}/file"
        assert filename not in {row["filename"] for row in self.version["files"]}
        self.version["files"].append({"filename": filename, "primary": False,
                                      "size": path.stat().st_size,
                                      "hashes": {"sha512": p.sha(path, "sha512")}})
        if self.fail == "LOST_UPLOAD_RESPONSE":
            raise RuntimeError("Simulated lost response after successful upload")


class ReplacementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.target = next(iter(p.targets()))
        self.path = self.root / Path(self.target.relative).name
        self.path.write_bytes(b"new verified artifact")
        self.backups = self.root / "backups"
        folder = self.backups / p.version_number(self.target)
        folder.mkdir(parents=True)
        old_path = folder / self.path.name
        old_path.write_bytes(b"old artifact")
        self.original = p.payload(self.target)
        self.original.update(id="existing-version", downloads=20,
                             files=[{"filename": self.path.name, "primary": True,
                                     "size": old_path.stat().st_size,
                                     "hashes": {"sha512": p.sha(old_path, "sha512")}}])
        (folder / "metadata.json").write_text(json.dumps(self.original), encoding="utf-8")

    def run_replacement(self, client):
        p.replace_version_file(client, self.target, self.path, client.version, self.backups)

    def assert_replaced(self, client):
        self.assertEqual(client.version["id"], self.original["id"])
        self.assertEqual(client.version["downloads"], 20)
        self.assertEqual(len(client.version["files"]), 1)
        self.assertEqual(p.remote_file(client.version)["hashes"]["sha512"], p.sha(self.path, "sha512"))

    def test_success_preserves_identity_and_downloads(self):
        client = FakeModrinth(self.original)
        self.run_replacement(client)
        self.assert_replaced(client)
        self.assertLess(client.events.index("UPLOAD"), client.events.index("PATCH"))
        self.assertLess(client.events.index("PATCH"), client.events.index("DELETE"))

    def test_upload_failure_keeps_original(self):
        client = FakeModrinth(self.original, "UPLOAD")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.assertEqual(client.version, self.original)
        self.assertNotIn("DELETE", client.events)

    def test_lost_upload_response_is_reconciled(self):
        client = FakeModrinth(self.original, "LOST_UPLOAD_RESPONSE")
        self.run_replacement(client)
        self.assert_replaced(client)

    def test_patch_failure_keeps_original_primary_and_can_resume(self):
        client = FakeModrinth(self.original, "PATCH")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.assertEqual(p.remote_file(client.version), p.remote_file(self.original))
        self.assertNotIn("DELETE", client.events)
        client.fail = None
        self.run_replacement(client)
        self.assert_replaced(client)
        self.assertEqual(client.events.count("UPLOAD"), 1)

    def test_cleanup_failure_resumes_without_reupload(self):
        client = FakeModrinth(self.original, "DELETE")
        with self.assertRaises(RuntimeError):
            self.run_replacement(client)
        self.assertTrue(any(row["hashes"]["sha512"] == p.sha(self.path, "sha512") for row in client.version["files"]))
        client.fail = None
        self.run_replacement(client)
        self.assert_replaced(client)
        self.assertEqual(client.events.count("UPLOAD"), 1)

    def test_unrelated_remote_file_blocks_mutation(self):
        client = FakeModrinth(self.original)
        client.version["files"].append({"filename": "other.jar", "primary": False,
                                        "hashes": {"sha512": hashlib.sha512(b"other").hexdigest()}})
        with self.assertRaisesRegex(RuntimeError, "Remote files changed"):
            self.run_replacement(client)
        self.assertEqual(client.events, ["GET"])


if __name__ == "__main__":
    unittest.main()
