"""Validate port JARs and record hashes; does not publish or claim in-game testing."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tomllib
from zipfile import ZipFile

from release_matrix import ROOT, VERSION, targets


def load_check(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


features = load_check("translation_features", "verify-translation-features.py")
mixin_rules = load_check("mixin_rules", "check-mixin-rules.py")
mixin_targets = load_check("mixin_targets", "check-port-mixins.py")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_metadata(target, jar):
    spec = json.loads((ROOT / target.project / "targets.json").read_text(encoding="utf-8-sig"))[target.minecraft]
    names = jar.namelist()
    require(len(names) == len(set(names)), "Duplicate entries inside JAR")
    require(jar.testzip() is None, "Corrupt ZIP entry")
    for name in names:
        require(not re.search(r"(?i)nyanslate|mctranslator|minecrafttranslator|(^|/)hub/tool/|(^|/)translation-hub/", name),
                f"Unexpected old-brand or author-only entry: {name}")
        if name.endswith(".class"):
            data = jar.read(name)
            require(data[:4] == b"\xca\xfe\xba\xbe", f"Invalid class: {name}")
            require(int.from_bytes(data[6:8], "big") <= target.java + 44, f"Wrong Java level: {name}")
    locales = {name.rsplit("/", 1)[1].split(".", 1)[0] for name in names
               if name.startswith("assets/nyanlex/lang/") and name.endswith(".json")}
    require(locales == {"en_us", "ja_jp", "zh_tw", "zh_cn"}, f"Incorrect UI locale set: {locales}")
    if target.loader == "fabric":
        metadata = json.loads(jar.read("fabric.mod.json"))
        require(metadata["id"] == "nyanlex" and metadata["version"] == VERSION, "Incorrect mod identity/version")
        require(metadata["environment"] == "client", "Not client-only")
        deps = metadata["depends"]
        api_id = spec.get("fabricApiId", "fabric-api")
        require(set(deps) == {"minecraft", "fabricloader", "java", api_id}, f"Incorrect dependency set: {deps}")
        require(deps["minecraft"] == target.minecraft, "Minecraft dependency must match exact target")
        require(deps["java"] == f">={target.java}", "Java dependency mismatch")
        require(bool(deps[api_id]) and bool(deps["fabricloader"]), "Missing Fabric dependencies")
        require(metadata["icon"] in names, "Missing icon")
        entrypoints = metadata["entrypoints"]["client"]
        require(len(entrypoints) == 1 and entrypoints[0].replace(".", "/") + ".class" in names, "Missing client entrypoint")
        return dict(kind="fabric", minecraft=deps["minecraft"], java=deps["java"],
                    loader=deps["fabricloader"], fabricApiId=api_id, fabricApiRange=deps[api_id], mainClass=entrypoints[0])
    metadata_path = "META-INF/neoforge.mods.toml" if "META-INF/neoforge.mods.toml" in names else "META-INF/mods.toml"
    metadata = tomllib.loads(jar.read(metadata_path).decode("utf-8"))
    require(metadata["modLoader"] == "javafml", "Incorrect NeoForge mod loader")
    mods = [mod for mod in metadata["mods"] if mod["modId"] == "nyanlex"]
    require(len(mods) == 1 and mods[0]["version"] == VERSION, "Incorrect NeoForge identity/version")
    deps = metadata["dependencies"]["nyanlex"]
    require({dep["modId"] for dep in deps} == {"minecraft", "neoforge"}, "Incorrect NeoForge dependencies")
    game = next(dep for dep in deps if dep["modId"] == "minecraft")
    loader = next(dep for dep in deps if dep["modId"] == "neoforge")
    require(game["versionRange"] == f"[{target.minecraft}]", "NeoForge Minecraft range must be exact")
    require(loader["versionRange"] == f"[{spec['neoforge']},)",
            "NeoForge minimum must match the declared build dependency")
    require(all(dep.get("side") == "CLIENT" for dep in deps), "NeoForge dependency not marked client-only")
    require(all(dep.get("type") == "required" or dep.get("mandatory") is True for dep in deps), "Missing required dependency")
    icon_key = "iconFile" if "iconFile" in mods[0] else "logoFile"
    require(mods[0][icon_key] in names, "Missing NeoForge icon")
    prefix = "neoforge26" if target.source_project == "neoforge26" else "neoforge"
    main_class = f"com.dragonmeow.nyanlex.{prefix}.NyanLexNeoForge" + ("26" if prefix == "neoforge26" else "")
    require(main_class.replace(".", "/") + ".class" in names, "Missing NeoForge entrypoint")
    return dict(kind="toml", entry=metadata_path, minecraft=game["versionRange"], loader=metadata["loaderVersion"],
                loaderDependencyRange=loader["versionRange"], iconKey=icon_key, mainClass=main_class)


def check(target):
    require(target.jar.is_file(), f"Missing JAR: {target.jar}")
    if target.loader == "neoforge":
        spec = json.loads((ROOT / target.project / "targets.json").read_text(encoding="utf-8-sig"))[target.minecraft]
        build = json.loads((target.build_dir / "port-verification.json").read_text(encoding="utf-8-sig"))
        require(build.get("loaderVersion") == spec["neoforge"], "Build evidence uses a different NeoForge version")
        dependency_name = re.compile(re.escape(f"neoforge-{spec['neoforge']}") + r"(?:\.jar$|[-_])")
        require(any(dependency_name.search(path) for path in build["compileClasspath"]),
                "Compiled classpath does not contain the declared NeoForge dependency")
    before = target.jar.read_bytes()
    with ZipFile(target.jar) as jar:
        metadata = check_metadata(target, jar)
    feature_result = features.check_jar(target.source_project, target.jar)
    problems, summary = mixin_rules.scan_jar(str(target.jar))
    require(not problems, f"Mixin rules failed: {problems}")
    hooks = mixin_targets.check_target(target)
    require(not hooks["errors"], f"Mixin target validation failed: {hooks['errors']}")
    require(target.jar.read_bytes() == before, "Artifact changed during verification")
    return dict(target=target.key, project=target.project, minecraft=target.minecraft, loader=target.loader,
                jar=str(target.jar.relative_to(ROOT)).replace("\\", "/"), relative=target.relative,
                size=len(before), hashes={algorithm: hashlib.new(algorithm, before).hexdigest()
                                         for algorithm in ("sha1", "sha256", "sha512")},
                metadata=metadata, features=feature_result, mixinRules=summary,
                injectorsChecked=hooks["injectors_checked"], runtimeTested=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", nargs="*", help="loader/version keys; default all new ports")
    args = parser.parse_args()
    selected = [target for target in targets() if target.expanded and (not args.target or target.key in args.target)]
    require(not args.target or set(args.target) == {target.key for target in selected}, "Unknown port target")
    results, failures = [], []
    for target in selected:
        try:
            results.append(check(target))
            print(f"PASS {target.key}")
        except Exception as error:
            failures.append(dict(target=target.key, error=str(error)))
            print(f"FAIL {target.key}: {error}")
    report = dict(version=VERSION, scope="all-ports" if not args.target else "selected-ports",
                  expected=len(selected), passed=len(results), artifacts=results, failures=failures,
                  runtimeTested=False)
    output = ROOT / "build" / ("port-artifacts.json" if not args.target else "selected-port-artifacts.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"PORT_ARTIFACTS passed={len(results)} expected={len(selected)} report={output}")
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
