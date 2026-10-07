#!/usr/bin/env python3
"""Verify Java-8 legacy echo behavior against a real source tree or an exact release JAR.

All translation and HTTP transports are in-process fakes. Dependencies are supplied
as local files; this runner never downloads anything or contacts a provider.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
MODULES = ("fabric1144", "fabric1152", "fabric1165", "forge1122", "forge1132")


def existing_file(path, label):
    path = path.expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"{label} is not a file: {path}")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True, help="JDK 9 or newer; uses javac --release 8")
    parser.add_argument("--gson", type=Path, required=True, help="local Gson JAR")
    parser.add_argument("--junit", type=Path, required=True, help="local JUnit console standalone JAR")
    parser.add_argument("--module", choices=MODULES, default="fabric1144")
    parser.add_argument("--jar", type=Path, help="test production classes from this exact release JAR instead of source")
    parser.add_argument("--output-dir", type=Path, default=REPO / "build/legacy-echo-verification")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    java_home = args.java_home.expanduser().resolve()
    java = existing_file(java_home / "bin" / ("java" + suffix), "java")
    javac = existing_file(java_home / "bin" / ("javac" + suffix), "javac")
    gson = existing_file(args.gson, "Gson")
    junit = existing_file(args.junit, "JUnit")
    jar = existing_file(args.jar, "release JAR") if args.jar else None
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest() if jar else None
    output = args.output_dir.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=args.module + "-", dir=output))
    classes = work / "classes"
    classes.mkdir()
    package = "com.dragonmeow.nyanlex.forgelegacy" if args.module.startswith("forge") else "com.dragonmeow.nyanlex.legacy"
    original_package = "com.dragonmeow.nyanlex.legacy"
    inputs = [HERE / "legacy/com/dragonmeow/nyanlex/legacy/LegacyEchoPortRegressionTest.java",
              REPO / "verification/legacy-core/com/dragonmeow/nyanlex/legacy/InlineLegacyCoreSimulation.java"]
    generated = work / "generated"
    generated.mkdir()
    tests = []
    for source in inputs:
        text = existing_file(source, "test source").read_text(encoding="utf-8")
        if package != original_package:
            text = text.replace(original_package, package)
        target = generated / source.name
        target.write_text(text, encoding="utf-8")
        tests.append(target)

    commands = {}

    def execute(name, command):
        command = [str(part) for part in command]
        commands[name] = command
        (work / "commands.json").write_text(json.dumps(commands, indent=2) + "\n", encoding="utf-8")
        result = subprocess.run(command, cwd=REPO, text=True, encoding="utf-8", errors="replace",
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (work / (name + ".log")).write_text(result.stdout, encoding="utf-8")
        if result.stdout:
            print(result.stdout, end="", flush=True)
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, command)

    source_path = REPO / args.module / "src/main/java"
    dependencies = [gson, junit]
    if jar:
        dependencies.insert(0, jar)
        source_path = work / "empty-sourcepath"
        source_path.mkdir()
    execute("compile", [javac, "--release", "8", "-encoding", "UTF-8", "-cp",
                        os.pathsep.join(map(str, dependencies)), "-sourcepath", source_path,
                        "-d", classes, *tests])
    if jar:
        # A final-artifact test must not silently compile a source copy of production.
        leaked = [path for path in classes.rglob("*.class")
                  if not path.name.startswith(("LegacyEchoPortRegressionTest", "InlineLegacyCoreSimulation"))]
        if leaked:
            raise ValueError(f"Production classes leaked into test output: {leaked}")
    runtime = [classes] + ([jar] if jar else []) + [gson]
    classpath = os.pathsep.join(map(str, runtime))
    reports = work / "reports"
    execute("echo-tests", [java, "-jar", junit, "execute", "--class-path", classpath,
                           "--select-class", package + ".LegacyEchoPortRegressionTest",
                           "--details=summary", "--disable-banner", "--disable-ansi-colors",
                           "--reports-dir", reports])
    execute("existing-core", [java, "-cp", classpath, package + ".InlineLegacyCoreSimulation"])
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports.glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    if totals["tests"] < 41 or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise ValueError(f"Expected at least 41 passing regression cases, with no skips: {totals}")
    if jar and hashlib.sha256(jar.read_bytes()).hexdigest() != jar_hash:
        raise ValueError("Release JAR changed during verification")
    summary = {"module": args.module, "jar": str(jar) if jar else None, "jarSha256": jar_hash, "release": 8,
               "counts": totals, "existingCore": "passed", "providerCalls": 0, "results": str(work)}
    (work / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {totals['tests']} Java-8 echo cases and existing legacy core; {work}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
