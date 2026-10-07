#!/usr/bin/env python3
"""Run echo, native-notice and cache regressions against modern sources or an exact JAR.

All translation backends are in-process fakes. Dependencies are supplied explicitly;
this runner never downloads dependencies, contacts a provider, or changes production files.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
MODERN_MODULES = (
    ".", "fabric1171", "fabric1182", "fabric1194", "fabric120", "fabric12111",
    "fabric26", "fabric2612", "neoforge", "neoforge120", "neoforge26",
)
BASE_TESTS = (
    "TranslationCacheTest", "TranslationDebugLogTest", "TranslationCacheCoalescingTest",
    "TranslationCacheWindowedBatchTest", "TranslationRequestSwitchTest",
    "MachineTranslationGateTest", "TextFilterTest", "TranslationServiceTest",
    "TranslationServiceLayoutLangTest", "NameMaskerTest", "PlayerNameSlotTest",
)
TRANSLATE_TESTS = (
    "DebugErrorLogTest", "PlayerNamePatternsTest", "TranslationTemplateLeadIconTest",
)
REGRESSION_TESTS = (
    "UserEchoRegressionTest", "LobbyNativeAnnouncementRegressionTest",
    "ExistingCacheReuseRegressionTest",
    "ServerTraceReplayRegressionTest",
)


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True,
                        help="JDK 21 or newer, containing bin/java and bin/javac")
    parser.add_argument("--gson", type=Path, required=True, help="local gson JAR")
    parser.add_argument("--junit", type=Path, required=True,
                        help="local junit-platform-console-standalone JAR")
    parser.add_argument("--module", choices=MODERN_MODULES, default="fabric2612",
                        help="source tree under test (default: fabric2612)")
    parser.add_argument("--jar", type=Path,
                        help="test production classes from this exact release JAR instead of source")
    parser.add_argument("--output-dir", type=Path, default=REPO / "build/echo-verification",
                        help="parent of a fresh results directory; existing runs are retained")
    return parser.parse_args()


def require_file(path, label):
    path = path.expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"{label} is not a file: {path}")
    return path


def execute(name, command, work, commands):
    command = [str(arg) for arg in command]
    commands[name] = command
    (work / "commands.json").write_text(json.dumps(commands, indent=2) + "\n", encoding="utf-8")
    result = subprocess.run(command, cwd=REPO, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (work / (name + ".log")).write_text(result.stdout, encoding="utf-8")
    if result.stdout:
        print(result.stdout, end="", flush=True)
    print(f"{name}: exit {result.returncode}", flush=True)
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, command)


def junit_counts(reports):
    counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    files = sorted(reports.glob("TEST-*.xml"))
    if not files:
        raise ValueError("JUnit returned no XML reports")
    for path in files:
        suite = ET.parse(path).getroot()
        for name in counts:
            counts[name] += int(suite.get(name, "0"))
    return counts


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    args = parse_args()
    java_home = args.java_home.expanduser().resolve()
    suffix = ".exe" if os.name == "nt" else ""
    java = require_file(java_home / "bin" / ("java" + suffix), "java")
    javac = require_file(java_home / "bin" / ("javac" + suffix), "javac")
    gson = require_file(args.gson, "Gson JAR")
    junit = require_file(args.junit, "JUnit console JAR")
    jar = require_file(args.jar, "release JAR") if args.jar else None
    jar_hash = sha256(jar) if jar else None
    version = subprocess.run([str(javac), "-version"], check=True, text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()
    match = re.search(r"javac\s+(\d+)", version)
    if not match or int(match.group(1)) < 21:
        raise ValueError(f"Behavioral tests require JDK 21 or newer; found {version!r}")

    output_dir = args.output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=output_dir))
    origin = f"Artifact: {jar}" if jar else f"Source module: {args.module}"
    print(f"{origin}\nCompiler: {version}\nResults: {work}", flush=True)
    commands = {}
    dependencies = ([jar] if jar else []) + [gson, junit]
    classpath = os.pathsep.join(map(str, dependencies))

    # The earliest modern target must still compile as Java 16. Use its actual
    # compatible dependencies, not sources copied from a newer loader tree.
    if not jar:
        early_source = REPO / "fabric1171/src/main/java"
        early_classes = work / "release16-classes"
        early_classes.mkdir()
        execute("release16-compile", [
            javac, "--release", "16", "-encoding", "UTF-8", "-cp", classpath,
            "-sourcepath", early_source, "-d", early_classes,
            early_source / "com/dragonmeow/nyanlex/cache/TranslationCache.java",
            early_source / "com/dragonmeow/nyanlex/translate/TextFilter.java",
        ], work, commands)

    source = REPO / args.module / "src/main/java" if not jar else work / "empty-sourcepath"
    if jar:
        source.mkdir()
    test_root = REPO / "src/test/java/com/dragonmeow/nyanlex"
    regressions = HERE / "src/com/dragonmeow/nyanlex"
    tests = [test_root / "TestConfigs.java"]
    tests.extend(test_root / (name + ".java") for name in BASE_TESTS)
    tests.extend(test_root / "translate" / (name + ".java") for name in TRANSLATE_TESTS)
    tests.extend(regressions / (name + ".java") for name in REGRESSION_TESTS)
    for test in tests:
        require_file(test, "test source")
    classes = work / "classes"
    classes.mkdir()
    # Tests use Java 21 APIs for assertions and executor cleanup. This target does
    # not change any shipped bytecode: the independent release16 check runs above.
    execute("compile", [
        javac, "--release", "21", "-encoding", "UTF-8", "-cp", classpath,
        "-sourcepath", source, "-d", classes, *tests,
    ], work, commands)
    if jar:
        # Exact-artifact tests must load all production code from the chosen JAR.
        # Only explicit test classes and their nested classes may exist in output.
        allowed_test_classes = {test.stem for test in tests}
        leaked = [path for path in classes.rglob("*.class")
                  if path.stem.split("$", 1)[0] not in allowed_test_classes]
        if leaked:
            raise ValueError(f"Production classes leaked into test output: {leaked}")
    reports = work / "reports"
    runtime = [classes] + ([jar] if jar else []) + [gson]
    execute("tests", [
        java, "-jar", junit, "execute", "--class-path", os.pathsep.join(map(str, runtime)),
        "--scan-class-path", "--details=summary", "--disable-banner", "--disable-ansi-colors",
        "--reports-dir", reports,
    ], work, commands)
    if jar and sha256(jar) != jar_hash:
        raise ValueError("The release JAR changed during verification")
    counts = junit_counts(reports)
    summary = {"module": args.module, "compiler": version, "source": str(source) if not jar else None,
               "jar": str(jar) if jar else None, "jarSha256": jar_hash,
               "release16Compile": "passed" if not jar else "not_run_artifact_mode", "behavioralTestRelease": 21,
               "counts": counts, "results": str(work)}
    (work / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if counts["tests"] < 450 or any(counts[name] for name in ("failures", "errors", "skipped")):
        raise ValueError(f"Expected at least 450 successful tests with no skips; found {counts}")
    checked = "exact release JAR" if jar else "Java 16 core compatibility"
    print(f"PASS: {counts['tests']} tests; {checked}; {work / 'summary.json'}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
