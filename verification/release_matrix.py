"""Canonical artifact paths for the maintained projects and the expanded ports.

Target declarations are inputs, not evidence that a target has passed validation.
Publication must use the separately generated artifact validation report.
"""
from dataclasses import dataclass
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.0.0"


@dataclass(frozen=True)
class Target:
    loader: str
    minecraft: str
    project: str
    source_project: str
    java: int
    expanded: bool = False

    @property
    def key(self):
        return f"{self.loader}/{self.minecraft}"

    @property
    def label(self):
        return {"fabric": "Fabric", "neoforge": "NeoForge", "forge": "Forge"}[self.loader]

    @property
    def filename(self):
        return f"nyanlex-{VERSION}-{self.label}-{self.minecraft}.jar"

    @property
    def build_dir(self):
        base = ROOT / self.project / "build"
        return base / self.minecraft if self.expanded else base

    @property
    def jar(self):
        return self.build_dir / "libs" / self.filename

    @property
    def relative(self):
        return f"{self.key}/{self.filename}"


BASE_TARGETS = [
    Target("forge", "1.12.2", "forge1122", "forge1122", 8),
    Target("forge", "1.13.2", "forge1132", "forge1132", 8),
    Target("fabric", "1.14.4", "fabric1144", "fabric1144", 8),
    Target("fabric", "1.15.2", "fabric1152", "fabric1152", 8),
    Target("fabric", "1.16.5", "fabric1165", "fabric1165", 8),
    Target("fabric", "1.17.1", "fabric1171", "fabric1171", 16),
    Target("fabric", "1.18.2", "fabric1182", "fabric1182", 17),
    Target("fabric", "1.19.4", "fabric1194", "fabric1194", 17),
    Target("fabric", "1.20.1", "fabric120", "fabric120", 17),
    Target("fabric", "1.21.1", ".", ".", 21),
    Target("fabric", "1.21.11", "fabric12111", "fabric12111", 21),
    Target("fabric", "26.1.2", "fabric2612", "fabric2612", 25),
    Target("fabric", "26.2", "fabric26", "fabric26", 25),
    Target("fabric", "26.3", "fabric263", "fabric26", 25),
    Target("neoforge", "1.20.1", "neoforge120", "neoforge120", 17),
    Target("neoforge", "1.21.1", "neoforge", "neoforge", 21),
    Target("neoforge", "26.2", "neoforge26", "neoforge26", 25),
    Target("neoforge", "26.3", "neoforge263", "neoforge26", 25),
]

PORT_PROJECTS = {
    "ports/fabric-legacy": "fabric",
    "ports/fabric-modern": "fabric",
    "ports/neoforge": "neoforge",
}

# Official stable Minecraft releases, bounded by the requested 1.16.5–26.3 range.
# NeoForge was not released before Minecraft 1.20.1.
REQUESTED_RELEASES = (
    "1.16.5 1.17 1.17.1 1.18 1.18.1 1.18.2 1.19 1.19.1 1.19.2 1.19.3 1.19.4 "
    "1.20 1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.20.6 "
    "1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 "
    "26.1 26.1.1 26.1.2 26.2 26.3"
).split()


def version_key(version):
    return tuple(int(part) for part in version.split("."))


def targets():
    result = list(BASE_TARGETS)
    for project, loader in PORT_PROJECTS.items():
        data = json.loads((ROOT / project / "targets.json").read_text(encoding="utf-8-sig"))
        for minecraft, row in data.items():
            result.append(Target(loader, minecraft, project, row["sourceProject"], row["java"], True))
    keys = [target.key for target in result]
    if len(keys) != len(set(keys)):
        raise ValueError("Duplicate Minecraft/loader targets")
    expected = {f"fabric/{mc}" for mc in REQUESTED_RELEASES}
    expected |= {f"neoforge/{mc}" for mc in REQUESTED_RELEASES
                 if version_key(mc) >= version_key("1.20.1")}
    expected |= {"forge/1.12.2", "forge/1.13.2", "fabric/1.14.4", "fabric/1.15.2"}
    if set(keys) != expected:
        raise ValueError(f"Incomplete release matrix; missing={sorted(expected - set(keys))}, "
                         f"unexpected={sorted(set(keys) - expected)}")
    return sorted(result, key=lambda target: (target.loader, version_key(target.minecraft)))


if __name__ == "__main__":
    print(json.dumps([dict(loader=t.loader, minecraft=t.minecraft, project=t.project,
                           sourceProject=t.source_project, java=t.java, expanded=t.expanded,
                           jar=str(t.jar.relative_to(ROOT)), relative=t.relative)
                      for t in targets()], indent=2))
