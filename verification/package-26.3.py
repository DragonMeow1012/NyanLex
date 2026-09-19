"""Validate and package the two Minecraft 26.3 build outputs (Python 3.11+)."""
import hashlib
import json
from pathlib import Path
import shutil
import tomllib
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parent.parent
VERSION = "1.0.4"
OUTPUT = ROOT / "mods-jar" / f"{VERSION}-26.3"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    artifacts = []
    for loader, label in (("fabric", "Fabric"), ("neoforge", "NeoForge")):
        name = f"mctranslator-{VERSION}-{label}-26.3.jar"
        source = ROOT / f"{loader}263" / "build" / "libs" / name
        with ZipFile(source) as jar:
            assert jar.testzip() is None, f"Corrupt JAR: {source}"
            if loader == "fabric":
                metadata = json.loads(jar.read("fabric.mod.json"))
                assert metadata["version"] == VERSION
                assert metadata["depends"]["minecraft"] == "26.3"
                assert metadata["depends"]["java"] == ">=25"
                entrypoint = metadata["entrypoints"]["client"][0]
            else:
                metadata = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())
                assert metadata["mods"][0]["version"] == VERSION
                dependencies = {item["modId"]: item for item in metadata["dependencies"]["mctranslator"]}
                assert dependencies["minecraft"]["versionRange"] == "[26.3,26.4)"
                assert dependencies["neoforge"]["versionRange"] == "[26.3,)"
                entrypoint = "com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26"
            assert entrypoint.replace(".", "/") + ".class" in jar.namelist()
            assert "com/dragonmeow/mctranslator/platform/BrowserLinks.class" in jar.namelist()
        artifacts.append((source, Path(loader) / "26.3" / name))

    # Validate all inputs before writing any packaged output.
    for source, relative in artifacts:
        destination = OUTPUT / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        assert sha256(source) == sha256(destination)

    archive_path = OUTPUT / f"MinecraftTranslator-{VERSION}-26.3.zip"
    with ZipFile(archive_path, "w", ZIP_DEFLATED) as archive:
        for _, relative in artifacts:
            archive.write(OUTPUT / relative, relative.as_posix())
    with ZipFile(archive_path) as archive:
        assert len(archive.namelist()) == 2
        for source, relative in artifacts:
            assert archive.read(relative.as_posix()) == source.read_bytes()

    paths = [relative for _, relative in artifacts] + [Path(archive_path.name)]
    (OUTPUT / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(OUTPUT / path)}  {path.as_posix()}\n" for path in paths),
        encoding="utf-8",
    )
    print(f"Verified 2 JARs, ZIP contents, and SHA-256 checksums: {OUTPUT}")


if __name__ == "__main__":
    main()
