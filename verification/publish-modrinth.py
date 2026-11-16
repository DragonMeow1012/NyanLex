"""Publish the verified 1.0.1 release matrix to Modrinth.

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


class DownloadHashMismatch(RuntimeError):
    """The Modrinth API metadata and downloaded CDN object disagree."""


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
    legacy = target.minecraft in {"1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5"}
    intro = (f"NyanLex Translator {VERSION} for **Minecraft {target.minecraft} / {target.label}**. "
             f"Install on the client only; the server does not need this mod.{dependency}")
    if legacy:
        body = """### Version limits

This legacy build includes only the chat input translation improvements. The Translation manager and the other new features of 1.0.1 are not supported here.

Forge 1.13.2: fixed a failure to start."""
    else:
        body = """### v1.0.1 improvements

- Chat input translation now also works for commands: start the chat box with `/`, such as a party chat command. The translation is inserted at the cursor and never overwrites what you have already typed.
- Server story dialogue boxes drawn with a custom font now show the translation inside the box, keeping per-word colors.
- Floating-text nameplates, such as NPC names, are translated. Requires Minecraft 1.19.4 or newer.
- **Translation manager**: search, edit, restore or delete saved translations on the Advanced settings page. Hand-edited translations are never overwritten by automatic translation and survive clearing the cache.
- **Keep titles and dialogue in chat**: a new switch that keeps fleeting title and dialogue translations in your chat, visible only to you.
- Fixed translated player chat showing the sender's name twice on Fabric 1.19.3 and newer.
- Switching to the original with `G` now shows the English text, even in interfaces where another mod ships its own Chinese. No new translation requests are sent while it is on.
- Custom fonts that lack Chinese characters now fall back to the game's built-in glyphs instead of showing boxes.
- Text that appears letter by letter is translated once, after it finishes, which greatly reduces requests and token use.
- Icons in item names and descriptions no longer turn into boxes after translation.
- Retranslating the whole interface now also retranslates nameplates, and text that is already translated is no longer sent again as if it were the original.
- On Minecraft 1.17 through 1.21.8 the default key for translating the screen is now `\\` instead of `P`, which is also the vanilla Social Interactions key; newer versions keep `P`, and key bindings you have already saved are unchanged.
- The Claude provider, with its thinking-strength and Fast mode settings, is now available on every build for Minecraft 1.17 and newer.
- Fixed a crash on startup on Fabric 1.17 through 1.19.4 with Fabric Loader 0.16.0 through 0.16.13.
- Fixed a crash on startup on Fabric 26.3 with Fabric Loader 0.19.3 and 0.19.4.
- Fixed NeoForge 1.20.1, which did not work correctly, and Fabric 1.17, which could not start.
- Fixed Forge 1.13.2, which could not start.
- Plain action bar text is now translated on Fabric 1.17 through 1.19.
- Held item names on NeoForge 1.20.1 through 1.21.8, and the scoreboard on some of those versions, are now translated.
- Tooltips that do not belong to an item are now translated on Fabric 1.17 through 1.19.4.
- Typing in the settings search box no longer triggers hotkeys on Minecraft 1.20 through 1.20.4.
- A tooltip containing an empty line can no longer switch tooltip translation off for the rest of the session."""
    return f"""{intro}

{body}

Online translation is disabled by default on new installations. Use only the JAR matching this exact Minecraft version and loader.

Hands-on play testing was done on Minecraft 26.1.2 / Fabric, and automated in-world tests also ran on Fabric 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.21.1, 1.21.11, 26.2 and 26.3 and on NeoForge 1.21.1, 26.2 and 26.3. The other builds passed build, static and startup checks, but were not tested in a world.

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


def cache_safe_filename(path: Path) -> str:
    """Return a deterministic URL-safe name for a stale same-name CDN object."""
    return f"{path.stem}-{sha(path, 'sha256')[:12]}{path.suffix}"


def published_file_matches(file: dict, path: Path) -> bool:
    return (
        file["filename"] in (path.name, cache_safe_filename(path))
        and file["hashes"]["sha512"] == sha(path, "sha512")
        and file["size"] == path.stat().st_size
    )


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
        and version.get("version_type") == wanted["version_type"]
        and version.get("status") == wanted["status"]
        and version.get("featured") == wanted["featured"]
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
        raise DownloadHashMismatch(
            "Downloaded replacement differs from the verified JAR; temporary file retained")


def replace_version_file(client: Modrinth, target, path: Path,
                         remote: dict, backup_root: Path) -> None:
    """Keep one verified JAR under its canonical or cache-safe filename."""
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
    cache_safe_name = cache_safe_filename(path)
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
    allowed = {
        (old["filename"], old_hash),
        (path.name, new_hash),
        (cache_safe_name, new_hash),
        (staging_name, staging_hash),
    }

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
        upload_error = None
        try:
            client.multipart(route + "/file", {}, local, filename=filename)
        except RuntimeError as error:
            # A completed upload can still race the version GET or the request
            # can time out after Modrinth accepted it. Confirm remote state
            # before deciding whether the upload failed.
            upload_error = error
        for attempt in range(8):
            current = inspect(refresh=attempt > 0)
            uploaded = find(current, filename, digest)
            if uploaded and uploaded["size"] == local.stat().st_size:
                return current
            time.sleep(min(attempt + 1, 5))
        if upload_error is not None:
            raise upload_error
        raise RuntimeError("Replacement upload verification failed")

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

    def ensure_verified_staging(current):
        if not find(current, staging_name, staging_hash):
            current = upload(staging, staging_name, staging_hash)
        verify_file_download(find(current, staging_name, staging_hash), staging_hash)
        return current

    current = inspect()
    canonical = find(current, path.name, new_hash)
    cache_safe = find(current, cache_safe_name, new_hash)
    if not canonical and not cache_safe:
        current = ensure_verified_staging(current)
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
    canonical = find(current, path.name, new_hash)
    cache_safe = find(current, cache_safe_name, new_hash)
    final_file = cache_safe or canonical
    if not final_file or final_file["size"] != path.stat().st_size or not metadata_matches(current, target):
        raise RuntimeError("Final canonical file or metadata not confirmed; temporary file retained")
    try:
        verify_file_download(final_file, new_hash)
    except DownloadHashMismatch:
        if final_file["filename"] != path.name:
            raise
        # Modrinth's object path contains the filename. If a previously used
        # canonical path remains stale, keep a freshly downloaded-and-verified
        # staging JAR available, remove only the bad path, then publish the
        # exact bytes under a deterministic content-addressed filename.
        current = ensure_verified_staging(current)
        current = remove_file(new_hash)
        if not find(current, staging_name, staging_hash):
            raise RuntimeError("Verified staging file was lost during CDN fallback")
        current = upload(path, cache_safe_name, new_hash)
        final_file = find(current, cache_safe_name, new_hash)
        if not final_file or final_file["size"] != path.stat().st_size:
            raise RuntimeError("Cache-safe replacement upload was not confirmed")
        verify_file_download(final_file, new_hash)
    final_name = final_file["filename"]
    for row in current["files"]:
        if row["filename"] != final_name or row["hashes"]["sha512"] != new_hash:
            digest = row["hashes"]["sha512"]
            current = remove_file(digest)
    final = inspect()
    if len(final["files"]) != 1 or not find(final, final_name, new_hash):
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
        if remote is None:
            action = "create"
        elif (len(remote["files"]) == 1
              and published_file_matches(remote_file(remote), path)):
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
        if len(remote["files"]) != 1 or not published_file_matches(remote_file(remote), path):
            raise RuntimeError(f"Final Modrinth hash mismatch for {target.key}")
        if not metadata_matches(remote, target):
            raise RuntimeError(f"Final Modrinth metadata mismatch for {target.key}")
        verify_file_download(remote_file(remote), sha(path, "sha512"))
    print("MODRINTH_DOWNLOADS_OK", flush=True)
    print(f"MODRINTH_PUBLISH_OK project={PROJECT_ID} versions={len(final)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--package-root", type=Path,
                        default=ROOT / "mods-jar" / "1.0.1-expanded")
    parser.add_argument("--backup-root", type=Path,
                        default=ROOT / "mods-jar" / "modrinth-backup-1.0.1")
    parser.add_argument("--apply", action="store_true",
                        help="Perform uploads, replacements and the project-body update")
    publish(parser.parse_args())


if __name__ == "__main__":
    main()
