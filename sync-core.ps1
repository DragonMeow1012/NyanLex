# Mirror the MC-agnostic core packages from the root tree (src\) — the canonical,
# unit-tested copy — into the modern source-compatible loader trees. Run after
# ANY edit under
# src\main\java\com\dragonmeow\nyanlex\{cache,config,hub,service,style,translate,warmup}.
#
#   powershell -ExecutionPolicy Bypass -File .\sync-core.ps1
#   powershell -ExecutionPolicy Bypass -File .\sync-core.ps1 -Check   # report drift only, change nothing
#
# Per-tree glue packages (fabric / fabric26 / neoforge) are never touched.
# Fabric 1.17.1 shares this core through the explicit Java 16/Gson compatibility
# rules below. The separate Java 8 ports remain manual ports.

param([switch]$Check)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$corePackages = 'cache', 'config', 'hub', 'service', 'style', 'translate', 'warmup'
$trees = 'fabric1182', 'fabric1194', 'fabric120', 'fabric12111',
    'fabric2612', 'fabric26', 'neoforge', 'neoforge120', 'neoforge26'

$copied = 0

# Copies a file, or, with -Check, only reports that it would have been copied.
function Put-File {
    param([string]$Source, [string]$Destination)
    if (-not $Check) {
        $parent = Split-Path -Parent $Destination
        if (-not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
        Copy-Item -Force $Source $Destination
    }
}

function Get-RelativeChildPath {
    param(
        [Parameter(Mandatory = $true)][string]$Base,
        [Parameter(Mandatory = $true)][string]$Child
    )
    $prefix = $Base.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $Child.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is not below expected root: $Child (root $Base)"
    }
    return $Child.Substring($prefix.Length)
}

# Authoring utilities under hub\tool are intentionally repository-only. They are
# not runtime core and must never be copied into loader source trees.
function Test-ExcludedCoreChild {
    param(
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$Relative
    )
    return $Package -eq 'hub' -and $Relative -like 'tool\*'
}

foreach ($tree in $trees) {
    foreach ($pkg in $corePackages) {
        $srcDir = Join-Path $root "src\main\java\com\dragonmeow\nyanlex\$pkg"
        $dstDir = Join-Path $root "$tree\src\main\java\com\dragonmeow\nyanlex\$pkg"
        if (-not (Test-Path $srcDir)) { continue }
        if (-not $Check) { New-Item -ItemType Directory -Force -Path $dstDir | Out-Null }

        # Copy new/changed files while preserving nested core packages.
        foreach ($f in Get-ChildItem $srcDir -Filter *.java -File -Recurse) {
            $insidePackage = Get-RelativeChildPath $srcDir $f.FullName
            if (Test-ExcludedCoreChild -Package $pkg -Relative $insidePackage) { continue }
            $dst = Join-Path $dstDir $insidePackage
            if (-not (Test-Path $dst) -or
                (Get-FileHash $f.FullName -Algorithm MD5).Hash -ne (Get-FileHash $dst -Algorithm MD5).Hash) {
                Put-File $f.FullName $dst
                Write-Output "sync: $tree\$pkg\$insidePackage"
                $copied++
            }
        }
        # Flag strays that exist only in a tree (never delete automatically).
        if (Test-Path -LiteralPath $dstDir) {
            foreach ($f in Get-ChildItem $dstDir -Filter *.java -File -Recurse) {
                $insidePackage = Get-RelativeChildPath $dstDir $f.FullName
                if (Test-ExcludedCoreChild -Package $pkg -Relative $insidePackage) { continue }
                if (-not (Test-Path (Join-Path $srcDir $insidePackage))) {
                    Write-Warning "only in ${tree}: $pkg\$insidePackage (not in root core - review manually)"
                }
            }
        }
    }
}

# Fabric 1.17.1 is source-compatible with the canonical core except for its
# Minecraft-bundled Gson version and two translator files that carry broader
# version-specific adaptations. Keep the compatibility boundary explicit:
# newly added common files are mirrored automatically, while a changed expected
# replacement fails loudly instead of silently dropping an old-runtime fix.
$fabric1171Excluded = @(
    'translate\GoogleResponseParser.java',
    'translate\OpenAiTranslator.java'
)

function Replace-Expected {
    param(
        [Parameter(Mandatory = $true)][string]$Content,
        [Parameter(Mandatory = $true)][string]$From,
        [Parameter(Mandatory = $true)][string]$To,
        [Parameter(Mandatory = $true)][int]$ExpectedCount,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $actual = ([regex]::Matches($Content, [regex]::Escape($From))).Count
    if ($actual -ne $ExpectedCount) {
        throw ('fabric1171 compatibility rule drifted for {0}: expected {1} occurrence(s), found {2}' -f $Label, $ExpectedCount, $actual)
    }
    return $Content.Replace($From, $To)
}

function Convert-Fabric1171Core {
    param(
        [Parameter(Mandatory = $true)][string]$Relative,
        [Parameter(Mandatory = $true)][string]$Content
    )

    switch ($Relative) {
        'cache\FileStore.java' {
            return Replace-Expected -Content $Content -From 'JsonParser.parseString(' -To 'new JsonParser().parse(' -ExpectedCount 4 -Label $Relative
        }
        'translate\CodexAppServerClient.java' {
            return Replace-Expected -Content $Content -From 'JsonParser.parseString(' -To 'new JsonParser().parse(' -ExpectedCount 2 -Label $Relative
        }
        'service\ChatDeliveryQueue.java' {
            return Replace-Expected -Content $Content -From 'new IdentityHashMap<>()' -To 'new IdentityHashMap<T, Boolean>()' -ExpectedCount 1 -Label $Relative
        }
        default {
            return $Content
        }
    }
}

$fabric1171Base = Join-Path $root 'fabric1171\src\main\java\com\dragonmeow\nyanlex'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
foreach ($pkg in $corePackages) {
    $srcDir = Join-Path $root "src\main\java\com\dragonmeow\nyanlex\$pkg"
    $dstDir = Join-Path $fabric1171Base $pkg
    if (-not (Test-Path $srcDir)) { continue }
    if (-not $Check) { New-Item -ItemType Directory -Force -Path $dstDir | Out-Null }

    foreach ($f in Get-ChildItem $srcDir -Filter *.java -File -Recurse) {
        $insidePackage = Get-RelativeChildPath $srcDir $f.FullName
        if (Test-ExcludedCoreChild -Package $pkg -Relative $insidePackage) { continue }
        $relative = "$pkg\$insidePackage"
        if ($fabric1171Excluded -contains $relative) { continue }

        $content = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
        $content = Convert-Fabric1171Core $relative $content
        $dst = Join-Path $dstDir $insidePackage
        $existing = if (Test-Path $dst) {
            Get-Content -LiteralPath $dst -Raw -Encoding UTF8
        } else {
            $null
        }
        if ($null -eq $existing -or $existing -cne $content) {
            if (-not $Check) {
                $parent = Split-Path -Parent $dst
                if (-not (Test-Path -LiteralPath $parent)) {
                    New-Item -ItemType Directory -Force -Path $parent | Out-Null
                }
                [System.IO.File]::WriteAllText($dst, $content, $utf8NoBom)
            }
            Write-Output "sync: fabric1171\$relative"
            $copied++
        }
    }

    if (Test-Path -LiteralPath $dstDir) {
        foreach ($f in Get-ChildItem $dstDir -Filter *.java -File -Recurse) {
            $insidePackage = Get-RelativeChildPath $dstDir $f.FullName
            if (Test-ExcludedCoreChild -Package $pkg -Relative $insidePackage) { continue }
            $relative = "$pkg\$insidePackage"
            if ($fabric1171Excluded -contains $relative) { continue }
            if (-not (Test-Path (Join-Path $srcDir $insidePackage))) {
                Write-Warning "only in fabric1171: $relative (not in root core - review manually)"
            }
        }
    }
}

# Fabric 1.21.11 is the second modern target that runs the Minecraft-agnostic
# JUnit suite. Keep that suite on the same canonical root copy as the core it
# exercises; otherwise a source sync can leave the target asserting an obsolete
# cache/schema contract. FabricTextStyleIntegrationTest is deliberately target-
# specific and is excluded by fabric12111/build.gradle, so it remains a manual
# API-version port.
$testSrcDir = Join-Path $root 'src\test\java\com\dragonmeow\nyanlex'
$testDstDir = Join-Path $root 'fabric12111\src\test\java\com\dragonmeow\nyanlex'
foreach ($f in Get-ChildItem $testSrcDir -Filter *.java -Recurse) {
    if ($f.Name -eq 'FabricTextStyleIntegrationTest.java') { continue }
    # SettingsCatalogTest reads the nyanlex.settings.* lang keys, which only the root and
    # fabric2612 trees ship (they are the only ones with the tabbed settings screen).
    if ($f.Name -in 'SettingsCatalogTest.java', 'SettingsModelTest.java', 'SettingsPanelTest.java') { continue }
    # The questionnaire, the manual, the shared dialog card, the lang-file guard and the no-chat-message
    # check belong to the new screens and sources of the root and fabric2612 trees.
    if ($f.Name -in 'DialogPanelTest.java', 'QuickSetupPanelTest.java', 'ManualPanelTest.java',
            'LangFilesTest.java', 'NoChatMessagesTest.java', 'WarmupConfirmDialogTest.java') { continue }
    $relative = $f.FullName.Substring($testSrcDir.Length + 1)
    # hub.tool is an author-only sub-package (HubExportTool/ChatLineClassifier/
    # UnmaskedNameConverter) this script deliberately never mirrors into any tree's
    # MAIN sources (see $corePackages above); its tests must not be mirrored here
    # either, or fabric12111's test compile fails on the missing main-source classes.
    if ($relative -like 'hub\tool\*') { continue }
    $dst = Join-Path $testDstDir $relative
    if (-not (Test-Path $dst) -or
        (Get-FileHash $f.FullName -Algorithm MD5).Hash -ne (Get-FileHash $dst -Algorithm MD5).Hash) {
        Put-File $f.FullName $dst
        Write-Output "sync: fabric12111\test\$relative"
        $copied++
    }
}
# Java 8 compatible boundaries shared unchanged by every loader.
# (DebugErrorLog is intentionally not in this list; see the Java 8 block below.)
foreach ($legacyTarget in @('fabric1144', 'fabric1152', 'fabric1165', 'forge1122', 'forge1132')) {
    $sharedDestination = Join-Path $root "$legacyTarget\src\main\java\com\dragonmeow\nyanlex\translate"
    foreach ($sharedName in @('ScreenTranslationCapture.java', 'TranslationFile.java', 'TranslationFileDialog.java',
            'HookHealth.java', 'HookGuard.java', 'ChatComposerPanel.java',
            'MachineTranslationGate.java', 'RequestsPausedException.java', 'RequestGate.java',
            'RequestPacer.java', 'CliProcessSupport.java', 'CliRequestLimits.java', 'HttpEndpointPolicy.java',
            'ApiModelCatalog.java', 'AnthropicWire.java', 'AiFeatureMemory.java')) {
        $sharedSource = Join-Path $root "src\main\java\com\dragonmeow\nyanlex\translate\$sharedName"
        $sharedFile = Join-Path $sharedDestination $sharedName
        if (-not (Test-Path $sharedFile) -or (Get-FileHash $sharedSource).Hash -ne (Get-FileHash $sharedFile).Hash) {
            Put-File $sharedSource $sharedFile
            $copied++
        }
    }
}
# SecretText is intentionally Java 8-compatible and is the canonical masking helper for every
# API-key screen, including the five legacy loaders whose other config classes are platform ports.
$secretSource = Join-Path $root 'src\main\java\com\dragonmeow\nyanlex\config\SecretText.java'
foreach ($legacyTarget in @('fabric1144', 'fabric1152', 'fabric1165', 'forge1122', 'forge1132')) {
    $secretDestination = Join-Path $root "$legacyTarget\src\main\java\com\dragonmeow\nyanlex\config"
    $secretFile = Join-Path $secretDestination 'SecretText.java'
    if (-not (Test-Path $secretFile) -or
        (Get-FileHash $secretSource).Hash -ne (Get-FileHash $secretFile).Hash) {
        Put-File $secretSource $secretFile
        Write-Output "sync: $legacyTarget\config\SecretText.java (java8)"
        $copied++
    }
}
# The Java 8 DebugErrorLog is a hand-maintained trimmed port (the root one needs Java 11+ APIs and
# OpenAiTranslator.ExchangeDumpSink, which the old trees do not have). fabric1144 holds the canonical
# Java 8 copy; the other four old trees mirror it unchanged. It is deliberately NOT taken from root.
$java8Canonical = Join-Path $root 'fabric1144\src\main\java\com\dragonmeow\nyanlex\translate\DebugErrorLog.java'
if (-not (Test-Path $java8Canonical)) { throw "Java 8 DebugErrorLog missing: $java8Canonical" }
foreach ($legacyTarget in @('fabric1152', 'fabric1165', 'forge1122', 'forge1132')) {
    $dstDir = Join-Path $root "$legacyTarget\src\main\java\com\dragonmeow\nyanlex\translate"
    $dst = Join-Path $dstDir 'DebugErrorLog.java'
    if (-not (Test-Path $dst) -or (Get-FileHash $java8Canonical).Hash -ne (Get-FileHash $dst).Hash) {
        Put-File $java8Canonical $dst
        Write-Output "sync: $legacyTarget\translate\DebugErrorLog.java (java8)"
        $copied++
    }
}
if ($Check) {
    Write-Output "check: $copied file(s) drifted"
    if ($copied -gt 0) { exit 1 }
} else {
    Write-Output "done: $copied file(s) synced"
}
