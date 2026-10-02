[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
. (Join-Path $PSScriptRoot 'release-transaction.ps1')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$releaseVersion = '1.0.0'
$repoRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$modsJarRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'mods-jar'))
$releaseRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $modsJarRoot $releaseVersion))
$transactionId = [guid]::NewGuid().ToString('N')
$stageRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $modsJarRoot ".$releaseVersion.staging-$transactionId"))
$backupRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $modsJarRoot ".$releaseVersion.backup-$transactionId"))
$failedInstallRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $modsJarRoot ".$releaseVersion.failed-$transactionId"))
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Require {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) { throw $Message }
}

function Get-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Relative)

    $full = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Relative))
    $trimChars = [char[]]@(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $prefix = $repoRoot.TrimEnd($trimChars) +
        [System.IO.Path]::DirectorySeparatorChar
    Require ($full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Source escapes repository root: $Relative"
    return $full
}

function Test-PathIsWithin {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [bool]$AllowRoot = $false
    )

    $full = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root)
    if ($AllowRoot -and $full.Equals(
            $fullRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    $trimChars = [char[]]@(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $prefix = $fullRoot.TrimEnd($trimChars) +
        [System.IO.Path]::DirectorySeparatorChar
    return $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-PackagePath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Relative
    )

    Require (-not [string]::IsNullOrWhiteSpace($Relative)) `
        'Release-relative paths must not be empty'
    Require (-not $Relative.Contains('\')) `
        "Release-relative paths must use forward slashes: $Relative"
    Require (-not $Relative.StartsWith('/')) `
        "Unsafe release-relative path: $Relative"
    $segments = @($Relative.Split('/'))
    Require ($segments.Count -gt 0 -and
            @($segments | Where-Object {
                [string]::IsNullOrEmpty($_) -or $_ -ceq '.' -or $_ -ceq '..' -or
                $_.Contains(':')
            }).Count -eq 0) `
        "Unsafe release-relative path: $Relative"
    $platformRelative = $Relative.Replace(
        [char]'/', [System.IO.Path]::DirectorySeparatorChar)
    $full = [System.IO.Path]::GetFullPath((Join-Path $Root $platformRelative))
    Require (Test-PathIsWithin $full $Root) "Output escapes package root: $Relative"
    return $full
}

function Assert-NoReparseAncestors {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $cursor = [System.IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            $isReparse = ([int]$item.Attributes -band
                [int][System.IO.FileAttributes]::ReparsePoint) -ne 0
            Require (-not $isReparse) `
                "$Description contains a reparse point: $cursor"
        }
        $parent = [System.IO.Path]::GetDirectoryName($cursor)
        if ([string]::IsNullOrEmpty($parent) -or $parent.Equals(
                $cursor, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $cursor = $parent
    }
}

function Assert-NoReparseTree {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Assert-NoReparseAncestors $Root $Description
    if (-not (Test-Path -LiteralPath $Root)) { return }
    Require (Test-Path -LiteralPath $Root -PathType Container) `
        "$Description is not a directory: $Root"

    $pending = [System.Collections.Stack]::new()
    $pending.Push([System.IO.Path]::GetFullPath($Root))
    while ($pending.Count -gt 0) {
        $directory = [string]$pending.Pop()
        foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force)) {
            $isReparse = ([int]$item.Attributes -band
                [int][System.IO.FileAttributes]::ReparsePoint) -ne 0
            Require (-not $isReparse) `
                "$Description contains a reparse point: $($item.FullName)"
            if ($item.PSIsContainer) {
                $pending.Push($item.FullName)
            }
        }
    }
}

function Get-FileSha256Lower {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-StreamSha256Lower {
    param([Parameter(Mandatory = $true)][System.IO.Stream]$Stream)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash($Stream)
        return -join @($bytes | ForEach-Object { $_.ToString('x2') })
    } finally {
        $sha.Dispose()
    }
}

function Assert-OutputTarget {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Relative
    )

    $target = Get-PackagePath $Root $Relative
    Assert-NoReparseAncestors $target "Release output $Relative"
    if (Test-Path -LiteralPath $target) {
        Require (Test-Path -LiteralPath $target -PathType Leaf) `
            "Release output path is not a file: $Relative"
    }

    $parent = Split-Path -Parent $target
    while (-not $parent.Equals(
            $Root, [System.StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $parent) {
            Require (Test-Path -LiteralPath $parent -PathType Container) `
                "Release output parent is not a directory: $parent"
        }
        $nextParent = Split-Path -Parent $parent
        Require (-not $nextParent.Equals(
                $parent, [System.StringComparison]::OrdinalIgnoreCase)) `
            "Could not reach release root while validating: $Relative"
        $parent = $nextParent
    }
}

function Get-ExpectedPackageDirectories {
    param([Parameter(Mandatory = $true)][string[]]$AllowedFiles)

    $directories = @()
    foreach ($relative in $AllowedFiles) {
        $parts = @($relative.Split('/'))
        for ($count = 1; $count -lt $parts.Count; $count++) {
            $directories += [string]::Join('/', $parts[0..($count - 1)])
        }
    }
    return @($directories | Sort-Object -Unique)
}

function Assert-ExactPackageTree {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$AllowedFiles,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Assert-NoReparseTree $Root $Description
    Require (Test-Path -LiteralPath $Root -PathType Container) `
        "$Description does not exist: $Root"

    # -Force is intentional: hidden/system entries are release contents too.
    $actualFiles = @(Get-ChildItem -LiteralPath $Root -Force -Recurse -File |
        ForEach-Object {
            $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
        } | Sort-Object)
    $expectedFiles = @($AllowedFiles | Sort-Object)
    Require ($actualFiles.Count -eq $expectedFiles.Count -and
            [string]::Join("`n", $actualFiles) -ceq
            [string]::Join("`n", $expectedFiles)) `
        ("$Description file set is not the exact case-sensitive release map. " +
            "Actual: " + ($actualFiles -join ', '))

    $actualDirectories = @(Get-ChildItem -LiteralPath $Root -Force -Recurse -Directory |
        ForEach-Object {
            $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
        } | Sort-Object)
    $expectedDirectories = @(Get-ExpectedPackageDirectories $AllowedFiles |
        Sort-Object)
    Require ($actualDirectories.Count -eq $expectedDirectories.Count -and
            [string]::Join("`n", $actualDirectories) -ceq
            [string]::Join("`n", $expectedDirectories)) `
        ("$Description directory set is not the exact case-sensitive release map. " +
            "Actual: " + ($actualDirectories -join ', '))
}

function Assert-PackageMatchesChecksumLines {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$ChecksumLines,
        [Parameter(Mandatory = $true)][System.Text.Encoding]$Encoding,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Require ($ChecksumLines.Count -eq 22) `
        "$Description expected exactly 22 checksum lines"
    $manifestPath = Get-PackagePath $Root 'SHA256SUMS.txt'
    $actualLines = [System.IO.File]::ReadAllLines($manifestPath, $Encoding)
    Require ([string]::Join("`n", $actualLines) -ceq
            [string]::Join("`n", $ChecksumLines)) `
        "$Description SHA256SUMS content or ordering changed"
    foreach ($line in $ChecksumLines) {
        $match = [regex]::Match(
            $line, '^(?<hash>[0-9a-f]{64}) \*(?<path>[^\\]+)$')
        Require ($match.Success) "$Description has a malformed checksum line: $line"
        $relative = $match.Groups['path'].Value
        Require ($relative -cnotmatch '(^|/)SHA256SUMS\.txt$') `
            "$Description checksum unexpectedly references itself"
        $target = Get-PackagePath $Root $relative
        Require (Test-Path -LiteralPath $target -PathType Leaf) `
            "$Description checksum target is missing: $relative"
        Require ((Get-FileSha256Lower $target) -ceq $match.Groups['hash'].Value) `
            "$Description hash mismatch: $relative"
    }
}

function Remove-ValidatedPackageTree {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $full = [System.IO.Path]::GetFullPath($Path)
    Require ($full -ceq $stageRoot -or $full -ceq $backupRoot) `
        "Refusing to delete an unexpected path for ${Description}: $full"
    Require (Test-PathIsWithin $full $modsJarRoot) `
        "Refusing to delete outside mods-jar for ${Description}: $full"
    Require ([System.IO.Path]::GetDirectoryName($full).Equals(
            $modsJarRoot, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Refusing to delete a non-sibling transaction path: $full"
    Require (-not $full.Equals(
            $modsJarRoot, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Refusing to delete mods-jar itself for ${Description}"
    Assert-NoReparseTree $full $Description
    Require (Test-Path -LiteralPath $full -PathType Container) `
        "Refusing to recursively delete a non-directory: $full"
    Remove-Item -LiteralPath $full -Recurse -Force
}

function Read-ZipEntryText {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][string]$EntryName
    )

    $entry = $Archive.GetEntry($EntryName)
    Require ($null -ne $entry) "Missing JAR metadata entry: $EntryName"
    $stream = $entry.Open()
    try {
        $reader = [System.IO.StreamReader]::new(
            $stream, [System.Text.Encoding]::UTF8, $true, 4096, $false)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-ManifestValue {
    param(
        [Parameter(Mandatory = $true)][string]$Manifest,
        [Parameter(Mandatory = $true)][string]$Key
    )

    $mainSectionEnd = [regex]::Match($Manifest, '\r?\n\r?\n')
    $mainSection = if ($mainSectionEnd.Success) {
        $Manifest.Substring(0, $mainSectionEnd.Index)
    } else {
        $Manifest
    }
    $pattern = '(?m)^{0}:\s*(?<value>[^\r\n]+)\s*$' -f
        [regex]::Escape($Key)
    $matches = [regex]::Matches($mainSection, $pattern)
    return $(if ($matches.Count -eq 1) {
        $matches[0].Groups['value'].Value.Trim()
    } else {
        $null
    })
}

function Get-TomlValue {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Key
    )

    $pattern = '(?m)^\s*{0}\s*=\s*"(?<value>[^"]*)"\s*$' -f
        [regex]::Escape($Key)
    $match = [regex]::Match($Text, $pattern)
    return $(if ($match.Success) { $match.Groups['value'].Value } else { $null })
}

function Get-TomlBlocks {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Header
    )

    $pattern = '(?ms)^\s*\[\[{0}\]\]\s*(?<body>.*?)(?=^\s*\[\[|\z)' -f
        [regex]::Escape($Header)
    return @([regex]::Matches($Text, $pattern) | ForEach-Object {
        $_.Groups['body'].Value
    })
}

function Assert-JarHasClass {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][string]$ClassName
    )

    $entryName = $ClassName.Replace('.', '/') + '.class'
    Require ($null -ne $Archive.GetEntry($entryName)) `
        "Missing loader entrypoint class: $entryName"
}

function Assert-FabricMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Artifact
    )

    $metadata = (Read-ZipEntryText $Archive 'fabric.mod.json') | ConvertFrom-Json
    Require ([string]$metadata.id -ceq 'nyanslate') `
        "$($Artifact.Source) has wrong Fabric mod id"
    Require ([string]$metadata.version -ceq $releaseVersion) `
        "$($Artifact.Source) has wrong Fabric version: $($metadata.version)"
    Require ([string]$metadata.environment -ceq 'client') `
        "$($Artifact.Source) is not marked client-only"
    Require ([string]$metadata.depends.minecraft -ceq $Artifact.MinecraftRange) `
        "$($Artifact.Source) has wrong Minecraft dependency: $($metadata.depends.minecraft)"
    Require ([string]$metadata.depends.fabricloader -ceq $Artifact.LoaderRange) `
        "$($Artifact.Source) has wrong Fabric Loader range: $($metadata.depends.fabricloader)"
    Require ([string]$metadata.depends.java -ceq $Artifact.JavaRange) `
        "$($Artifact.Source) has wrong Java range: $($metadata.depends.java)"

    $clientEntrypoints = @($metadata.entrypoints.client)
    Require ($clientEntrypoints.Count -eq 1) `
        "$($Artifact.Source) must contain exactly one Fabric client entrypoint"
    Require ([string]$clientEntrypoints[0] -ceq $Artifact.MainClass) `
        "$($Artifact.Source) has wrong Fabric client entrypoint: $($clientEntrypoints[0])"
    Assert-JarHasClass $Archive $Artifact.MainClass
}

function Assert-Forge1122Metadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Artifact
    )

    $entries = @((Read-ZipEntryText $Archive 'mcmod.info') | ConvertFrom-Json)
    $matches = @($entries | Where-Object { [string]$_.modid -ceq 'nyanslate' })
    Require ($matches.Count -eq 1) `
        "$($Artifact.Source) must contain one nyanslate mcmod.info record"
    $metadata = $matches[0]
    Require ([string]$metadata.version -ceq $releaseVersion) `
        "$($Artifact.Source) has wrong Forge mod version: $($metadata.version)"
    Require ([string]$metadata.mcversion -ceq $Artifact.MinecraftRange) `
        "$($Artifact.Source) has wrong Minecraft version: $($metadata.mcversion)"

    $manifest = Read-ZipEntryText $Archive 'META-INF/MANIFEST.MF'
    $implementationVersion = Get-ManifestValue $manifest 'Implementation-Version'
    Require ($implementationVersion -ceq $releaseVersion) `
        "$($Artifact.Source) has wrong manifest version: $implementationVersion"
    Assert-JarHasClass $Archive $Artifact.MainClass
}

function Assert-TomlMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Artifact
    )

    $toml = Read-ZipEntryText $Archive $Artifact.MetadataEntry
    Require ((Get-TomlValue $toml 'modLoader') -ceq 'javafml') `
        "$($Artifact.Source) has wrong modLoader"
    Require ((Get-TomlValue $toml 'loaderVersion') -ceq $Artifact.LoaderRange) `
        "$($Artifact.Source) has wrong loaderVersion"

    $modBlocks = @(Get-TomlBlocks $toml 'mods')
    $mainMods = @($modBlocks | Where-Object {
        (Get-TomlValue $_ 'modId') -ceq 'nyanslate'
    })
    Require ($mainMods.Count -eq 1) `
        "$($Artifact.Source) must contain one nyanslate TOML mod block"
    $tomlVersion = Get-TomlValue $mainMods[0] 'version'
    Require ($tomlVersion -ceq $Artifact.TomlVersion) `
        "$($Artifact.Source) has wrong TOML version: $tomlVersion"

    $dependencies = @(Get-TomlBlocks $toml 'dependencies.nyanslate')
    $minecraftDependencies = @($dependencies | Where-Object {
        (Get-TomlValue $_ 'modId') -ceq 'minecraft'
    })
    Require ($minecraftDependencies.Count -eq 1) `
        "$($Artifact.Source) must contain one Minecraft dependency"
    $minecraftRange = Get-TomlValue $minecraftDependencies[0] 'versionRange'
    Require ($minecraftRange -ceq $Artifact.MinecraftRange) `
        "$($Artifact.Source) has wrong Minecraft range: $minecraftRange"

    $loaderDependencies = @($dependencies | Where-Object {
        (Get-TomlValue $_ 'modId') -ceq $Artifact.LoaderDependency
    })
    Require ($loaderDependencies.Count -eq 1) `
        "$($Artifact.Source) must contain one $($Artifact.LoaderDependency) dependency"
    $loaderDependencyRange = Get-TomlValue $loaderDependencies[0] 'versionRange'
    Require ($loaderDependencyRange -ceq $Artifact.LoaderDependencyRange) `
        "$($Artifact.Source) has wrong loader dependency range: $loaderDependencyRange"

    if ($Artifact.TomlVersion -ceq ('$' + '{file.jarVersion}')) {
        $manifest = Read-ZipEntryText $Archive 'META-INF/MANIFEST.MF'
        $implementationVersion = Get-ManifestValue $manifest 'Implementation-Version'
        Require ($implementationVersion -ceq $releaseVersion) `
            "$($Artifact.Source) has wrong manifest version: $implementationVersion"
    }
    Assert-JarHasClass $Archive $Artifact.MainClass
}

function Assert-SourceJar {
    param(
        [Parameter(Mandatory = $true)]$Artifact,
        [Parameter(Mandatory = $true)][string]$Path,
        [switch]$RequireBuildLibs
    )

    $jarPath = [System.IO.Path]::GetFullPath($Path)
    Assert-NoReparseAncestors $jarPath "JAR artifact $($Artifact.Source)"
    Require (Test-Path -LiteralPath $jarPath -PathType Leaf) `
        "Missing JAR artifact for $($Artifact.Source): $jarPath"
    $expectedName = "nyanslate-$releaseVersion-$($Artifact.Label)-$($Artifact.Minecraft).jar"
    Require ((Split-Path -Leaf $jarPath) -ceq $expectedName) `
        "Unexpected JAR filename for $($Artifact.Source); expected $expectedName"
    Require ([System.IO.Path]::GetFileName($Artifact.Relative) -ceq $expectedName) `
        "Destination filename does not match source: $($Artifact.Relative)"
    if ($RequireBuildLibs) {
        Require ($jarPath.IndexOf(
                [System.IO.Path]::DirectorySeparatorChar + 'build' +
                [System.IO.Path]::DirectorySeparatorChar + 'libs' +
                [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase) -ge 0) `
            "Source is not a build/libs artifact: $($Artifact.Source)"
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $duplicateEntries = @($archive.Entries |
            Group-Object FullName | Where-Object { $_.Count -ne 1 })
        Require ($duplicateEntries.Count -eq 0) `
            "$($Artifact.Source) contains duplicate ZIP entries"
        switch ($Artifact.MetadataKind) {
            'fabric' { Assert-FabricMetadata $archive $Artifact }
            'forge1122' { Assert-Forge1122Metadata $archive $Artifact }
            'toml' { Assert-TomlMetadata $archive $Artifact }
            default { throw "Unknown metadata kind: $($Artifact.MetadataKind)" }
        }
    } finally {
        $archive.Dispose()
    }
}

# The source and destination of every publishable JAR are intentionally explicit.
# Adding a release target requires a reviewed row here and a matching ZIP count below.
$artifacts = @(
    [pscustomobject]@{ Loader = 'forge'; Label = 'Forge'; Minecraft = '1.12.2'; Source = "forge1122\build\libs\nyanslate-$releaseVersion-Forge-1.12.2.jar"; Relative = "forge/1.12.2/nyanslate-$releaseVersion-Forge-1.12.2.jar"; MetadataKind = 'forge1122'; MinecraftRange = '1.12.2'; MainClass = 'com.dragonmeow.nyanslate.forgelegacy.NyanslateForge' },
    [pscustomobject]@{ Loader = 'forge'; Label = 'Forge'; Minecraft = '1.13.2'; Source = "forge1132\build\libs\nyanslate-$releaseVersion-Forge-1.13.2.jar"; Relative = "forge/1.13.2/nyanslate-$releaseVersion-Forge-1.13.2.jar"; MetadataKind = 'toml'; MetadataEntry = 'META-INF/mods.toml'; TomlVersion = ('$' + '{file.jarVersion}'); MinecraftRange = '[1.13.2]'; LoaderRange = '[25,)'; LoaderDependency = 'forge'; LoaderDependencyRange = '[25,)'; MainClass = 'com.dragonmeow.nyanslate.forgelegacy.NyanslateForge' },

    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.14.4'; Source = "fabric1144\build\libs\nyanslate-$releaseVersion-Fabric-1.14.4.jar"; Relative = "fabric/1.14.4/nyanslate-$releaseVersion-Fabric-1.14.4.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.14.4'; LoaderRange = '>=0.16.0'; JavaRange = '>=8'; MainClass = 'com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.15.2'; Source = "fabric1152\build\libs\nyanslate-$releaseVersion-Fabric-1.15.2.jar"; Relative = "fabric/1.15.2/nyanslate-$releaseVersion-Fabric-1.15.2.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.15.2'; LoaderRange = '>=0.16.0'; JavaRange = '>=8'; MainClass = 'com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.16.5'; Source = "fabric1165\build\libs\nyanslate-$releaseVersion-Fabric-1.16.5.jar"; Relative = "fabric/1.16.5/nyanslate-$releaseVersion-Fabric-1.16.5.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.16.5'; LoaderRange = '>=0.16.0'; JavaRange = '>=8'; MainClass = 'com.dragonmeow.nyanslate.legacy.LegacyTranslatorMod' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.17.1'; Source = "fabric1171\build\libs\nyanslate-$releaseVersion-Fabric-1.17.1.jar"; Relative = "fabric/1.17.1/nyanslate-$releaseVersion-Fabric-1.17.1.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.17.1'; LoaderRange = '>=0.16.0'; JavaRange = '>=16'; MainClass = 'com.dragonmeow.nyanslate.fabric.NyanslateFabric' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.18.2'; Source = "fabric1182\build\libs\nyanslate-$releaseVersion-Fabric-1.18.2.jar"; Relative = "fabric/1.18.2/nyanslate-$releaseVersion-Fabric-1.18.2.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.18.2'; LoaderRange = '>=0.16.0'; JavaRange = '>=17'; MainClass = 'com.dragonmeow.nyanslate.fabric.NyanslateFabric' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.19.4'; Source = "fabric1194\build\libs\nyanslate-$releaseVersion-Fabric-1.19.4.jar"; Relative = "fabric/1.19.4/nyanslate-$releaseVersion-Fabric-1.19.4.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.19.4'; LoaderRange = '>=0.16.0'; JavaRange = '>=17'; MainClass = 'com.dragonmeow.nyanslate.fabric.NyanslateFabric' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.20.1'; Source = "fabric120\build\libs\nyanslate-$releaseVersion-Fabric-1.20.1.jar"; Relative = "fabric/1.20.1/nyanslate-$releaseVersion-Fabric-1.20.1.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.20.1'; LoaderRange = '>=0.16.0'; JavaRange = '>=17'; MainClass = 'com.dragonmeow.nyanslate.fabric.NyanslateFabric' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.21.1'; Source = "build\libs\nyanslate-$releaseVersion-Fabric-1.21.1.jar"; Relative = "fabric/1.21.1/nyanslate-$releaseVersion-Fabric-1.21.1.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.21.1'; LoaderRange = '>=0.16.0'; JavaRange = '>=21'; MainClass = 'com.dragonmeow.nyanslate.fabric.NyanslateFabric' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '1.21.11'; Source = "fabric12111\build\libs\nyanslate-$releaseVersion-Fabric-1.21.11.jar"; Relative = "fabric/1.21.11/nyanslate-$releaseVersion-Fabric-1.21.11.jar"; MetadataKind = 'fabric'; MinecraftRange = '1.21.11'; LoaderRange = '>=0.16.0'; JavaRange = '>=21'; MainClass = 'com.dragonmeow.nyanslate.fabric.NyanslateFabric' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '26.1.2'; Source = "fabric2612\build\libs\nyanslate-$releaseVersion-Fabric-26.1.2.jar"; Relative = "fabric/26.1.2/nyanslate-$releaseVersion-Fabric-26.1.2.jar"; MetadataKind = 'fabric'; MinecraftRange = '26.1.2'; LoaderRange = '>=0.19.0'; JavaRange = '>=25'; MainClass = 'com.dragonmeow.nyanslate.fabric26.NyanslateFabric26' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '26.2'; Source = "fabric26\build\libs\nyanslate-$releaseVersion-Fabric-26.2.jar"; Relative = "fabric/26.2/nyanslate-$releaseVersion-Fabric-26.2.jar"; MetadataKind = 'fabric'; MinecraftRange = '26.2'; LoaderRange = '>=0.19.0'; JavaRange = '>=25'; MainClass = 'com.dragonmeow.nyanslate.fabric26.NyanslateFabric26' },
    [pscustomobject]@{ Loader = 'fabric'; Label = 'Fabric'; Minecraft = '26.3'; Source = "fabric263\build\libs\nyanslate-$releaseVersion-Fabric-26.3.jar"; Relative = "fabric/26.3/nyanslate-$releaseVersion-Fabric-26.3.jar"; MetadataKind = 'fabric'; MinecraftRange = '26.3'; LoaderRange = '>=0.19.0'; JavaRange = '>=25'; MainClass = 'com.dragonmeow.nyanslate.fabric26.NyanslateFabric26' },

    [pscustomobject]@{ Loader = 'neoforge'; Label = 'NeoForge'; Minecraft = '1.20.1'; Source = "neoforge120\build\libs\nyanslate-$releaseVersion-NeoForge-1.20.1.jar"; Relative = "neoforge/1.20.1/nyanslate-$releaseVersion-NeoForge-1.20.1.jar"; MetadataKind = 'toml'; MetadataEntry = 'META-INF/mods.toml'; TomlVersion = "$releaseVersion"; MinecraftRange = '[1.20.1,1.20.2)'; LoaderRange = '[47,)'; LoaderDependency = 'forge'; LoaderDependencyRange = '[47,)'; MainClass = 'com.dragonmeow.nyanslate.neoforge.NyanslateNeoForge' },
    [pscustomobject]@{ Loader = 'neoforge'; Label = 'NeoForge'; Minecraft = '1.21.1'; Source = "neoforge\build\libs\nyanslate-$releaseVersion-NeoForge-1.21.1.jar"; Relative = "neoforge/1.21.1/nyanslate-$releaseVersion-NeoForge-1.21.1.jar"; MetadataKind = 'toml'; MetadataEntry = 'META-INF/neoforge.mods.toml'; TomlVersion = "$releaseVersion"; MinecraftRange = '[1.21.1,1.21.2)'; LoaderRange = '[4,)'; LoaderDependency = 'neoforge'; LoaderDependencyRange = '[21.1.0,)'; MainClass = 'com.dragonmeow.nyanslate.neoforge.NyanslateNeoForge' },
    [pscustomobject]@{ Loader = 'neoforge'; Label = 'NeoForge'; Minecraft = '26.2'; Source = "neoforge26\build\libs\nyanslate-$releaseVersion-NeoForge-26.2.jar"; Relative = "neoforge/26.2/nyanslate-$releaseVersion-NeoForge-26.2.jar"; MetadataKind = 'toml'; MetadataEntry = 'META-INF/neoforge.mods.toml'; TomlVersion = "$releaseVersion"; MinecraftRange = '[26.2,26.3)'; LoaderRange = '[4,)'; LoaderDependency = 'neoforge'; LoaderDependencyRange = '[26.2,)'; MainClass = 'com.dragonmeow.nyanslate.neoforge26.NyanslateNeoForge26' },
    [pscustomobject]@{ Loader = 'neoforge'; Label = 'NeoForge'; Minecraft = '26.3'; Source = "neoforge263\build\libs\nyanslate-$releaseVersion-NeoForge-26.3.jar"; Relative = "neoforge/26.3/nyanslate-$releaseVersion-NeoForge-26.3.jar"; MetadataKind = 'toml'; MetadataEntry = 'META-INF/neoforge.mods.toml'; TomlVersion = "$releaseVersion"; MinecraftRange = '[26.3,26.4)'; LoaderRange = '[4,)'; LoaderDependency = 'neoforge'; LoaderDependencyRange = '[26.3,)'; MainClass = 'com.dragonmeow.nyanslate.neoforge26.NyanslateNeoForge26' }
)

$zipSpecs = @(
    [pscustomobject]@{ Relative = "Nyanslate-$releaseVersion-Fabric.zip"; Loaders = @('fabric'); ExpectedCount = 12 },
    [pscustomobject]@{ Relative = "Nyanslate-$releaseVersion-NeoForge.zip"; Loaders = @('neoforge'); ExpectedCount = 4 },
    [pscustomobject]@{ Relative = "Nyanslate-$releaseVersion-Forge.zip"; Loaders = @('forge'); ExpectedCount = 2 },
    [pscustomobject]@{ Relative = "Nyanslate-$releaseVersion-all-versions.zip"; Loaders = @('fabric', 'neoforge', 'forge'); ExpectedCount = 18 }
)

Require ($artifacts.Count -eq 18) "Artifact map must contain exactly 18 JARs"
Require (@($artifacts | Where-Object Loader -eq 'fabric').Count -eq 12) `
    "Artifact map must contain 12 Fabric JARs"
Require (@($artifacts | Where-Object Loader -eq 'neoforge').Count -eq 4) `
    "Artifact map must contain 4 NeoForge JARs"
Require (@($artifacts | Where-Object Loader -eq 'forge').Count -eq 2) `
    "Artifact map must contain 2 Forge JARs"
Require (@($artifacts | Group-Object Source | Where-Object Count -ne 1).Count -eq 0) `
    "Artifact map contains duplicate source paths"
Require (@($artifacts | Group-Object Relative | Where-Object Count -ne 1).Count -eq 0) `
    "Artifact map contains duplicate destination paths"
Require ($zipSpecs.Count -eq 4) "ZIP map must contain exactly four archives"
Require (@($zipSpecs | Group-Object Relative | Where-Object Count -ne 1).Count -eq 0) `
    "ZIP map contains duplicate destination paths"

$allowed = @($artifacts.Relative) + @($zipSpecs.Relative) + @('SHA256SUMS.txt')
Require ($allowed.Count -eq 23) `
    "Release output map must contain 18 JARs, four ZIPs, and SHA256SUMS.txt"
Require (@($allowed | Group-Object | Where-Object Count -ne 1).Count -eq 0) `
    "Release output map contains duplicate paths"

# Preflight every source, destination, existing release entry, and transaction
# path before creating the staging directory or changing any release output.
Require (Test-PathIsWithin $modsJarRoot $repoRoot) `
    "mods-jar is outside the repository: $modsJarRoot"
Require (Test-Path -LiteralPath $modsJarRoot -PathType Container) `
    "mods-jar directory does not exist: $modsJarRoot"
foreach ($transactionPath in @(
        $releaseRoot, $stageRoot, $backupRoot, $failedInstallRoot)) {
    Require (Test-PathIsWithin $transactionPath $modsJarRoot) `
        "Transaction path is outside mods-jar: $transactionPath"
    Require ([System.IO.Path]::GetDirectoryName($transactionPath).Equals(
            $modsJarRoot, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Transaction path is not an immediate child of mods-jar: $transactionPath"
    Assert-NoReparseAncestors $transactionPath "Transaction path"
}
Require (-not (Test-Path -LiteralPath $stageRoot)) `
    "Unique staging path already exists: $stageRoot"
Require (-not (Test-Path -LiteralPath $backupRoot)) `
    "Unique backup path already exists: $backupRoot"
Require (-not (Test-Path -LiteralPath $failedInstallRoot)) `
    "Unique failed-install recovery path already exists: $failedInstallRoot"

$releaseExistedAtPreflight = Test-Path -LiteralPath $releaseRoot
if ($releaseExistedAtPreflight) {
    Assert-ExactPackageTree $releaseRoot $allowed 'Existing release'
}

foreach ($artifact in $artifacts) {
    $expectedName =
        "nyanslate-$releaseVersion-$($artifact.Label)-$($artifact.Minecraft).jar"
    $expectedRelative =
        "$($artifact.Loader)/$($artifact.Minecraft)/$expectedName"
    Require ($artifact.Relative -ceq $expectedRelative) `
        "Artifact destination must be loader/version/JAR: $($artifact.Relative)"
    $source = Get-RepoPath $artifact.Source
    Assert-NoReparseAncestors $source "Source artifact $($artifact.Source)"
    Assert-OutputTarget $releaseRoot $artifact.Relative
    Assert-OutputTarget $stageRoot $artifact.Relative
}
foreach ($spec in $zipSpecs) {
    $selected = @($artifacts | Where-Object {
        $spec.Loaders -contains $_.Loader
    })
    Require ($selected.Count -eq $spec.ExpectedCount) `
        "$($spec.Relative) selects $($selected.Count) JARs; expected $($spec.ExpectedCount)"
    Assert-OutputTarget $releaseRoot $spec.Relative
    Assert-OutputTarget $stageRoot $spec.Relative
}
Assert-OutputTarget $releaseRoot 'SHA256SUMS.txt'
Assert-OutputTarget $stageRoot 'SHA256SUMS.txt'

# Validate every source and bind its validated metadata to a stable preflight
# hash. A concurrent build must not be able to replace a JAR between these two
# hashes or between preflight and the staging copy.
$sourcePathsByRelative = @{}
$sourceHashesByRelative = @{}
foreach ($artifact in $artifacts) {
    $source = Get-RepoPath $artifact.Source
    Assert-NoReparseAncestors $source "Source artifact $($artifact.Source)"
    Require (Test-Path -LiteralPath $source -PathType Leaf) `
        "Missing build artifact: $($artifact.Source)"
    $hashBeforeValidation = Get-FileSha256Lower $source
    Assert-SourceJar $artifact $source -RequireBuildLibs
    $hashAfterValidation = Get-FileSha256Lower $source
    Require ($hashBeforeValidation -ceq $hashAfterValidation) `
        "Source changed while metadata was validated: $($artifact.Source)"
    $sourcePathsByRelative[$artifact.Relative] = $source
    $sourceHashesByRelative[$artifact.Relative] = $hashAfterValidation
}
Require ($sourcePathsByRelative.Count -eq 18 -and
        $sourceHashesByRelative.Count -eq 18) `
    "Source preflight did not record all 18 artifacts"

$stageCreated = $false
$commitStarted = $false
$checksumLines = @()

try {
    New-Item -ItemType Directory -Path $stageRoot | Out-Null
    $stageCreated = $true
    Assert-NoReparseTree $stageRoot 'New staging directory'

    $packagedByRelative = @{}
    foreach ($artifact in $artifacts) {
        $source = [string]$sourcePathsByRelative[$artifact.Relative]
        $recordedHash = [string]$sourceHashesByRelative[$artifact.Relative]
        Assert-NoReparseAncestors $source "Source artifact $($artifact.Source)"
        Require ((Get-FileSha256Lower $source) -ceq $recordedHash) `
            "Source changed after preflight: $($artifact.Source)"

        $destination = Get-PackagePath $stageRoot $artifact.Relative
        $destinationDirectory = Split-Path -Parent $destination
        if (-not (Test-Path -LiteralPath $destinationDirectory)) {
            New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $destination

        $sourceHashAfterCopy = Get-FileSha256Lower $source
        $destinationHash = Get-FileSha256Lower $destination
        Require ($sourceHashAfterCopy -ceq $recordedHash -and
                $destinationHash -ceq $recordedHash) `
            "Source changed or staged JAR hash mismatched: $($artifact.Relative)"
        Assert-SourceJar $artifact $destination
        $packagedByRelative[$artifact.Relative] = $destination
    }

    foreach ($spec in $zipSpecs) {
        $selected = @($artifacts | Where-Object {
            $spec.Loaders -contains $_.Loader
        } | Sort-Object Relative)
        Require ($selected.Count -eq $spec.ExpectedCount) `
            "$($spec.Relative) selected $($selected.Count) JARs; expected $($spec.ExpectedCount)"
        $zipPath = Get-PackagePath $stageRoot $spec.Relative
        Require (-not (Test-Path -LiteralPath $zipPath)) `
            "Fresh staging ZIP already exists: $zipPath"
        $archive = [System.IO.Compression.ZipFile]::Open(
            $zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($artifact in $selected) {
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive,
                    [string]$packagedByRelative[$artifact.Relative],
                    [string]$artifact.Relative,
                    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
        } finally {
            $archive.Dispose()
        }
    }

    $checksumTargets = @(
        @($artifacts | ForEach-Object {
            [pscustomobject]@{
                Relative = $_.Relative
                Path = [string]$packagedByRelative[$_.Relative]
            }
        })
        @($zipSpecs | ForEach-Object {
            [pscustomobject]@{
                Relative = $_.Relative
                Path = Get-PackagePath $stageRoot $_.Relative
            }
        })
    )
    $checksumTargets = @($checksumTargets | Sort-Object Relative)
    Require ($checksumTargets.Count -eq 22) `
        "Checksum target set must contain 18 JARs and four ZIPs"

    $checksumLines = @($checksumTargets | ForEach-Object {
        (Get-FileSha256Lower $_.Path) + ' *' + $_.Relative
    })
    Require ($checksumLines.Count -eq 22) `
        "SHA256SUMS must contain exactly 22 lines"
    $checksumPath = Get-PackagePath $stageRoot 'SHA256SUMS.txt'
    [System.IO.File]::WriteAllLines(
        $checksumPath, [string[]]$checksumLines, $utf8NoBom)

    # No output is installed until the staging tree is the exact, fully
    # verified 23-file package.
    Assert-ExactPackageTree $stageRoot $allowed 'Staged release'
    foreach ($artifact in $artifacts) {
        $stagedJar = [string]$packagedByRelative[$artifact.Relative]
        Assert-SourceJar $artifact $stagedJar
        Require ((Get-FileSha256Lower $stagedJar) -ceq
                [string]$sourceHashesByRelative[$artifact.Relative]) `
            "Staged JAR changed after validation: $($artifact.Relative)"
    }

    # Reopen every ZIP and compare its exact entry set and each uncompressed
    # entry hash against the staged JAR.
    foreach ($spec in $zipSpecs) {
        $selected = @($artifacts | Where-Object {
            $spec.Loaders -contains $_.Loader
        } | Sort-Object Relative)
        $expectedNames = @($selected | ForEach-Object { $_.Relative })
        $zipPath = Get-PackagePath $stageRoot $spec.Relative
        $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
        try {
            $actualNames = @($archive.Entries | ForEach-Object { $_.FullName })
            Require ($actualNames.Count -eq $spec.ExpectedCount) `
                "$($spec.Relative) contains $($actualNames.Count) entries; expected $($spec.ExpectedCount)"
            Require (@($actualNames | Where-Object {
                        $_.Contains('\') -or $_.EndsWith('/') -or
                        $_ -match '(^|/)SHA256SUMS\.txt$'
                    }).Count -eq 0) `
                "$($spec.Relative) contains an unsafe, directory, or checksum entry"
            Require ([string]::Join("`n", $actualNames) -ceq
                    [string]::Join("`n", $expectedNames)) `
                "$($spec.Relative) entry list or ordering is wrong"

            foreach ($artifact in $selected) {
                $entry = $archive.GetEntry($artifact.Relative)
                Require ($null -ne $entry) `
                    "$($spec.Relative) is missing $($artifact.Relative)"
                $stream = $entry.Open()
                try {
                    $entryHash = Get-StreamSha256Lower $stream
                } finally {
                    $stream.Dispose()
                }
                $jarHash = Get-FileSha256Lower (
                    [string]$packagedByRelative[$artifact.Relative])
                Require ($entryHash -ceq $jarHash) `
                    "$($spec.Relative) entry hash mismatch: $($artifact.Relative)"
            }
        } finally {
            $archive.Dispose()
        }
    }

    $writtenLines = [System.IO.File]::ReadAllLines($checksumPath, $utf8NoBom)
    Require ($writtenLines.Count -eq 22 -and
            [string]::Join("`n", $writtenLines) -ceq
            [string]::Join("`n", $checksumLines)) `
        "Written SHA256SUMS content or ordering is wrong"
    $targetsByRelative = @{}
    foreach ($target in $checksumTargets) {
        $targetsByRelative[$target.Relative] = $target.Path
    }
    $seenChecksums = @{}
    foreach ($line in $writtenLines) {
        $match = [regex]::Match(
            $line, '^(?<hash>[0-9a-f]{64}) \*(?<path>[^\\]+)$')
        Require ($match.Success) "Malformed SHA256SUMS line: $line"
        $relative = $match.Groups['path'].Value
        Require ($relative -cnotmatch '(^|/)SHA256SUMS\.txt$') `
            "SHA256SUMS must not checksum itself"
        Require ($targetsByRelative.ContainsKey($relative)) `
            "SHA256SUMS contains an unknown path: $relative"
        Require (-not $seenChecksums.ContainsKey($relative)) `
            "SHA256SUMS contains a duplicate path: $relative"
        $actualHash = Get-FileSha256Lower ([string]$targetsByRelative[$relative])
        Require ($match.Groups['hash'].Value -ceq $actualHash) `
            "SHA256SUMS hash mismatch: $relative"
        $seenChecksums[$relative] = $true
    }
    Require ($seenChecksums.Count -eq 22) `
        "SHA256SUMS does not cover all 22 release files"
    Assert-ExactPackageTree $stageRoot $allowed 'Validated staged release'
    Assert-PackageMatchesChecksumLines `
        $stageRoot $checksumLines $utf8NoBom 'Validated staged release'

    $assertExpectedTree = {
        param([string]$Path, [string]$Description)
        Assert-ExactPackageTree $Path $allowed $Description
    }
    $assertMovableTree = {
        param([string]$Path, [string]$Description)
        Assert-NoReparseTree $Path $Description
        Require (Test-Path -LiteralPath $Path -PathType Container) `
            "$Description is not a directory: $Path"
    }
    $assertInstalledTree = {
        param([string]$Path, [string]$Description)
        Assert-ExactPackageTree $Path $allowed $Description
        foreach ($artifact in $artifacts) {
            $installedJar = Get-PackagePath $Path $artifact.Relative
            Require ((Get-FileSha256Lower $installedJar) -ceq
                    [string]$sourceHashesByRelative[$artifact.Relative]) `
                "Installed JAR hash changed during commit: $($artifact.Relative)"
        }
        Assert-PackageMatchesChecksumLines `
            $Path $checksumLines $utf8NoBom $Description
    }
    $removeBackup = {
        param([string]$Path)
        Remove-ValidatedPackageTree $Path 'Old-release backup'
    }

    # From this boundary onward every failure retains an explicit recovery tree;
    # the outer cleanup is intentionally limited to pre-commit staging failures.
    $commitStarted = $true
    $null = Invoke-ValidatedReleaseSwap `
        -ReleaseRoot $releaseRoot `
        -StageRoot $stageRoot `
        -BackupRoot $backupRoot `
        -FailedInstallRoot $failedInstallRoot `
        -HadExistingRelease $releaseExistedAtPreflight `
        -AssertExpectedTree $assertExpectedTree `
        -AssertMovableTree $assertMovableTree `
        -AssertInstalledTree $assertInstalledTree `
        -RemoveValidatedBackup $removeBackup
} catch {
    $failure = $_
    if ($stageCreated -and -not $commitStarted -and
            (Test-Path -LiteralPath $stageRoot)) {
        try {
            Remove-ValidatedPackageTree $stageRoot 'Failed staging directory'
        } catch {
            throw ("Packaging failed: {0}; staging cleanup also failed: {1}" -f
                $failure.Exception.Message, $_.Exception.Message)
        }
    }
    throw $failure
}

Write-Output ("PACKAGE_RELEASE_OK release={0}" -f $releaseRoot)
Write-Output 'JARS total=18 fabric=12 neoforge=4 forge=2 source_and_staged_hashes=verified'
Write-Output 'ZIPS total=4 entries=12,4,2,18 entry_hashes=verified'
Write-Output 'SHA256SUMS lines=22 format=lowercase-forward-slash verified'
foreach ($line in $checksumLines) {
    Write-Output ("SHA256 {0}" -f $line)
}
