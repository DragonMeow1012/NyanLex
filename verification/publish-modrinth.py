"""Publish the verified 1.0.0 release matrix to Modrinth.

The command is deliberately opt-in: without ``--apply`` it performs a read-only
plan. Existing version IDs and download counts are preserved. A temporary JAR
with identical entries keeps each version downloadable while its canonical
filename is replaced. Final files retain the canonical name and exact bytes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
from pathlib import Path
import re
import shutil
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid
import zipfile

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
    def __init__(self, token: str, api: str = API):
        self.headers = {"Authorization": token, "User-Agent": USER_AGENT}
        self.api = api

    def request(self, method: str, route: str, *, payload=None, body=None,
                content_type=None, authenticated=True):
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        headers = dict(self.headers if authenticated else {"User-Agent": USER_AGENT})
        if content_type:
            headers["Content-Type"] = content_type
        request = Request(self.api + route, data=body, headers=headers, method=method)
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

    def multipart(self, route: str, payload: dict, file_path: Path, *, filename=None):
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
             f'filename="{filename or file_path.name}"').encode(),
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

- October 7 refresh: all 62 JARs rebuilt from source with the .02 echo-fix baseline (1.0.0-echo-fix.20261006.2). Repeated unchanged results stop resubmitting at the echo threshold; keep-original decisions survive restarts, and already-Chinese server notices bypass unnecessary requests.
- Incoming chat has three modes on modern and legacy builds: show translations in order, show translations when ready, or show the original immediately and add its translation in place (default). Late translations preserve message position and age; cleared messages stay cleared.
- Restored ordered delivery and fixed mouse/keyboard cycling and saving of the modern chat-mode button.
- Verified 62 source builds, 27,223 final-JAR core/echo checks and 2,907 modern settings-panel checks. Minecraft 26.1.2 / Fabric passed 20 in-game checks in a local world with a controlled translation service. This does not claim in-game testing of every target or public multiplayer servers.
- Modern builds hide Antigravity by default. Enable it in Advanced settings after reviewing account, privacy and service risks; official sign-in does not establish third-party integration authorization.
- CLI translations use dedicated profiles, untrusted-text boundaries, tool restrictions and bounded process handling. Unknown remaining allowance does not block translation; service-reported CLI limits pause requests.
- Antigravity uses an official pre-tool denial hook. Live testing covered CLI 1.2.16 and selected scenarios; this is not an operating-system sandbox or an all-version guarantee.
- Antigravity and Gemini notices have independent "Don't show again" checkboxes. API-key mode switches without a popup and preserves the current model; notice buttons use the correct confirmation labels.
- Do-not-translate terms appear first in their settings section. Remote API connections require HTTPS, local loopback HTTP remains supported, and automatic redirects are disabled.
- Open Translation Settings from the settings menu, or enter `/nyanlex` in chat if another UI mod hides that entry.
- Optional Google sign-in uses a separately installed Antigravity CLI. Install it from [Google's official download page](https://antigravity.google/download).
- Google machine translation tries a compatible alternate endpoint once when the primary returns a 429 or block page.
- Existing API-key rotation and individual key cooldown behavior are retained.

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


def edit_payload(target) -> dict:
    result = payload(target)
    for key in ("project_id", "file_parts", "primary_file"):
        result.pop(key)
    return result


def verify_file_download(file: dict, expected_sha512: str) -> None:
    request = Request(file["url"], headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=120) as response:
        digest = hashlib.file_digest(response, "sha512").hexdigest()
    if digest != expected_sha512:
        raise RuntimeError("Downloaded replacement differs from the verified JAR; temporary file retained")


def replace_version_file(client: Modrinth, target, path: Path,
                         remote: dict, backup_root: Path) -> None:
    """Keep one downloadable JAR while restoring the canonical filename."""
    route = f"/version/{remote['id']}"
    backup_metadata = backup_root / remote["version_number"] / "metadata.json"
    if backup_metadata.exists():
        original = json.loads(backup_metadata.read_text(encoding="utf-8"))
        if original["id"] != remote["id"]:
            raise RuntimeError("Backup belongs to another version; use a fresh backup root")
    else:
        original = remote
    if len(original["files"]) != 1:
        raise RuntimeError("Replacement requires a backup of the original single-file version")
    backup_version(client, original, backup_root)
    old = remote_file(original)
    old_hash = old["hashes"]["sha512"]
    new_hash = sha(path, "sha512")
    staging_name = f"{path.stem}-uploading-{sha(path, 'sha256')[:12]}{path.suffix}"
    staging = backup_metadata.parent / "staging" / staging_name
    staging.parent.mkdir(exist_ok=True)
    shutil.copy2(path, staging)
    # A distinct ZIP comment gives deletion-by-hash an unambiguous target.
    # Every executable class, resource, manifest and nested JAR stays identical.
    with zipfile.ZipFile(staging, "a") as jar:
        jar.comment = jar.comment + b"\nNyanLex temporary filename replacement\n"
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(staging) as staged:
        if source.namelist() != staged.namelist() or staged.testzip() is not None:
            raise RuntimeError("Temporary upload has invalid ZIP entries")
        if any(source.read(name) != staged.read(name) for name in source.namelist()):
            raise RuntimeError("Temporary upload changed JAR contents")
    staging_hash = sha(staging, "sha512")
    if staging_hash in (old_hash, new_hash):
        raise RuntimeError("Temporary upload must have a distinct file hash")
    allowed = {(old["filename"], old_hash), (path.name, new_hash), (staging_name, staging_hash)}

    def inspect(*, refresh=False):
        if refresh:
            # File deletion clears the server cache before committing its DB
            # transaction. An empty version edit clears it after the commit.
            client.request("PATCH", route, payload={})
        current = client.request("GET", route)
        if current["project_id"] != PROJECT_ID or current["version_number"] != version_number(target):
            raise RuntimeError("Remote version identity changed")
        if not current["files"] or any((row["filename"], row["hashes"]["sha512"]) not in allowed
                                       for row in current["files"]):
            raise RuntimeError("Remote files changed since backup")
        if len({row["hashes"]["sha512"] for row in current["files"]}) != len(current["files"]):
            raise RuntimeError("Ambiguous duplicate file hashes")
        return current

    def find(current, filename, digest):
        return next((row for row in current["files"]
                     if row["filename"] == filename and row["hashes"]["sha512"] == digest), None)

    def upload(local, filename, digest):
        try:
            client.multipart(route + "/file", {}, local, filename=filename)
        except RuntimeError:
            if not find(inspect(), filename, digest):
                raise
        current = inspect()
        uploaded = find(current, filename, digest)
        if not uploaded or uploaded["size"] != local.stat().st_size:
            raise RuntimeError("Replacement upload verification failed")
        return current

    def remove_file(digest):
        try:
            client.request("DELETE", f"/version_file/{digest}?algorithm=sha512&version_id={remote['id']}")
        except RuntimeError:
            current = inspect(refresh=True)
            if any(row["hashes"]["sha512"] == digest for row in current["files"]):
                raise
        else:
            current = inspect(refresh=True)
        if any(row["hashes"]["sha512"] == digest for row in current["files"]):
            raise RuntimeError("Replacement file deletion not confirmed")
        return current

    current = inspect()
    if not find(current, path.name, new_hash):
        if not find(current, staging_name, staging_hash):
            current = upload(staging, staging_name, staging_hash)
        verify_file_download(find(current, staging_name, staging_hash), staging_hash)
        # Remove the old filename/hash before uploading the exact final bytes.
        # This avoids both same-name conflicts and ambiguous same-hash deletion.
        if find(current, old["filename"], old_hash):
            current = remove_file(old_hash)
        if len(current["files"]) != 1 or not find(current, staging_name, staging_hash):
            raise RuntimeError("Original file removal not confirmed")
        current = upload(path, path.name, new_hash)
    if not metadata_matches(current, target):
        client.request("PATCH", route, payload=edit_payload(target))
        current = inspect()
    final_file = find(current, path.name, new_hash)
    if not final_file or final_file["size"] != path.stat().st_size or not metadata_matches(current, target):
        raise RuntimeError("Final canonical file or metadata not confirmed; temporary file retained")
    verify_file_download(final_file, new_hash)
    for row in current["files"]:
        if row["filename"] != path.name or row["hashes"]["sha512"] != new_hash:
            digest = row["hashes"]["sha512"]
            current = remove_file(digest)
    final = inspect()
    if len(final["files"]) != 1 or not find(final, path.name, new_hash):
        raise RuntimeError("Replacement file cleanup failed")
    if final["downloads"] < original["downloads"]:
        raise RuntimeError("Version download count decreased")


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
        elif (len(remote["files"]) == 1
              and remote_file(remote)["filename"] == path.name
              and remote_file(remote)["hashes"]["sha512"] == local_sha512):
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
            client.request("PATCH", f"/version/{remote['id']}", payload=edit_payload(target))
            print(f"MODRINTH_UPDATED {target.key}", flush=True)
        elif action == "replace":
            replace_version_file(client, target, path, remote, backup_root)
            print(f"MODRINTH_REPLACED {target.key}", flush=True)

    body = (ROOT / "docs" / "publishing" / "store-description.md").read_text(encoding="utf-8")
    client.request("PATCH", f"/project/{PROJECT_ID}", payload={"body": body})
    project = client.request("GET", f"/project/{PROJECT_ID}")
    if project["body"] != body:
        raise RuntimeError("Final Modrinth project description mismatch")

    disclosure_client = Modrinth(read_token(args.token_file), "https://api.modrinth.com/v3")
    route = f"/project/{PROJECT_ID}/disclosures"
    before = disclosure_client.request("GET", route)
    backup_path = backup_root / "disclosures-before.json"
    if not backup_path.exists():
        backup_path.write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding="utf-8")
    edit = json.loads((ROOT / "docs/publishing/modrinth-disclosures.json").read_text(encoding="utf-8"))
    # Keep the existing consent model and other disclosures; add the newly
    # documented recipient to the existing translation-data disclosure.
    for row in before["disclosures"]:
        if row["type"] == "telemetry" and not row.get("deleted_at"):
            data = [entry.replace(
                "or ChatGPT/Codex through the locally installed Codex CLI.",
                "ChatGPT/Codex through the locally installed Codex CLI, or Google/Antigravity through the locally installed Antigravity CLI.")
                for entry in row["data_collected"]]
            if data != row["data_collected"]:
                edit["set"].append({"type": "telemetry", "consent": row["consent"], "data_collected": data})
    disclosure_client.request("PATCH", route, payload=edit)
    after = disclosure_client.request("GET", route)
    by_type = {row["type"]: row for row in after["disclosures"] if not row.get("deleted_at")}
    for expected in edit["set"]:
        if any(by_type.get(expected["type"], {}).get(key) != value for key, value in expected.items()):
            raise RuntimeError(f"Final Modrinth disclosure mismatch: {expected['type']}")
    changed_types = {row["type"] for row in edit["set"]}
    metadata_keys = {"updated_at", "updated_by", "set_by_moderator", "lock_status", "deleted_at"}
    for previous in before["disclosures"]:
        if previous["type"] not in changed_types and not previous.get("deleted_at"):
            expected = {key: value for key, value in previous.items() if key not in metadata_keys}
            if any(by_type.get(previous["type"], {}).get(key) != value for key, value in expected.items()):
                raise RuntimeError(f"Unrelated Modrinth disclosure changed: {previous['type']}")
    print("MODRINTH_DESCRIPTION_AND_DISCLOSURES_OK", flush=True)

    final = client.request("GET", f"/project/{PROJECT_ID}/version")
    final_by_number = {row["version_number"]: row for row in final}
    expected_numbers = {version_number(target) for target, _ in files}
    if set(final_by_number) != expected_numbers:
        raise RuntimeError("Final Modrinth version set does not match the 62-target matrix")
    for target, path in files:
        remote = final_by_number[version_number(target)]
        if (len(remote["files"]) != 1 or remote_file(remote)["filename"] != path.name
                or remote_file(remote)["hashes"]["sha512"] != sha(path, "sha512")):
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
