#!/usr/bin/env python3
"""Package the verified 1.0.0 core rebuild without claiming full Gradle gates.

This command never compiles, changes JAR contents, or publishes remotely. The
authoritative build report and original public ZIP must be pinned by SHA256.
Run with --help for the required paths; output-root must not already contain
release/, packaging-report.json, or NyanLex-1.0.0-before-echo-fix.zip.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time
import zipfile
import zlib

from release_matrix import ROOT, VERSION, targets


ORIGINAL_SHA256 = "e2ed6a521d38f85d0848f4cb13382fb581241214ec68bc5ed9a2c1c3525ec968"
LOADER_COUNTS = {"fabric": 37, "neoforge": 23, "forge": 2}
ARCHIVES = {
    "NyanLex-1.0.0-Fabric.zip": ("fabric",),
    "NyanLex-1.0.0-NeoForge.zip": ("neoforge",),
    "NyanLex-1.0.0-Forge.zip": ("forge",),
    "NyanLex-1.0.0-all-versions.zip": ("fabric", "neoforge", "forge"),
}
BACKUP_NAME = "NyanLex-1.0.0-before-echo-fix.zip"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def record(path):
    return {"path": str(path), "bytes": path.stat().st_size,
            "sha256": sha256(path)}


def file_list(root):
    result = []
    for path in root.rglob("*"):
        require(not path.is_symlink(), f"Unexpected symlink: {path}")
        if path.is_file():
            result.append(path.relative_to(root).as_posix())
    return sorted(result)


def inspect_archive(path, names, expected_hashes, expected_sizes):
    """Reading every entry to EOF also validates its ZIP CRC."""
    entries = []
    with zipfile.ZipFile(path) as archive:
        require(archive.namelist() == names,
                f"ZIP entry order/list differs from the canonical set: {path}")
        for info in archive.infolist():
            require(not info.is_dir(), f"Unexpected directory entry: {info.filename}")
            require(info.compress_type == zipfile.ZIP_DEFLATED,
                    f"Unexpected compression type: {info.filename}")
            require(info.file_size == expected_sizes[info.filename],
                    f"ZIP entry size mismatch: {info.filename}")
            with archive.open(info) as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            require(digest == expected_hashes[info.filename],
                    f"ZIP entry SHA256 mismatch: {info.filename}")
            entries.append({"relative": info.filename, "bytes": info.file_size,
                            "sha256": digest, "crc32": f"{info.CRC:08x}"})
    return {**record(path), "entry_count": len(entries), "entries": entries,
            "exact_entry_list_and_order": True, "all_entry_hashes_match": True,
            "zip_crc_valid": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-root", type=Path, required=True,
                        help="The final-core-artifacts tree; never a Gradle output tree")
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--build-report-sha256", required=True)
    parser.add_argument("--original-zip", type=Path, required=True)
    parser.add_argument("--original-zip-sha256", default=ORIGINAL_SHA256)
    parser.add_argument("--output-root", type=Path, required=True,
                        help="Deliverables root; release/ and the backup are created within it")
    parser.add_argument("--evidence", type=Path, action="append", default=[],
                        help="Optional existing evidence file to reference by SHA256; not rerun")
    args = parser.parse_args()
    started = time.monotonic()
    script_path = Path(__file__).resolve()
    script_hash = sha256(script_path)
    input_root = args.input_root.resolve(strict=True)
    build_report_path = args.build_report.resolve(strict=True)
    original_zip = args.original_zip.resolve(strict=True)
    output_root = args.output_root.resolve()
    require(VERSION == "1.0.0", "This packaging layout is pinned to version 1.0.0")
    require(not input_root.is_relative_to(output_root)
            and not output_root.is_relative_to(input_root),
            "Input and output trees must be separate")
    require(sha256(build_report_path) == args.build_report_sha256,
            "Authoritative build report SHA256 differs from the supplied pin")
    require(sha256(original_zip) == args.original_zip_sha256 == ORIGINAL_SHA256,
            "Original public all-versions ZIP differs from the pinned release")
    build = json.loads(build_report_path.read_text(encoding="utf-8"))
    require(build["version"] == VERSION
            and build["method"] == "core-only incremental rebuild"
            and build["clean_full_gradle_build"] is False
            and build["runtimeTested"] is False,
            "Unexpected build method, version, or runtime claims")
    require(build["passed"] == 62 and build["failed"] == 0
            and build["canonical_target_count"] == 62
            and build["selected_target_count"] == 62
            and build["source_unchanged_during_build"] is True,
            "Build report is not the complete verified 62-target result")
    verification_hashes = build["verification_inputs_sha256"]
    for relative, expected_hash in verification_hashes.items():
        require(sha256(ROOT / relative) == expected_hash,
                f"Frozen verification input differs: {relative}")

    matrix = {target.relative: target for target in targets()}
    require(len(matrix) == 62, "Expected exactly 62 unique matrix paths")
    require(Counter(target.loader for target in matrix.values()) == LOADER_COUNTS,
            "Loader counts differ from the original release")
    rows = {row["relative"]: row for row in build["artifacts"]}
    require(len(rows) == len(build["artifacts"]) == 62 and rows.keys() == matrix.keys(),
            "Build report has missing, duplicate, or unexpected matrix targets")
    names = sorted(matrix)
    require(file_list(input_root) == names,
            "Input tree must contain only the 62 final matrix JARs")
    hashes, sizes, originals, inherited = {}, {}, {}, []
    for relative in names:
        row, target = rows[relative], matrix[relative]
        require((row["target"], row["file"], row["source_project"], row["java"])
                == (target.key, target.filename, target.source_project, target.java),
                f"Report/matrix identity mismatch: {relative}")
        require(row["method"] == build["method"]
                and row["baseline_commit"] == build["baseline_commit"]
                and row["clean_full_gradle_build"] is False
                and row["runtimeTested"] is False,
                f"Per-artifact build method mismatch: {relative}")
        for flag in ("baseline_core_matches_source", "mapped_entries_byte_identical",
                     "all_other_entries_byte_identical", "zip_crc_valid",
                     "metadata_resources_manifest_licenses_preserved"):
            require(row[flag] is True, f"Missing build guarantee {flag}: {relative}")
        require(row["feature_check"] == row["class_level_check"] == "passed",
                f"Artifact feature/class-level check did not pass: {relative}")
        mixin = row["mixin_rule_check"]
        require(mixin["new_findings"] == []
                and mixin["baseline_findings"] == mixin["current_findings"],
                f"Unresolved new or changed Mixin finding: {relative}")
        if mixin["status"] == "unchanged_baseline_findings":
            require(bool(mixin["current_findings"]), f"Empty inherited finding: {relative}")
            inherited.append({"target": target.key, **mixin})
        else:
            require(mixin["status"] == "passed" and not mixin["current_findings"],
                    f"Unexpected Mixin status: {relative}")
        path = input_root / relative
        hashes[relative], sizes[relative] = sha256(path), path.stat().st_size
        originals[relative] = row["original_sha256"]
        require((hashes[relative], sizes[relative]) == (row["sha256"], row["bytes"]),
                f"Final JAR differs from the pinned build report: {relative}")
        with zipfile.ZipFile(path) as jar:
            require(jar.testzip() is None, f"JAR CRC check failed: {relative}")
    with zipfile.ZipFile(original_zip) as archive:
        original_sizes = {info.filename: info.file_size for info in archive.infolist()}
    original_inspection = inspect_archive(original_zip, names, originals, original_sizes)
    evidence = [record(path.resolve(strict=True)) for path in args.evidence]

    output_root.mkdir(parents=True, exist_ok=True)
    for name in ("release", BACKUP_NAME, "packaging-report.json"):
        require(not (output_root / name).exists(), f"Refusing to replace existing output: {name}")
    # A fixed timestamp derived from the pinned report avoids local clock/mtime
    # changes; Python/zlib versions are recorded because compression may vary.
    build_time = datetime.fromisoformat(build["build_time_utc"]).astimezone(timezone.utc)
    zip_time = (build_time.year, build_time.month, build_time.day,
                build_time.hour, build_time.minute, build_time.second // 2 * 2)
    with tempfile.TemporaryDirectory(prefix=".core-package-", dir=output_root) as temporary:
        stage = Path(temporary)
        release = stage / "release"
        release.mkdir()
        for relative in names:
            destination = release / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(input_root / relative, destination)
            require(sha256(destination) == hashes[relative], f"Copied JAR differs: {relative}")
        archive_records = []
        for filename, loaders in ARCHIVES.items():
            selected = [name for name in names if matrix[name].loader in loaders]
            require(len(selected) == sum(LOADER_COUNTS[loader] for loader in loaders),
                    f"Wrong archive target count: {filename}")
            with zipfile.ZipFile(release / filename, "x", compression=zipfile.ZIP_DEFLATED,
                                 compresslevel=9) as archive:
                for relative in selected:
                    info = zipfile.ZipInfo(relative, zip_time)
                    info.create_system = 0
                    info.external_attr = 0x20  # DOS archive flag, independent of host umask.
                    archive.writestr(info, (release / relative).read_bytes(),
                                     compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
            inspected = inspect_archive(release / filename, selected, hashes, sizes)
            inspected["path"] = str(output_root / "release" / filename)
            inspected["relative"] = filename
            archive_records.append(inspected)
        all_hashes = {**hashes, **{row["relative"]: row["sha256"] for row in archive_records}}
        checksum_names = sorted(all_hashes, key=str.casefold)
        require(len(checksum_names) == 66, "Checksum set must contain 62 JARs and 4 ZIPs")
        checksum_bytes = ("\r\n".join(f"{all_hashes[name]} *{name}" for name in checksum_names)
                          + "\r\n").encode("utf-8")
        checksum_path = release / "SHA256SUMS.txt"
        checksum_path.write_bytes(checksum_bytes)
        parsed_checksums = {}
        for line in checksum_path.read_bytes().decode("utf-8").splitlines():
            digest, relative = line.split(" *", 1)
            require(relative not in parsed_checksums and len(digest) == 64
                    and all(character in "0123456789abcdef" for character in digest),
                    "Malformed or duplicate checksum line")
            require(sha256(release / relative) == digest, f"Checksum readback failed: {relative}")
            parsed_checksums[relative] = digest
        require(list(parsed_checksums) == checksum_names and parsed_checksums == all_hashes,
                "Checksum target set, order, or digest differs")
        expected_files = sorted([*names, *ARCHIVES, "SHA256SUMS.txt"])
        require(file_list(release) == expected_files, "Staged release tree is not the exact 67 files")
        backup = stage / BACKUP_NAME
        shutil.copyfile(original_zip, backup)
        require(sha256(backup) == ORIGINAL_SHA256, "Copied original release backup differs")
        for relative in names:
            require(sha256(input_root / relative) == hashes[relative],
                    f"Input JAR changed during packaging: {relative}")
        require(sha256(build_report_path) == args.build_report_sha256
                and sha256(original_zip) == ORIGINAL_SHA256
                and sha256(script_path) == script_hash,
                "Pinned inputs or packaging script changed during packaging")
        for relative, expected_hash in verification_hashes.items():
            require(sha256(ROOT / relative) == expected_hash,
                    f"Frozen verification input changed during packaging: {relative}")

        report = {
            "status": "passed", "scope": "Packaging verification only",
            "version": VERSION, "method": build["method"],
            "clean_full_gradle_build": False, "runtimeTested": False,
            "published_remotely": False,
            "packaging_time_utc": datetime.now(timezone.utc).isoformat(),
            "seconds": round(time.monotonic() - started, 3),
            "command": [sys.executable, str(script_path), *sys.argv[1:]],
            "packaging_script": {"path": str(script_path), "sha256": script_hash},
            "python": sys.version, "zlib_version": zlib.ZLIB_VERSION,
            "zlib_runtime_version": zlib.ZLIB_RUNTIME_VERSION,
            "zip_format": {"compression": "deflate", "level": 9,
                           "entry_timestamp_utc": list(zip_time), "create_system": 0,
                           "external_attr": 32, "directory_entries": False},
            "input_root": str(input_root), "release_root": str(output_root / "release"),
            "build_report": record(build_report_path),
            "baseline_commit": build["baseline_commit"],
            "verification_inputs_sha256": verification_hashes,
            "original_archive": original_inspection,
            "original_backup": {"path": str(output_root / BACKUP_NAME),
                                "bytes": backup.stat().st_size, "sha256": ORIGINAL_SHA256},
            "jar_count": 62, "jar_bytes": sum(sizes.values()), "loader_counts": LOADER_COUNTS,
            "archive_count": 4, "release_file_count": 67,
            "artifacts": [{"target": matrix[name].key, "relative": name,
                           "bytes": sizes[name], "sha256": hashes[name],
                           "original_sha256": originals[name],
                           "build_report_hash_matches": True, "jar_crc_valid": True}
                          for name in names],
            "archives": archive_records,
            "checksums": {"relative": "SHA256SUMS.txt", "entries": 66,
                          "bytes": len(checksum_bytes), "sha256": sha256(checksum_path),
                          "format": "lowercase SHA256, space-star, forward-slash path; UTF-8 without BOM, CRLF",
                          "order": "case-insensitive lexicographic relative path",
                          "all_files_verified": True},
            "inherited_build_findings": inherited,
            "supporting_evidence_files": evidence,
            "supporting_evidence_tests_rerun_by_packager": False,
            "all_jar_hashes_match_build_report": True,
            "all_archive_entry_lists_hashes_and_crcs_verified": True,
            "exact_release_file_set_verified": True,
            "inputs_unchanged_during_packaging": True,
            "limitations": [
                "These are the already verified core-only incremental rebuild JARs; packaging does not rebuild or modify them.",
                "Original remapped/reobfuscated adapters and resources are retained; no fresh full Gradle matrix or Minecraft runtime is claimed.",
                "Inherited Mixin findings retain their original classification and are not converted into clean passes.",
                "Referenced JVM test and review reports are separate evidence; this command only verifies package identity and integrity.",
            ],
        }
        report_file = stage / "packaging-report.json"
        report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        # Install only after every JAR, archive, checksum, and backup is verified.
        for name in ("release", BACKUP_NAME, "packaging-report.json"):
            (stage / name).rename(output_root / name)
    print(json.dumps({"status": "passed", "release_root": str(output_root / "release"),
                      "jar_count": 62, "archive_count": 4, "checksum_entries": 66,
                      "packaging_report": record(output_root / "packaging-report.json"),
                      "archives": [{key: row[key] for key in ("relative", "bytes", "sha256", "entry_count")}
                                   for row in archive_records],
                      "backup_sha256": ORIGINAL_SHA256}, indent=2))


if __name__ == "__main__":
    main()
