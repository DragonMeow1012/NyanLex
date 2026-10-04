"""Render the localized README download matrices from the canonical release matrix."""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from release_matrix import ROOT, VERSION, targets, version_key


TEXT = {
    "README.md": {
        "heading": "下載",
        "scope": "完整支援範圍請以表格為準：Fabric 提供 Minecraft 1.16.5 至 26.3 的所有正式版本，NeoForge 提供 1.20.1 至 26.3 的所有正式版本；另保留 Fabric 1.14.4、1.15.2 與 Forge 1.12.2、1.13.2。每個 JAR 僅適用於檔名所示的 Minecraft 版本與 Loader。",
        "bundles": "整包下載",
        "bundle": "版本包",
        "all": "全部版本",
        "download": "下載",
        "expand": "依 Minecraft 系列展開並下載個別版本：",
        "legacy": "舊版 1.12～1.15",
        "java": "Java",
        "none": "—",
    },
    "README.en.md": {
        "heading": "Download",
        "scope": "Use the tables below as the definitive support list. Fabric covers every stable Minecraft release from 1.16.5 through 26.3, and NeoForge covers every stable release from 1.20.1 through 26.3. Fabric 1.14.4 and 1.15.2, plus Forge 1.12.2 and 1.13.2, remain available. Each JAR works only with the Minecraft version and loader named in its filename.",
        "bundles": "Bundle downloads",
        "bundle": "bundle",
        "all": "All versions",
        "download": "Download",
        "expand": "Expand a Minecraft series to download an individual build:",
        "legacy": "Legacy 1.12–1.15",
        "java": "Java",
        "none": "—",
    },
    "README.ja.md": {
        "heading": "ダウンロード",
        "scope": "正式な対応範囲は以下の表をご確認ください。Fabric は Minecraft 1.16.5 から 26.3 までの全正式版、NeoForge は 1.20.1 から 26.3 までの全正式版を提供します。Fabric 1.14.4／1.15.2 と Forge 1.12.2／1.13.2 も引き続き利用できます。各 JAR はファイル名に記載された Minecraft バージョンと Loader 専用です。",
        "bundles": "一括ダウンロード",
        "bundle": "版パック",
        "all": "全バージョン",
        "download": "ダウンロード",
        "expand": "Minecraft の系列を展開して個別版をダウンロード：",
        "legacy": "旧版 1.12～1.15",
        "java": "Java",
        "none": "—",
    },
    "README.zh-CN.md": {
        "heading": "下载",
        "scope": "完整支持范围请以表格为准：Fabric 提供 Minecraft 1.16.5 至 26.3 的所有正式版本，NeoForge 提供 1.20.1 至 26.3 的所有正式版本；另保留 Fabric 1.14.4、1.15.2 与 Forge 1.12.2、1.13.2。每个 JAR 仅适用于文件名所示的 Minecraft 版本与 Loader。",
        "bundles": "整包下载",
        "bundle": "版本包",
        "all": "全部版本",
        "download": "下载",
        "expand": "按 Minecraft 系列展开并下载单独版本：",
        "legacy": "旧版 1.12～1.15",
        "java": "Java",
        "none": "—",
    },
}

LOADER_LABEL = {"fabric": "Fabric", "neoforge": "NeoForge", "forge": "Forge"}
RELEASE = f"https://github.com/DragonMeow1012/NyanLex/releases/download/v{VERSION}"
BEGIN = "<!-- BEGIN GENERATED DOWNLOAD MATRIX -->"
END = "<!-- END GENERATED DOWNLOAD MATRIX -->"
HEADING_ALIASES = {
    "README.md": ("下載", "下載矩陣", "直接下載"),
    "README.en.md": ("Download", "Download matrix", "Direct downloads"),
    "README.ja.md": ("ダウンロード", "ダウンロード一覧", "直接ダウンロード"),
    "README.zh-CN.md": ("下载", "下载矩阵", "直接下载"),
}


def link(filename: str, label: str, text: dict[str, str]) -> str:
    return f"[{text['download']} {label}]({RELEASE}/{filename})"


def render(filename: str) -> str:
    text = TEXT[filename]
    rows = defaultdict(dict)
    java = {}
    for target in targets():
        rows[target.minecraft][target.loader] = target
        java[target.minecraft] = target.java

    groups = [(text["legacy"], ("1.12", "1.13", "1.14", "1.15"))]
    groups += [(f"Minecraft {major}", (major,)) for major in
               ("1.16", "1.17", "1.18", "1.19", "1.20", "1.21", "26")]

    lines = [BEGIN, f"## {text['heading']}", "", text["scope"], "",
             f"### {text['bundles']}", "",
             "| Fabric · 37 | NeoForge · 23 | Forge · 2 | " + text["all"] + " |",
             "| --- | --- | --- | --- |",
             "| " + " | ".join([
                 link(f"NyanLex-{VERSION}-Fabric.zip", f"Fabric {text['bundle']}", text),
                 link(f"NyanLex-{VERSION}-NeoForge.zip", f"NeoForge {text['bundle']}", text),
                 link(f"NyanLex-{VERSION}-Forge.zip", f"Forge {text['bundle']}", text),
                 link(f"NyanLex-{VERSION}-all-versions.zip", text["all"], text),
             ]) + " |", "", text["expand"], ""]

    for label, prefixes in groups:
        versions = [version for version in rows
                    if any(version == prefix or version.startswith(prefix + ".")
                           for prefix in prefixes)]
        versions.sort(key=version_key)
        if not versions:
            continue
        lines += ["<details>", f"<summary><b>{label}</b></summary>", "",
                  f"| Minecraft | Fabric | NeoForge | Forge | {text['java']} |",
                  "| --- | --- | --- | --- | ---: |"]
        for version in versions:
            cells = []
            for loader in ("fabric", "neoforge", "forge"):
                target = rows[version].get(loader)
                cells.append(text["none"] if target is None
                             else link(target.filename, LOADER_LABEL[loader] + " JAR", text))
            lines.append(f"| {version} | {' | '.join(cells)} | {java[version]} |")
        lines += ["", "</details>", ""]
    lines.append(END)
    return "\n".join(lines).rstrip() + "\n\n"


def update(filename: str) -> None:
    path = ROOT / filename
    source = path.read_text(encoding="utf-8")
    if BEGIN in source:
        start = source.index(BEGIN)
        end = source.index(END, start) + len(END)
        while end < len(source) and source[end] == "\n":
            end += 1
    else:
        headings = (f"## {heading}\n" for heading in HEADING_ALIASES[filename])
        heading = next(candidate for candidate in headings if candidate in source)
        start = source.index(heading)
        end = source.index("\n## ", start + len(heading)) + 1
    path.write_text(source[:start] + render(filename) + source[end:], encoding="utf-8", newline="\n")


if __name__ == "__main__":
    for readme in TEXT:
        update(readme)
