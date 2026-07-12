# Mirror the MC-agnostic core packages from the root tree (src\) — the canonical,
# unit-tested copy — into the modern source-compatible loader trees. Run after
# ANY edit under
# src\main\java\com\dragonmeow\mctranslator\{cache,config,service,style,translate}.
#
#   powershell -ExecutionPolicy Bypass -File .\sync-core.ps1
#
# Per-tree glue packages (fabric / fabric26 / neoforge) are never touched.
# Fabric 1.17.1 and the Java 8 ports require manual compatibility ports and are
# intentionally excluded from this mechanical copy list.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$corePackages = 'cache', 'config', 'service', 'style', 'translate'
$trees = 'fabric1182', 'fabric1194', 'fabric120', 'fabric12111',
    'fabric2612', 'fabric26', 'neoforge', 'neoforge120', 'neoforge26'

$copied = 0
foreach ($tree in $trees) {
    foreach ($pkg in $corePackages) {
        $srcDir = Join-Path $root "src\main\java\com\dragonmeow\mctranslator\$pkg"
        $dstDir = Join-Path $root "$tree\src\main\java\com\dragonmeow\mctranslator\$pkg"
        if (-not (Test-Path $srcDir)) { continue }
        New-Item -ItemType Directory -Force -Path $dstDir | Out-Null

        # Copy new/changed files.
        foreach ($f in Get-ChildItem $srcDir -Filter *.java) {
            $dst = Join-Path $dstDir $f.Name
            if (-not (Test-Path $dst) -or
                (Get-FileHash $f.FullName -Algorithm MD5).Hash -ne (Get-FileHash $dst -Algorithm MD5).Hash) {
                Copy-Item -Force $f.FullName $dst
                Write-Output "sync: $tree\$pkg\$($f.Name)"
                $copied++
            }
        }
        # Flag strays that exist only in a tree (never delete automatically).
        foreach ($f in Get-ChildItem $dstDir -Filter *.java) {
            if (-not (Test-Path (Join-Path $srcDir $f.Name))) {
                Write-Warning "only in ${tree}: $pkg\$($f.Name) (not in root core - review manually)"
            }
        }
    }
}
Write-Output "done: $copied file(s) synced"
