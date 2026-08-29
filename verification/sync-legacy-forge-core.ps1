[CmdletBinding()]
param([switch]$Check)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$canonicalRoot = Join-Path $repoRoot `
    'fabric1144\src\main\java\com\dragonmeow\mctranslator\legacy'
$targets = @(
    (Join-Path $repoRoot `
        'forge1122\src\main\java\com\dragonmeow\mctranslator\forgelegacy'),
    (Join-Path $repoRoot `
        'forge1132\src\main\java\com\dragonmeow\mctranslator\forgelegacy')
)
$files = @(
    'LegacyConfig.java',
    'LegacyChatDeliveryQueue.java',
    'LegacyChatRequestProfile.java',
    'LegacyCodexClient.java',
    'LegacyMachineProvider.java',
    'LegacySessionTokenUsage.java',
    'LegacyTemplateText.java',
    'LegacyTranslator.java'
)
$canonicalPackage = 'com.dragonmeow.mctranslator.legacy'
$forgePackage = 'com.dragonmeow.mctranslator.forgelegacy'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$changed = 0

function Require {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

Require (Test-Path -LiteralPath $canonicalRoot -PathType Container) `
    "Canonical legacy root is missing: $canonicalRoot"
Require ($files.Count -eq 8 -and
        @($files | Select-Object -Unique).Count -eq 8) `
    'Forge canonical transform must contain exactly eight unique files'

foreach ($targetRoot in $targets) {
    Require (Test-Path -LiteralPath $targetRoot -PathType Container) `
        "Forge target root is missing: $targetRoot"
    foreach ($name in $files) {
        $source = Join-Path $canonicalRoot $name
        $destination = Join-Path $targetRoot $name
        Require (Test-Path -LiteralPath $source -PathType Leaf) `
            "Canonical source is missing: $source"
        $canonical = [System.IO.File]::ReadAllText(
            $source, [System.Text.Encoding]::UTF8)
        $declaration = "package $canonicalPackage;"
        Require ([regex]::Matches(
                $canonical, [regex]::Escape($declaration)).Count -eq 1) `
            "Canonical package declaration drifted: $source"
        $transformed = $canonical.Replace($canonicalPackage, $forgePackage)
        Require (-not $transformed.Contains($canonicalPackage) -and
                $transformed.Contains("package $forgePackage;")) `
            "Package transform was incomplete: $source"

        $existing = if (Test-Path -LiteralPath $destination -PathType Leaf) {
            [System.IO.File]::ReadAllText(
                $destination, [System.Text.Encoding]::UTF8)
        } else {
            $null
        }
        if ($null -eq $existing -or $existing -cne $transformed) {
            Require (-not $Check) `
                "Forge canonical core is out of sync: $destination"
            [System.IO.File]::WriteAllText($destination, $transformed, $utf8NoBom)
            $changed++
            Write-Output "SYNC_FORGE_CORE file=$destination"
        }

        $installed = [System.IO.File]::ReadAllText(
            $destination, [System.Text.Encoding]::UTF8)
        $normalized = $installed.Replace($forgePackage, $canonicalPackage)
        Require ($normalized -ceq $canonical) `
            "Forge core is not a package-only canonical transform: $destination"
    }
}

if ($Check) {
    Require ($changed -eq 0) 'Check mode unexpectedly changed a file'
}
Write-Output ("SYNC_FORGE_CORE_OK files=16 changed={0} check={1}" -f
    $changed, $Check.IsPresent)
