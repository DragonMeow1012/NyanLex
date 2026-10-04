"""Publish the verified 1.0.0 release matrix to Modrinth.

The command is deliberately opt-in: without ``--apply`` it performs a read-only
plan. Existing target versions are replaced one at a time only after their
metadata and file have been backed up locally. A failed replacement attempts to
restore the deleted version before stopping.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
from pathlib import Path
import re
import shutil
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

from release_matrix import ROOT, VERSION, targets


API = "https://api.modrinth.com/v2"
PROJECT_ID = "VumqThI9"
FABRIC_API_ID = "P7dR8mSH"
USER_AGENT = "DragonMeow1012/NyanLex-release-publisher/1.0.0"
TOKEN_PATTERN = re.compile(r"mrp_[A-Za-z0-9]+")


def sha(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_token(path: Path) -> str:
    match = TOKEN_PATTERN.search(path.read_text(encoding="utf-8-sig"))
    if not match:
        raise RuntimeError(f"No Modrinth PAT found in {path}")
    return match.group(0)


class Modrinth:
    def __init__(self, token: str):
        self.headers = {"Authorization": token, "User-Agent": USER_AGENT}

    def request(self, method: str, route: str, *, payload=None, body=None,
                content_type=None, authenticated=True):
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        headers = dict(self.headers if authenticated else {"User-Agent": USER_AGENT})
        if content_type:
            headers["Content-Type"] = content_type
        request = Request(API + route, data=body, headers=headers, method=method)
        for attempt in range(5):
            try:
                with urlopen(request, timeout=120) as response:
                    data = response.read()
                    return json.loads(data) if data else None
            except HTTPError as error:
                detail = error.read().decode("utf-8", errors="replace")
                if error.code == 429 or 500 <= error.code < 600:
                    if attempt < 4:
                        delay = int(error.headers.get("Retry-After", 2 ** attempt))
                        time.sleep(max(1, delay))
                        continue
                raise RuntimeError(f"Modrinth {method} {route} failed ({error.code}): {detail}") from error
            except URLError as error:
                if attempt < 4:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(f"Modrinth {method} {route} failed: {error}") from error
        raise AssertionError("unreachable")

    def multipart(self, route: str, payload: dict, file_path: Path):
        boundary = "----NyanLex" + uuid.uuid4().hex
        newline = b"\r\n"
        chunks = [
            f"--{boundary}".encode(),
            b'Content-Disposition: form-data; name="data"',
            b"Content-Type: application/json; charset=utf-8",
            b"",
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            f"--{boundary}".encode(),
            (f'Content-Disposition: form-data; name="file"; '
             f'filename="{file_path.name}"').encode(),
            f"Content-Type: {mimetypes.guess_type(file_path.name)[0] or 'application/java-archive'}".encode(),
            b"",
            file_path.read_bytes(),
            f"--{boundary}--".encode(),
            b"",
        ]
        return self.request("POST", route, body=newline.join(chunks),
                            content_type=f"multipart/form-data; boundary={boundary}")


def version_number(target) -> str:
    return f"{VERSION}-{target.label}-{target.minecraft}"


def changelog(target) -> str:
    dependency = (" Requires a matching **Fabric API** installation."
                  if target.loader == "fabric" else "")
    return f"""NyanLex Translator {VERSION} for **Minecraft {target.minecraft} / {target.label}**. Install on the client only; the server does not need this mod.{dependency}

### Features

- Translate incoming chat, items, tooltips, books, interfaces and supported HUD text.
- Write in your own language, translate the draft, then review it in the normal chat bar before sending.
- Reuse saved translations; modern builds also support translation packs and selectable full-content warmup.
- Modern compatibility includes old/new FTB Quests APIs, advancement notices and supported Jade/WAILA-style object names.

### Fixes in this build

- Open Translation Settings from the settings menu, or enter `/nyanlex` in chat if another UI mod hides that entry.
- Modern Fabric/NeoForge builds can use a Google account through a separately installed Antigravity CLI, including the CLI-reported model and reasoning variants. Install it from [Google's official download page](https://antigravity.google/download).
- Google machine translation tries a compatible alternate endpoint once when the primary returns a 429 or block page.

Online translation is disabled by default on new installations. Use only the JAR matching this exact Minecraft version and loader. Translation coverage depends on how each mod renders text; text embedded in images is not translated.

[Full documentation](https://github.com/DragonMeow1012/NyanLex/blob/main/README.en.md)"""


def payload(target) -> dict:
    dependencies = ([{"project_id": FABRIC_API_ID, "dependency_type": "required"}]
                    if target.loader == "fabric" else [])
    return {
        "name": f"NyanLex Translator {VERSION} - {target.label} {target.minecraft}",
        "version_number": version_number(target),
        "changelog": changelog(target),
        "dependencies": dependencies,
        "game_versions": [target.minecraft],
        "version_type": "release",
        "loaders": [target.loader],
        "featured": False,
        "status": "listed",
        "project_id": PROJECT_ID,
        "file_parts": ["file"],
        "primary_file": "file",
        "environment": "client_only",
    }


def package_files(package_root: Path):
    result = []
    expected = {target.relative for target in targets()}
    actual = {path.relative_to(package_root).as_posix()
              for path in package_root.rglob("*.jar")}
    if actual != expected:
        raise RuntimeError(f"Package JAR set mismatch; missing={sorted(expected - actual)}, "
                           f"unexpected={sorted(actual - expected)}")
    checksum_lines = (package_root / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
    checksums = {}
    for line in checksum_lines:
        digest, relative = line.split(" *", 1)
        checksums[relative] = digest
    for target in targets():
        path = package_root / target.relative
        actual_hash = sha(path, "sha256")
        if checksums.get(target.relative) != actual_hash:
            raise RuntimeError(f"Checksum mismatch for {target.relative}")
        result.append((target, path))
    return result


def remote_file(version: dict) -> dict:
    primary = [row for row in version["files"] if row.get("primary")]
    if len(primary) > 1 or not version["files"]:
        raise RuntimeError(f"Unexpected file layout in Modrinth version {version['id']}")
    return primary[0] if primary else version["files"][0]


def metadata_matches(version: dict, target) -> bool:
    wanted = payload(target)
    dependency_fields = ("project_id", "version_id", "file_name", "dependency_type")

    def normalized_dependencies(rows):
        return sorted(tuple(row.get(field) for field in dependency_fields) for row in rows)

    return (
        version.get("name") == wanted["name"]
        and version.get("game_versions") == wanted["game_versions"]
        and version.get("loaders") == wanted["loaders"]
        and version.get("environment") == wanted["environment"]
        and normalized_dependencies(version.get("dependencies", []))
        == normalized_dependencies(wanted["dependencies"])
        and version.get("changelog") == wanted["changelog"]
    )


def backup_version(client: Modrinth, version: dict, backup_root: Path) -> Path:
    folder = backup_root / version["version_number"]
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "metadata.json").write_text(
        json.dumps(version, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    file = remote_file(version)
    target = folder / file["filename"]
    if not target.exists() or sha(target, "sha512") != file["hashes"]["sha512"]:
        request = Request(file["url"], headers={"User-Agent": USER_AGENT})
        with urlopen(request, timeout=120) as response, target.open("wb") as output:
            shutil.copyfileobj(response, output)
    if sha(target, "sha512") != file["hashes"]["sha512"]:
        raise RuntimeError(f"Backup hash mismatch for {version['version_number']}")
    return target


def restore_payload(version: dict) -> dict:
    keep = ("name", "version_number", "changelog", "dependencies", "game_versions",
            "version_type", "loaders", "featured", "status", "environment")
    result = {key: version[key] for key in keep}
    result.update(project_id=PROJECT_ID, file_parts=["file"], primary_file="file")
    return result


def publish(args) -> None:
    package_root = args.package_root.resolve()
    files = package_files(package_root)
    client = Modrinth(read_token(args.token_file))
    existing = client.request("GET", f"/project/{PROJECT_ID}/version")
    by_number = {row["version_number"]: row for row in existing}
    if len(by_number) != len(existing):
        raise RuntimeError("Duplicate remote version numbers")

    operations = []
    for target, path in files:
        remote = by_number.get(version_number(target))
        local_sha512 = sha(path, "sha512")
        if remote is None:
            action = "create"
        elif remote_file(remote)["hashes"]["sha512"] == local_sha512:
            action = "metadata" if not metadata_matches(remote, target) else "skip"
        else:
            action = "replace"
        operations.append((action, target, path, remote))

    counts = {name: sum(action == name for action, *_ in operations)
              for name in ("create", "replace", "metadata", "skip")}
    print("MODRINTH_PLAN " + " ".join(f"{key}={value}" for key, value in counts.items()))
    if not args.apply:
        return

    backup_root = args.backup_root.resolve()
    backup_root.mkdir(parents=True, exist_ok=True)

    # Create missing versions before replacing anything, so a transient failure
    # cannot reduce the existing public matrix.
    for action, target, path, _ in operations:
        if action == "create":
            client.multipart("/version", payload(target), path)
            print(f"MODRINTH_CREATED {target.key}", flush=True)

    for action, target, path, remote in operations:
        if action == "metadata":
            edit = payload(target)
            for key in ("project_id", "file_parts", "primary_file"):
                edit.pop(key)
            client.request("PATCH", f"/version/{remote['id']}", payload=edit)
            print(f"MODRINTH_UPDATED {target.key}", flush=True)
        elif action == "replace":
            old_file = backup_version(client, remote, backup_root)
            client.request("DELETE", f"/version/{remote['id']}")
            try:
                client.multipart("/version", payload(target), path)
            except Exception:
                try:
                    client.multipart("/version", restore_payload(remote), old_file)
                    print(f"MODRINTH_RESTORED {target.key}", file=sys.stderr, flush=True)
                except Exception as restore_error:
                    print(f"MODRINTH_RESTORE_FAILED {target.key}: {restore_error}",
                          file=sys.stderr, flush=True)
                raise
            print(f"MODRINTH_REPLACED {target.key}", flush=True)

    body = (ROOT / "docs" / "publishing" / "store-description.md").read_text(encoding="utf-8")
    client.request("PATCH", f"/project/{PROJECT_ID}", payload={"body": body})

    final = client.request("GET", f"/project/{PROJECT_ID}/version")
    final_by_number = {row["version_number"]: row for row in final}
    expected_numbers = {version_number(target) for target, _ in files}
    if set(final_by_number) != expected_numbers:
        raise RuntimeError("Final Modrinth version set does not match the 62-target matrix")
    for target, path in files:
        remote = final_by_number[version_number(target)]
        if remote_file(remote)["hashes"]["sha512"] != sha(path, "sha512"):
            raise RuntimeError(f"Final Modrinth hash mismatch for {target.key}")
        if not metadata_matches(remote, target):
            raise RuntimeError(f"Final Modrinth metadata mismatch for {target.key}")
    print(f"MODRINTH_PUBLISH_OK project={PROJECT_ID} versions={len(final)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--package-root", type=Path,
                        default=ROOT / "mods-jar" / "1.0.0-expanded")
    parser.add_argument("--backup-root", type=Path,
                        default=ROOT / "mods-jar" / "modrinth-backup-1.0.0")
    parser.add_argument("--apply", action="store_true",
                        help="Perform uploads, replacements and the project-body update")
    publish(parser.parse_args())


if __name__ == "__main__":
    main()
