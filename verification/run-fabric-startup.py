"""Launch final Fabric release JARs in isolated, offline production clients.

Uses Mojang's original client/libraries and Fabric Meta's production launch
profile, never Loom's named development output. A separate test-only Java agent
must report real title-screen rendering. No user launcher/account files are read.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time
import urllib.request
import uuid
from zipfile import ZipFile

from release_matrix import ROOT, targets

WORK = ROOT / ".localtest/startup"
CACHE = WORK / "cache"
INSTALL = Path("C:/Users/DragonMeow/curseforge/minecraft/Install")
GRADLE_HOMES = [ROOT / ".gradle-agent-home", ROOT / ".gradle-agent-home/fabric-modern",
                ROOT / ".gradle-agent-home/neoforge-ports", Path.home() / ".gradle"]
LOOM_ROOTS = [root / "caches/fabric-loom" for root in GRADLE_HOMES]
ASSET_ROOTS = [INSTALL / "assets"] + [root / "caches/neoformruntime/assets" for root in GRADLE_HOMES]
MAVEN = "https://maven.fabricmc.net/"
USER_AGENT = "NyanLex-startup-verification/1.0.0 (https://github.com/DragonMeow1012/NyanLex)"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path, algorithm="sha1"):
    value = hashlib.new(algorithm)
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def json_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def fetch_bytes(url):
    require(url.startswith("https://"), "Only HTTPS download URLs are allowed")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except (OSError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def artifact(url, destination, sha1=None, size=None, candidates=()):
    """Hash-check cached/downloaded artifacts; never mutate read-only candidates."""
    def valid(path):
        return path.is_file() and (size is None or path.stat().st_size == size) and (
            sha1 is None or digest(path) == sha1)

    if valid(destination):
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        for candidate in candidates:
            if valid(candidate):
                shutil.copyfile(candidate, temporary)
                break
        else:
            temporary.write_bytes(fetch_bytes(url))
        require(valid(temporary), "Downloaded artifact failed integrity check: " + destination.name)
        temporary.replace(destination)
    finally:
        if temporary.exists():
            temporary.unlink()
    return destination


def metadata(mc):
    for root in LOOM_ROOTS:
        for name in ("minecraft-info.json", "mojang_minecraft_info.json"):
            path = root / mc / name
            if path.is_file():
                info = json.loads(path.read_text(encoding="utf-8"))
                if info.get("id") == mc:
                    return info
    cached = CACHE / "versions" / mc / "version.json"
    if cached.is_file():
        return json.loads(cached.read_text(encoding="utf-8"))
    manifest = json.loads(fetch_bytes("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    row = next(row for row in manifest["versions"] if row["id"] == mc)
    path = artifact(row["url"], cached, row["sha1"])
    return json.loads(path.read_text(encoding="utf-8"))


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8-sig").splitlines()
                if "=" in line and not line.strip().startswith("#"))


def pins(target):
    if target.expanded:
        spec = json.loads((ROOT / target.project / "targets.json").read_text(encoding="utf-8-sig"))[target.minecraft]
        loader = "0.16.10" if target.project.endswith("fabric-legacy") else "0.19.3"
        return loader, spec["fabricApi"]
    props = properties(ROOT / target.project / "gradle.properties")
    return props["loader_version"], props.get("fabric_api_version", props.get("fabric_version"))


def profile(mc, loader):
    path = CACHE / "profiles" / (mc + "-" + loader + ".json")
    if not path.is_file():
        info = json.loads(fetch_bytes(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json"))
        require(info.get("inheritsFrom") == mc, "Fabric profile target mismatch")
        json_write(path, info)
    return json.loads(path.read_text(encoding="utf-8"))


def allowed(rules):
    if not rules:
        return True
    result = False
    for rule in rules:
        operating = rule.get("os", {})
        matched = operating.get("name", "windows") == "windows"
        if "arch" in operating:
            matched &= bool(re.fullmatch(operating["arch"], "x86_64"))
        if "version" in operating:
            matched &= bool(re.search(operating["version"], platform.version()))
        for feature, value in rule.get("features", {}).items():
            matched &= value is False  # No demo, login, Quick Play, or custom resolution features.
        if matched:
            result = rule["action"] == "allow"
    return result


def maven_path(coordinate):
    parts = coordinate.split(":")
    require(3 <= len(parts) <= 4, "Unexpected Maven coordinate")
    group, name, version = parts[:3]
    suffix = "-" + parts[3] if len(parts) == 4 else ""
    return f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{suffix}.jar"


def library_candidates(relative, coordinate):
    values = [INSTALL / "libraries" / relative]
    parts = coordinate.split(":")
    if len(parts) >= 3:
        for root in GRADLE_HOMES:
            module = root / "caches/modules-2/files-2.1" / parts[0] / parts[1] / parts[2]
            if module.is_dir():
                values.extend(module.glob("*/" + Path(relative).name))
    return values


def library(row, override=None):
    item = override or row.get("downloads", {}).get("artifact")
    if item:
        relative, url, sha1, size = item["path"], item["url"], item.get("sha1"), item.get("size")
    else:
        relative = maven_path(row["name"])
        url = row.get("url", MAVEN).rstrip("/") + "/" + relative
        sha1 = row.get("sha1")
        if not sha1:
            sha1 = fetch_bytes(url + ".sha1").decode("ascii").strip().split()[0]
        require(re.fullmatch("[0-9a-fA-F]{40}", sha1), "Invalid Maven SHA1 response")
        size = row.get("size")
    destination = CACHE / "libraries" / relative
    require(destination.resolve().is_relative_to((CACHE / "libraries").resolve()), "Unsafe library path")
    return artifact(url, destination, sha1, size, library_candidates(relative, row["name"]))


def extract_natives(jar, destination):
    with ZipFile(jar) as archive:
        for item in archive.infolist():
            if item.filename.lower().endswith((".dll", ".jnilib", ".dylib", ".so")):
                # Windows natives are flattened; never extract archive-controlled paths.
                name = Path(item.filename).name
                require(name not in ("", ".", ".."), "Invalid native library name")
                for folder in (destination, destination / "java"):
                    folder.mkdir(parents=True, exist_ok=True)
                    (folder / name).write_bytes(archive.read(item))


def assets(info):
    index = info["assetIndex"]
    root = CACHE / "assets"
    index_path = artifact(index["url"], root / "indexes" / (index["id"] + ".json"), index["sha1"],
                          index.get("size"), [base / "indexes" / (index["id"] + ".json") for base in ASSET_ROOTS])
    content = json.loads(index_path.read_text(encoding="utf-8"))
    objects = {value["hash"]: value for value in content["objects"].values()}

    def obtain(value):
        hashed = value["hash"]
        relative = f"objects/{hashed[:2]}/{hashed}"
        return artifact(f"https://resources.download.minecraft.net/{hashed[:2]}/{hashed}", root / relative,
                        hashed, value["size"], [base / relative for base in ASSET_ROOTS])

    with ThreadPoolExecutor(max_workers=12) as executor:
        list(executor.map(obtain, objects.values()))
    if content.get("virtual"):
        for name, value in content["objects"].items():
            output = (root / "virtual" / index["id"] / name).resolve()
            require(output.is_relative_to((root / "virtual" / index["id"]).resolve()), "Unsafe virtual asset path")
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / "objects" / value["hash"][:2] / value["hash"], output)
    return root


def arguments(rows, replacements):
    result = []
    for row in rows:
        if isinstance(row, dict):
            if not allowed(row.get("rules")):
                continue
            row = row["value"]
        for value in row if isinstance(row, list) else [row]:
            for key, replacement in replacements.items():
                value = value.replace("${" + key + "}", str(replacement))
            require("${" not in value, "Unsupported launcher placeholder: " + value)
            result.append(value)
    return result


def java_for(major, override):
    if override:
        return Path(override)
    roots = {8: Path("C:/Program Files/Java/jre1.8.0_471"),
             16: Path("C:/Users/DragonMeow/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"),
             17: Path("C:/Users/DragonMeow/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"),
             21: Path("C:/Program Files/Java/jdk-21"), 25: Path("C:/Program Files/Java/jdk-25")}
    return roots[major] / "bin/java.exe"


def check_package_jar(package, target):
    path = (package / target.relative).resolve()
    require(path.is_relative_to(package), "Package artifact escapes package root")
    rows = {}
    for line in (package / "SHA256SUMS.txt").read_text(encoding="utf-8-sig").splitlines():
        matched = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        require(matched is not None, "Malformed package checksum line")
        require(matched[2] not in rows, "Duplicate package checksum entry")
        rows[matched[2]] = matched[1]
    require(len(rows) == 66, "Expected the complete62-JAR/four-ZIP release package")
    require(target.relative in rows and path.is_file(), "Missing final packaged artifact: " + target.key)
    require(digest(path, "sha256") == rows[target.relative], "Final package JAR checksum mismatch")
    require(digest(target.jar, "sha256") == rows[target.relative], "Package JAR is stale relative to verified build")
    return path, rows[target.relative]


def launch(target, options):
    require(target.loader == "fabric", "This launcher only accepts Fabric targets")
    package = Path(options.package_root).resolve()
    final_jar, expected = check_package_jar(package, target)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run = WORK / "fabric" / target.minecraft / stamp
    game = run / "game"
    mods, natives = game / "mods", run / "natives"
    mods.mkdir(parents=True)
    copied = mods / final_jar.name
    shutil.copyfile(final_jar, copied)
    loader, api = pins(target)
    info, fabric = metadata(target.minecraft), profile(target.minecraft, loader)
    api_jar = library(dict(name="net.fabricmc.fabric-api:fabric-api:" + api, url=MAVEN))
    shutil.copyfile(api_jar, mods / api_jar.name)
    client_info = info["downloads"]["client"]
    client = artifact(client_info["url"], CACHE / "versions" / target.minecraft / "client.jar",
                      client_info["sha1"], client_info.get("size"),
                      [root / target.minecraft / "minecraft-client.jar" for root in LOOM_ROOTS])
    # Fabric's profile takes precedence for dependencies it explicitly supplies.
    combined = {}
    for row in info["libraries"] + fabric["libraries"]:
        identity = ":".join(row["name"].split(":")[:2]) + ":" + ":".join(row["name"].split(":")[3:])
        combined[identity] = row
    classpath = []
    for row in combined.values():
        if not allowed(row.get("rules")):
            continue
        if row.get("downloads", {}).get("artifact") or "downloads" not in row:
            downloaded = library(row)
            classpath.append(str(downloaded))
            extract_natives(downloaded, natives)
        classifier = row.get("natives", {}).get("windows")
        if classifier:
            classifier = classifier.replace("${arch}", "64")
            downloaded = library(row, row["downloads"]["classifiers"][classifier])
            extract_natives(downloaded, natives)
    classpath.append(str(client))
    print("PREPARING_ASSETS", target.key, flush=True)
    asset_root = assets(info)
    java = java_for(info.get("javaVersion", {}).get("majorVersion", target.java), options.java)
    require(java.is_file(), "Required Java runtime is unavailable: " + str(java))
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=15)
    java_description = (version.stderr or version.stdout).splitlines()[0]
    agent = Path(options.agent_jar).resolve()
    require(agent.is_file(), "Build the shared title-readiness agent first")
    report_path = run / "readiness.json"
    replacements = dict(auth_player_name="NyanLexSmoke", version_name=target.minecraft,
                        game_directory=game, assets_root=asset_root, assets_index_name=info["assetIndex"]["id"],
                        auth_uuid=uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:NyanLexSmoke").hex,
                        auth_access_token="0", user_type="legacy", user_properties="{}", version_type="release",
                        clientid="", auth_xuid="", natives_directory=natives, launcher_name="NyanLexVerifier",
                        launcher_version="1.0", classpath=os.pathsep.join(classpath),
                        library_directory=CACHE / "libraries", classpath_separator=os.pathsep)
    jvm = arguments(info.get("arguments", {}).get("jvm", []), replacements)
    if not jvm:
        jvm = ["-Djava.library.path=" + str(natives), "-cp", replacements["classpath"]]
    jvm += arguments(fabric.get("arguments", {}).get("jvm", []), replacements)
    game_args = info.get("arguments", {}).get("game")
    if game_args is None:
        game_args = info["minecraftArguments"].split()
    command = [str(java), "-Xmx2G", "-javaagent:" + str(agent),
               "-Dnyanlex.smoke.report=" + str(report_path), "-Dnyanlex.smoke.jar=" + str(copied),
               "-Dnyanlex.smoke.timeoutSeconds=" + str(options.timeout)] + jvm + [fabric["mainClass"]]
    command += arguments(game_args, replacements) + arguments(fabric.get("arguments", {}).get("game", []), replacements)
    # Tiny isolated display; no multiplayer/Quick Play flags or credentials.
    command += ["--width", "854", "--height", "480"]
    report = dict(target=target.key, minecraft=target.minecraft, loader=loader, fabricApi=api,
                  java=java_description, javaExecutable=str(java), requiredJava=target.java,
                  jar=str(final_jar), copiedJar=str(copied), sha256=expected, runDirectory=str(run),
                  production=True, runtimeTested=False, titleRendered=False, timeoutSeconds=options.timeout)
    json_write(run / "launch.json", dict(report, command=command))
    if options.prepare_only:
        print("PREPARED", target.key, run, flush=True)
        return report
    started = time.monotonic()
    print("STARTING_CLIENT", target.key, run, flush=True)
    with (run / "console.log").open("wb") as log:
        process = subprocess.Popen(command, cwd=game, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            code = process.wait(timeout=options.timeout + 30)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            code = -1
            report["error"] = "Client exceeded the bounded startup deadline"
    report.update(exitCode=code, seconds=round(time.monotonic() - started, 2))
    if report_path.is_file():
        report["readiness"] = json.loads(report_path.read_text(encoding="utf-8"))
        report["titleRendered"] = report["readiness"].get("titleRendered") is True
    report["runtimeTested"] = report["titleRendered"] and code == 0
    require(digest(copied, "sha256") == expected, "Test modified the release JAR")
    json_write(run / "result.json", report)
    print("CLIENT_RESULT", target.key, "PASS" if report["runtimeTested"] else "FAIL", run, flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", action="append", required=True, help="Exact fabric/<MC> target; repeatable")
    parser.add_argument("--package-root", required=True)
    parser.add_argument("--agent-jar", required=True)
    parser.add_argument("--java", help="Override the Java executable for this batch")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--prepare-only", action="store_true", help="Download/prepare only; never counted as runtime-tested")
    options = parser.parse_args()
    require(30 <= options.timeout <= 900, "Timeout must be30–900 seconds")
    known = {row.key: row for row in targets() if row.loader == "fabric"}
    require(set(options.target) <= set(known), "Unknown Fabric target")
    require(len(options.target) == len(set(options.target)), "Duplicate requested target")
    results = []
    for key in options.target:
        try:
            results.append(launch(known[key], options))
        except Exception as error:
            print("CLIENT_ERROR", key, type(error).__name__, str(error), flush=True)
            results.append(dict(target=key, runtimeTested=False, titleRendered=False, error=str(error)))
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = WORK / ("fabric-results-" + stamp + ".json")
    json_write(output, dict(results=results, prepareOnly=options.prepare_only))
    print("FABRIC_STARTUP_REPORT", output, flush=True)
    return int(not options.prepare_only and any(not row["runtimeTested"] for row in results))


if __name__ == "__main__":
    raise SystemExit(main())
