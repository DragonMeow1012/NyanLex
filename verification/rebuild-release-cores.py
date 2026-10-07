"""Recompile the changed JVM-only cores into verified, already mapped 1.0.0 JARs.

This is deliberately separate from the full Gradle release matrix. It preserves
every other entry's exact bytes, proves baseline core/source correspondence,
checks linkage, and reruns the checks that operate on final JARs alone. It does
not claim a fresh remap, a fresh game-classpath Mixin check, or a Minecraft run.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
import tomllib
import zlib
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from release_matrix import ROOT, VERSION, targets

BASELINE = "95ae24353cbe1434bd6508e69af0ff8bfb4a72b3"
PREFIX = "com/dragonmeow/nyanlex/"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "verification" / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def executable(home, name):
    path = home / "bin" / (name + (".exe" if os.name == "nt" else ""))
    require(path.is_file(), f"Missing tool: {path}")
    return str(path)


def run(command, log, cwd=ROOT):
    started = time.monotonic()
    with log.open("w", encoding="utf-8") as output:
        proc = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT)
    require(proc.returncode == 0, f"Command failed ({proc.returncode}); see {log}")
    return {"command": command, "log": str(log), "seconds": round(time.monotonic() - started, 3)}


def core_sources(target):
    if target.java == 8:
        package = "forgelegacy" if target.loader == "forge" else "legacy"
        names = [PREFIX + package + "/LegacyTranslator.java"]
    else:
        names = [PREFIX + "cache/TranslationCache.java", PREFIX + "translate/TextFilter.java"]
    root = Path(target.source_project) / "src/main/java"
    return [(root / name).as_posix() for name in names]


def families(target):
    return [name.split("/src/main/java/")[-1].removeprefix("src/main/java/")[:-5]
            for name in core_sources(target)]


def in_family(name, roots):
    return any(name == root + ".class" or (name.startswith(root + "$") and name.endswith(".class"))
               for root in roots)


def inventory(path):
    with ZipFile(path) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), f"Duplicate ZIP entries: {path}")
        require(jar.testzip() is None, f"ZIP CRC failure: {path}")
        for name in names:
            pure = PurePosixPath(name)
            require(not pure.is_absolute() and ".." not in pure.parts and "\\" not in name
                    and "\x00" not in name, f"Unsafe ZIP entry: {name}")
            require(not re.match(r"(?i)^META-INF/(?:.*\.(?:SF|RSA|DSA|EC)|SIG-.*)$", name),
                    f"Signed JAR cannot be modified by this procedure: {path}: {name}")
        manifest = jar.read("META-INF/MANIFEST.MF") if "META-INF/MANIFEST.MF" in names else b""
        require(b"-Digest:" not in manifest and b"-Digest-Manifest:" not in manifest,
                f"Signed manifest cannot be modified: {path}")
        return {name: sha(jar.read(name)) for name in names}


def class_audit(input_path, report_path, auditor_cp, java25):
    run([executable(java25, "java"), "-cp", auditor_cp, "CoreClassAudit",
         str(input_path), str(report_path)], report_path.with_suffix(".log"))
    return json.loads(report_path.read_text(encoding="utf-8"))


def compile_core(target, source_paths, original, output, empty, compiler_home, gson, log):
    output.mkdir(parents=True)
    command = [executable(compiler_home, "javac")]
    if target.loader == "forge":
        command += ["-source", "8", "-target", "8"]
    else:
        command += ["--release", str(target.java)]
    command += ["-g", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
                "-sourcepath", str(empty), "-classpath", os.pathsep.join([str(original), str(gson)]),
                "-d", str(output), *map(str, source_paths)]
    return run(command, log)


def member_key(member):
    return member["name"], member["descriptor"]


def member_lookup(classes, owner, kind, key, seen=None):
    seen = set() if seen is None else seen
    if owner in seen:
        return None
    seen.add(owner)
    node = classes.get(owner + ".class")
    if node is None:
        return None
    for item in node[kind]:
        if member_key(item) == key:
            return item
    if key[0] == "<init>":
        return None
    for parent in [node.get("parent"), *node.get("interfaces", [])]:
        if parent:
            found = member_lookup(classes, parent, kind, key, seen)
            if found is not None:
                return found
    return None


def jdk_boundaries(classes, owner, seen=None):
    seen = set() if seen is None else seen
    if owner in seen:
        return set()
    seen.add(owner)
    node = classes.get(owner + '.class')
    if node is None:
        return {owner} if owner.startswith(('java/', 'javax/')) else set()
    result = set()
    for parent in [node.get('parent'), *node.get('interfaces', [])]:
        if parent:
            result.update(jdk_boundaries(classes, parent, seen))
    return result


def validate_linkage(old, new, roots):
    old_core = {k: v for k, v in old.items() if in_family(k, roots)}
    combined = {k: v for k, v in old.items() if not in_family(k, roots)} | new
    public_members = 0
    private_access_bridges = {(node['name'], item['name'], item['descriptor'])
                              for node in old_core.values() for item in node['methods']
                              if item['access'] & 0x1000 and item['access'] & 0x0008
                              and not item['access'] & 0x0005
                              and re.fullmatch(r'access\$[0-9]+', item['name'])}
    for name, node in old.items():
        if in_family(name, roots):
            continue
        require(not any((ref['owner'], ref['name'], ref['descriptor']) in private_access_bridges
                        for ref in node['member_refs']),
                f"A class outside the rebuilt family uses a synthetic private-access bridge: {name}")
    for name, before in old_core.items():
        # Private nested implementation classes are replaced as one closed family.
        # Their internal constructors can change; actual outside references below
        # are still required to resolve. Public/package contracts cannot shrink.
        if before.get("inner_access", before["access"]) & 0x0002:
            continue
        require(name in new, f"Externally visible class removed: {name}")
        after = new[name]
        for field in ["name", "parent", "interfaces", "access"]:
            require(before.get(field) == after.get(field), f"Class ABI changed: {name}: {field}")
        for kind in ["fields", "methods"]:
            after_members = {member_key(item): item for item in after[kind]}
            for item in before[kind]:
                if item["access"] & 0x0002 or item["name"] == "<clinit>":
                    continue
                if kind == 'methods' and (before['name'], item['name'], item['descriptor']) in private_access_bridges:
                    continue
                require(member_key(item) in after_members,
                        f"Public/package member removed: {name}: {item}")
                replacement = after_members[member_key(item)]
                require(item == replacement, f"Public/package member ABI changed: {name}: {item}")
                public_members += 1

    outside_references = 0
    core_references = 0
    unchanged_jdk_inherited_references = 0
    original_external_refs = {(ref['owner'], ref['name'], ref['descriptor'], ref['kind'])
                              for node in old_core.values() for ref in node['member_refs']
                              if not ref['owner'].startswith((PREFIX, 'java/', 'javax/'))}
    unchanged_external_references = 0
    for name, node in combined.items():
        changed = name in new
        if changed:
            for dependency in node["class_refs"] + node["descriptor_class_refs"]:
                require(not re.search(r"(?:^|L)(?:net/minecraft|net/fabricmc|net/minecraftforge|net/neoforged|org/spongepowered)/", dependency),
                        f"Core contains a mapped/loader type: {name}: {dependency}")
        for ref in node["member_refs"]:
            owner = ref["owner"]
            if changed and not owner.startswith((PREFIX, 'java/', 'javax/')):
                require((owner, ref['name'], ref['descriptor'], ref['kind']) in original_external_refs,
                        f'New external dependency API requirement: {name}: {ref}')
                unchanged_external_references += 1
            relevant = (changed and owner.startswith(PREFIX)) or in_family(owner + ".class", roots)
            if not relevant:
                continue
            key = member_key(ref)
            resolved = member_lookup(combined, owner, ref["kind"], key)
            if resolved is None and changed:
                # An old call such as AnonymousLinkedHashMap.size() names the mod
                # subclass in the pool, but the declaration lives in the JDK.
                # Accept only the exact pre-existing reference with an unchanged
                # JDK inheritance boundary and no removed mod declaration.
                existed = any(ref in previous["member_refs"] for previous in old_core.values())
                old_boundary = jdk_boundaries(old, owner)
                require(key[0] != '<init>' and existed and member_lookup(old, owner, ref['kind'], key) is None
                        and old_boundary and old_boundary == jdk_boundaries(combined, owner),
                        f"Unresolved new mod member reference: {name}: {ref}")
                unchanged_jdk_inherited_references += 1
            else:
                require(resolved is not None, f"Unresolved final core member: {name}: {ref}")
            if changed:
                core_references += 1
            else:
                outside_references += 1
    return {"public_and_package_members_preserved": public_members,
            "synthetic_private_access_bridges_confined_to_rebuilt_family": len(private_access_bridges),
            "unchanged_class_references_to_core_checked": outside_references,
            "new_core_mod_references_checked": core_references,
            "unchanged_jdk_inherited_references_checked": unchanged_jdk_inherited_references,
            "unchanged_external_dependency_references_checked": unchanged_external_references,
            "mapped_types_in_recompiled_core": False}


def replace_families(original, output, classes, roots):
    new_entries = {p.relative_to(classes).as_posix(): p.read_bytes()
                   for p in classes.rglob("*.class")}
    require(new_entries, "Compiler produced no classes")
    require(all(in_family(name, roots) for name in new_entries),
            "Compiler emitted a class outside the selected family (implicit compilation)")
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(original) as source, ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as dest:
        dest.comment = source.comment
        emitted = set()
        for entry in source.infolist():
            if in_family(entry.filename, roots):
                if entry.filename in new_entries:
                    dest.writestr(copy.copy(entry), new_entries[entry.filename], compresslevel=9)
                    emitted.add(entry.filename)
            else:
                dest.writestr(copy.copy(entry), source.read(entry.filename), compresslevel=9)
        for name in sorted(set(new_entries) - emitted):
            entry = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            dest.writestr(entry, new_entries[name], compresslevel=9)


def check_metadata(target, jar, port_checks):
    if target.expanded:
        return port_checks.check_metadata(target, jar)
    names = set(jar.namelist())
    if target.loader == 'fabric':
        metadata = json.loads(jar.read('fabric.mod.json'))
        require(metadata['id'] == 'nyanlex' and metadata['version'] == VERSION,
                f'Incorrect Fabric identity/version: {target.key}')
        require(metadata['environment'] == 'client', f'Not a client mod: {target.key}')
        deps = metadata['depends']
        require(deps['minecraft'] == target.minecraft and deps['java'] == f'>={target.java}',
                f'Incorrect Fabric Minecraft/Java requirement: {target.key}')
        api = 'fabric' if 'fabric' in deps else 'fabric-api'
        require(set(deps) == {'minecraft', 'java', 'fabricloader', api} and deps[api] and deps['fabricloader'],
                f'Incorrect Fabric dependencies: {target.key}')
        require(metadata['icon'] in names, f'Missing Fabric icon: {target.key}')
        entrypoints = metadata['entrypoints']['client']
        require(len(entrypoints) == 1 and entrypoints[0].replace('.', '/') + '.class' in names,
                f'Missing Fabric client entrypoint: {target.key}')
        return {'kind': 'fabric', 'minecraft': deps['minecraft'], 'java': deps['java'],
                'dependencies': deps, 'mainClass': entrypoints[0], 'effectiveVersion': VERSION}
    if target.key == 'forge/1.12.2':
        rows = json.loads(jar.read('mcmod.info'))
        require(len(rows) == 1 and rows[0]['modid'] == 'nyanlex' and rows[0]['version'] == VERSION
                and rows[0]['mcversion'] == target.minecraft, 'Incorrect Forge 1.12.2 metadata')
        require(rows[0]['logoFile'].lstrip('/') in names, 'Missing Forge 1.12.2 icon')
        main = PREFIX + 'forgelegacy/NyanLexForge.class'
        require(main in names, 'Missing Forge 1.12.2 entrypoint')
        return {'kind': 'mcmod.info', 'minecraft': rows[0]['mcversion'], 'effectiveVersion': VERSION,
                'mainClass': main[:-6].replace('/', '.')}
    path = 'META-INF/neoforge.mods.toml' if 'META-INF/neoforge.mods.toml' in names else 'META-INF/mods.toml'
    metadata = tomllib.loads(jar.read(path).decode('utf-8'))
    require(metadata['modLoader'] == 'javafml', f'Incorrect FML loader: {target.key}')
    mods = [row for row in metadata['mods'] if row['modId'] == 'nyanlex']
    require(len(mods) == 1, f'Incorrect FML mod identity: {target.key}')
    declared_version = mods[0]['version']
    if target.key == 'forge/1.13.2':
        # Forge 1.13.2 expands this standard macro from its retained manifest.
        require(declared_version == '${file.jarVersion}', 'Forge 1.13.2 version macro changed')
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n', '\n')
        require(re.search(r'^Implementation-Version: 1\.0\.0$', manifest, re.M),
                'Forge 1.13.2 effective manifest version is not 1.0.0')
    else:
        require(declared_version == VERSION, f'Incorrect NeoForge version: {target.key}')
    expected_ranges = {'forge/1.13.2': '[1.13.2]', 'neoforge/1.20.1': '[1.20.1,1.20.2)',
                       'neoforge/1.21.1': '[1.21.1,1.21.2)', 'neoforge/26.2': '[26.2,26.3)',
                       'neoforge/26.3': '[26.3,26.4)'}
    deps = metadata['dependencies']['nyanlex']
    game = [row for row in deps if row['modId'] == 'minecraft']
    require(len(game) == 1 and game[0]['versionRange'] == expected_ranges[target.key],
            f'Incorrect maintained Minecraft predicate: {target.key}')
    expected_loader = 'forge' if target.key in {'forge/1.13.2', 'neoforge/1.20.1'} else 'neoforge'
    require({row['modId'] for row in deps} == {'minecraft', expected_loader},
            f'Incorrect FML dependency set: {target.key}')
    require(all(row.get('side') == 'CLIENT' and (row.get('mandatory') is True or row.get('type') == 'required')
                for row in deps), f'Incorrect client/required dependencies: {target.key}')
    icon = mods[0].get('iconFile', mods[0].get('logoFile'))
    require(icon in names, f'Missing FML icon: {target.key}')
    main = ('forgelegacy/NyanLexForge' if target.loader == 'forge' else
            'neoforge26/NyanLexNeoForge26' if target.source_project == 'neoforge26' else 'neoforge/NyanLexNeoForge')
    require(PREFIX + main + '.class' in names, f'Missing FML entrypoint: {target.key}')
    return {'kind': 'toml', 'entry': path, 'minecraft': game[0]['versionRange'],
            'loader': metadata['loaderVersion'], 'dependencies': deps, 'declaredVersion': declared_version,
            'effectiveVersion': VERSION, 'mainClass': (PREFIX + main).replace('/', '.')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-root", type=Path, required=True, help="Verified original loader/MC/JAR tree")
    parser.add_argument("--output-root", type=Path, required=True, help="New loader/MC/JAR tree; must be empty")
    parser.add_argument("--report-root", type=Path, required=True, help="New build/evidence directory; must be empty")
    parser.add_argument("--java-home", type=Path, required=True, help="JDK 25; also runs the auditor")
    parser.add_argument("--java21-home", type=Path, required=True)
    parser.add_argument("--java8-home", type=Path, required=True)
    parser.add_argument("--gson", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True, help="Official ASM core JAR, Java 25 capable")
    parser.add_argument("--asm-tree", type=Path, required=True)
    parser.add_argument("--baseline-commit", default=BASELINE)
    parser.add_argument("--input-manifest", type=Path, default=ROOT / "verification/release-2026-10-05.json")
    parser.add_argument("--target", action="append", default=[])
    args = parser.parse_args()
    verification_inputs = {str(path.relative_to(ROOT)): sha(path.read_bytes()) for path in
                           [Path(__file__), ROOT / 'verification/CoreClassAudit.java',
                            ROOT / 'verification/release_matrix.py', ROOT / 'verification/verify-translation-features.py',
                            ROOT / 'verification/check-mixin-rules.py', ROOT / 'verification/verify-port-artifacts.py']}
    args.input_root = args.input_root.resolve()
    args.output_root = args.output_root.resolve()
    args.report_root = args.report_root.resolve()
    for path in [args.output_root, args.report_root]:
        require(not path.exists() or not any(path.iterdir()), f"Output must be a new empty directory: {path}")
        path.mkdir(parents=True, exist_ok=True)
    all_targets = targets()
    selected = [t for t in all_targets if not args.target or t.key in args.target]
    require(selected and (not args.target or {t.key for t in selected} == set(args.target)), "Unknown target")
    manifest_bytes = args.input_manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    expected = {item["target"]: item for item in manifest["artifacts"]}
    require(set(expected) == {t.key for t in all_targets} and manifest["version"] == VERSION,
            "Input release manifest does not cover the exact canonical matrix/version")
    baseline = subprocess.check_output(["git", "rev-parse", args.baseline_commit + "^{commit}"], cwd=ROOT, text=True).strip()
    source_paths = sorted({path for target in selected for path in core_sources(target)})
    frozen = {path: sha((ROOT / path).read_bytes()) for path in source_paths}
    write_json(args.report_root / "frozen-source-inputs.json", frozen)
    baseline_root = args.report_root / "baseline-sources"
    for path in source_paths:
        dest = baseline_root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(subprocess.check_output(["git", "show", baseline + ":" + path], cwd=ROOT))
    write_json(args.report_root / "baseline-source-inputs.json",
               {path: sha((baseline_root / path).read_bytes()) for path in source_paths})

    features = module("release_features", "verify-translation-features.py")
    mixins = module("release_mixin_rules", "check-mixin-rules.py")
    port_checks = module('release_port_metadata', 'verify-port-artifacts.py')
    auditor_dir = args.report_root / "auditor-classes"
    auditor_dir.mkdir()
    auditor_cp = os.pathsep.join(map(str, [auditor_dir, args.gson.resolve(), args.asm.resolve(), args.asm_tree.resolve()]))
    run([executable(args.java_home, "javac"), "--release", "25", "-encoding", "UTF-8", "-proc:none",
         "-classpath", auditor_cp, "-d", str(auditor_dir), str(ROOT / "verification/CoreClassAudit.java")],
        args.report_root / "compile-auditor.log")
    empty = args.report_root / "empty-sourcepath"
    empty.mkdir()
    artifacts = []
    started = time.monotonic()
    for target in selected:
        print("Rebuilding verified core:", target.key, flush=True)
        original = args.input_root / target.relative
        output = args.output_root / target.relative
        work = args.report_root / target.key
        work.mkdir(parents=True)
        original_bytes = original.read_bytes()
        require(sha(original_bytes) == expected[target.key]["sha256"], f"Original artifact hash mismatch: {target.key}")
        old_entries = inventory(original)
        roots = families(target)
        paths = core_sources(target)
        compiler = args.java8_home if target.loader == "forge" else args.java_home if target.java == 25 else args.java21_home
        baseline_compile = compile_core(target, [baseline_root / p for p in paths], original,
                                        work / "baseline-classes", empty, compiler, args.gson, work / "compile-baseline.log")
        old_classes = class_audit(original, work / "original-classes.json", auditor_cp, args.java_home)
        baseline_classes = class_audit(work / "baseline-classes", work / "baseline-classes.json", auditor_cp, args.java_home)
        original_core = {k: v for k, v in old_classes.items() if in_family(k, roots)}
        require(set(original_core) == set(baseline_classes), f"Baseline class-family set differs: {target.key}")
        mismatches = [name for name in baseline_classes
                      if original_core[name]["normalized_sha256"] != baseline_classes[name]["normalized_sha256"]]
        require(not mismatches, f"Published core does not match baseline source/compiler: {target.key}: {mismatches}")
        current_compile = compile_core(target, [ROOT / p for p in paths], original,
                                       work / "new-classes", empty, compiler, args.gson, work / "compile-new.log")
        new_classes = class_audit(work / "new-classes", work / "new-classes.json", auditor_cp, args.java_home)
        require(all(in_family(name, roots) for name in new_classes), f"Unexpected compiler output: {target.key}")
        require(all(row["major"] == target.java + 44 for row in new_classes.values()), f"Wrong class major: {target.key}")
        linkage = validate_linkage(old_classes, new_classes, roots)
        replace_families(original, output, work / "new-classes", roots)
        new_entries = inventory(output)
        unchanged = {k: v for k, v in old_entries.items() if not in_family(k, roots)}
        actual_other = {k: v for k, v in new_entries.items() if not in_family(k, roots)}
        require(unchanged == actual_other, f"Entry outside core changed: {target.key}")
        with ZipFile(output) as jar:
            features.assert_java_level(jar, target.key, target.java)
            metadata = check_metadata(target, jar, port_checks)
            require(not any("TranslationTraceLog" in n or "NameResolutionWindow" in n for n in jar.namelist()),
                    f"Diagnostic or rejected TAB window classes remain: {target.key}")
            require(all(jar.read(name) == (work / "new-classes" / name).read_bytes() for name in new_classes),
                    f"Final JAR differs from compiled core: {target.key}")
        features.check_jar(target.source_project, output)
        original_problems, _ = mixins.scan_jar(original)
        problems, mixin_description = mixins.scan_jar(output)
        require(sorted(problems) == sorted(original_problems),
                f"Mixin rule findings changed from the pinned original: {target.key}: {problems}")
        if target.expanded:
            # This is an existing strict gate for all 44 ports; it is unchanged.
            require(not problems, f"Expanded-port Mixin rule failure: {target.key}: {problems}")
        require(original.read_bytes() == original_bytes, f"Original input changed during build: {target.key}")
        artifact = {
            "target": target.key, "file": target.filename, "relative": target.relative,
            "path": str(output), "bytes": output.stat().st_size, "sha256": sha(output.read_bytes()),
            "source_project": target.source_project, "java": target.java,
            "method": "core-only incremental rebuild", "clean_full_gradle_build": False,
            "runtimeTested": False, "original_sha256": expected[target.key]["sha256"],
            "baseline_commit": baseline, "baseline_class_count": len(baseline_classes),
            "baseline_core_matches_source": True,
            "baseline_comparison": "ASM canonical form: no debug/frame encoding; fresh constant pool; sorted inner/nest inventories; executable instructions and bootstrap arguments preserved",
            "source_sha256": {path: frozen[path] for path in paths},
            "recompiled_class_families": roots, "recompiled_class_count": len(new_classes),
            "recompiled_classes_sha256": {name: row["sha256"] for name, row in new_classes.items()},
            "mapped_entries_byte_identical": True, "all_other_entries_byte_identical": True,
            "unchanged_entry_count": len(unchanged), "unchanged_entries_sha256": unchanged,
            "removed_core_entries": sorted(set(original_core) - set(new_classes)),
            "added_core_entries": sorted(set(new_classes) - set(original_core)),
            "metadata_resources_manifest_licenses_preserved": True, "zip_crc_valid": True,
            "metadata": metadata,
            "class_level_check": "passed", "feature_check": "passed",
            "mixin_rule_check": {"status": "unchanged_baseline_findings" if problems else "passed",
                                 "detail": mixin_description, "baseline_findings": original_problems,
                                 "current_findings": problems, "new_findings": [],
                                 "baseline_jar_sha256": expected[target.key]["sha256"]},
            "game_classpath_mixin_check": "not rerun; unchanged mapped class bytes and mixin resources are proven, without relabeling the historical check as a fresh pass",
            "linkage": linkage, "baseline_compile": baseline_compile, "current_compile": current_compile,
            "artifact_jvm_tests": "pending; separate exact-JAR test report required",
        }
        write_json(work / "artifact-verification.json", artifact)
        artifacts.append(artifact)
        write_json(args.report_root / "progress.json", {"completed": len(artifacts), "selected": len(selected),
                                                       "artifacts": [{k: a[k] for k in ["target", "path", "sha256"]} for a in artifacts]})
        print("Verified", target.key, artifact["sha256"], flush=True)
    require(frozen == {path: sha((ROOT / path).read_bytes()) for path in source_paths}, "Frozen source changed during build")
    require(verification_inputs == {path: sha((ROOT / path).read_bytes()) for path in verification_inputs},
            'Build/audit scripts changed during this run')
    tools = {}
    for label, home in [("jdk25", args.java_home), ("jdk21", args.java21_home), ("jdk8", args.java8_home)]:
        proc = subprocess.run([executable(home, "javac"), "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True)
        tools[label] = {"home": str(home.resolve()), "javac": proc.stdout.strip(),
                        "javac_binary_sha256": sha(Path(executable(home, "javac")).read_bytes())}
    result = {"version": VERSION, "method": "core-only incremental rebuild", "baseline_commit": baseline,
              "build_time_utc": datetime.now(timezone.utc).isoformat(), "seconds": round(time.monotonic() - started, 3),
              "source_unchanged_during_build": True, "canonical_target_count": len(all_targets),
              "selected_target_count": len(selected), "passed": len(artifacts), "failed": 0,
              "clean_full_gradle_build": False, "runtimeTested": False,
              "input_manifest_sha256": sha(manifest_bytes), "toolchains": tools,
              "verification_inputs_sha256": verification_inputs,
              "python": sys.version, "zip_compressor": {'library': 'zlib', 'runtime_version': zlib.ZLIB_RUNTIME_VERSION, 'level': 9},
              "tool_dependencies": {str(p.resolve()): sha(p.read_bytes()) for p in [args.gson, args.asm, args.asm_tree]},
              "script_sha256": sha(Path(__file__).read_bytes()),
              "auditor_source_sha256": sha((ROOT / "verification/CoreClassAudit.java").read_bytes()),
              "command": [sys.executable, *sys.argv], "artifacts": artifacts}
    write_json(args.report_root / "release-core-rebuild.json", result)
    print(json.dumps({"passed": len(artifacts), "output_root": str(args.output_root),
                      "report": str(args.report_root / "release-core-rebuild.json")}), flush=True)


if __name__ == "__main__":
    main()
