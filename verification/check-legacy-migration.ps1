# Compiles the Java 8 legacy LegacyDataMigration (fabric1144 is the canonical copy) with --release 8
# and runs the check in verification\legacy. No Minecraft, no network, only temporary folders.
#
#   powershell -ExecutionPolicy Bypass -File .\verification\check-legacy-migration.ps1
param([string]$Jdk = 'C:\Program Files\Java\jdk-21')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gson = Get-ChildItem -Recurse -Filter 'gson-2*.jar' "$env:USERPROFILE\.gradle\caches\modules-2" |
    Where-Object { $_.Name -notmatch 'sources' } | Sort-Object Name | Select-Object -Last 1
if (-not $gson) { throw 'no gson jar found in the Gradle cache' }
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('legacy-migration-check-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $out | Out-Null
try {
    $legacy = Join-Path $root 'fabric1144\src\main\java\com\dragonmeow\nyanlex'
    & "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -cp $gson.FullName -d $out `
        "$legacy\legacy\LegacyDataMigration.java" "$legacy\translate\TranslationFile.java" `
        (Join-Path $root 'verification\legacy\com\dragonmeow\nyanlex\legacy\LegacyDataMigrationCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'compile failed' }
    & "$Jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$out;$($gson.FullName)" `
        com.dragonmeow.nyanlex.legacy.LegacyDataMigrationCheck
    if ($LASTEXITCODE -ne 0) { throw 'legacy migration check failed' }
} finally {
    Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
}
