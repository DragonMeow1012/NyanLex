[CmdletBinding()]
param(
    [ValidateSet('All', 'Source', 'Build', 'FinalJar')]
    [string]$Phase = 'All',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$releaseVersion = '1.0.4'
$repoRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$verificationRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$gradleExecutables = @{
    '8.10' = Join-Path $repoRoot '.gradle-local\gradle-8.10\bin\gradle.bat'
    '8.13' = Join-Path $repoRoot '.gradle-local\gradle-8.13\bin\gradle.bat'
    '9.5.0' = Join-Path $repoRoot '.gradle-local\gradle-9.5.0\bin\gradle.bat'
}
$initScript = Join-Path $verificationRoot 'final-jar-inline.init.gradle'
$launcherSource = Join-Path $verificationRoot 'FinalJarHarnessLauncher.java'
$sourceInitScript = Join-Path $verificationRoot 'source-output-inline.init.gradle'
$sourceLauncher = Join-Path $verificationRoot 'SourceOutputHarnessLauncher.java'
$fakeCodex = Join-Path $verificationRoot 'fake-codex.cmd'
$fakeCodexPython = Join-Path $verificationRoot 'fake_codex.py'
$protocolAssertion = Join-Path $verificationRoot 'assert-inline-protocol.ps1'
$requiredDeliveryKeys = @(
    'config.mctranslator.chat_delivery',
    'config.mctranslator.chat_delivery.ordered',
    'config.mctranslator.chat_delivery.ready_first'
)

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
    if ($full.Equals($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full
    }
    $trimChars = [char[]]@(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $prefix = $repoRoot.TrimEnd($trimChars) +
        [System.IO.Path]::DirectorySeparatorChar
    Require ($full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Path escapes repository root: $Relative"
    return $full
}

function Test-SamePath {
    param(
        [Parameter(Mandatory = $true)][string]$First,
        [Parameter(Mandatory = $true)][string]$Second
    )
    return [System.IO.Path]::GetFullPath($First).Equals(
        [System.IO.Path]::GetFullPath($Second),
        [System.StringComparison]::OrdinalIgnoreCase)
}

function New-FabricRow {
    param(
        [string]$Key,
        [string]$Project,
        [string]$Minecraft,
        [string]$LoaderRange,
        [string]$JavaRange,
        [int]$SourceRelease,
        [int]$ClassMajor,
        [int]$RuntimeJdk,
        [string]$HarnessKind,
        [string]$MainClass,
        [string]$SettingsClass
    )
    return [pscustomobject]@{
        Key = $Key
        Project = $Project
        Loader = 'fabric'
        Label = 'Fabric'
        Minecraft = $Minecraft
        ArtifactName = "mctranslator-$releaseVersion-Fabric-$Minecraft.jar"
        MetadataKind = 'fabric'
        MetadataEntry = 'fabric.mod.json'
        MinecraftRange = $Minecraft
        LoaderRange = $LoaderRange
        JavaRange = $JavaRange
        TomlVersion = $null
        LoaderDependency = $null
        LoaderDependencyRange = $null
        SourceRelease = $SourceRelease
        ClassMajor = $ClassMajor
        RuntimeJdk = $RuntimeJdk
        BuildJdk = 21
        HarnessKind = $HarnessKind
        MainClass = $MainClass
        SettingsClass = $SettingsClass
        LangCount = 143
        LangExtension = 'json'
    }
}

function New-TomlRow {
    param(
        [string]$Key,
        [string]$Project,
        [string]$Loader,
        [string]$Label,
        [string]$Minecraft,
        [string]$MetadataEntry,
        [string]$MinecraftRange,
        [string]$LoaderRange,
        [string]$TomlVersion,
        [string]$LoaderDependency,
        [string]$LoaderDependencyRange,
        [int]$SourceRelease,
        [int]$ClassMajor,
        [int]$RuntimeJdk,
        [int]$BuildJdk,
        [string]$HarnessKind,
        [string]$MainClass,
        [string]$SettingsClass,
        [int]$LangCount,
        [string]$LangExtension
    )
    return [pscustomobject]@{
        Key = $Key
        Project = $Project
        Loader = $Loader
        Label = $Label
        Minecraft = $Minecraft
        ArtifactName = "mctranslator-$releaseVersion-$Label-$Minecraft.jar"
        MetadataKind = 'toml'
        MetadataEntry = $MetadataEntry
        MinecraftRange = $MinecraftRange
        LoaderRange = $LoaderRange
        JavaRange = $null
        TomlVersion = $TomlVersion
        LoaderDependency = $LoaderDependency
        LoaderDependencyRange = $LoaderDependencyRange
        SourceRelease = $SourceRelease
        ClassMajor = $ClassMajor
        RuntimeJdk = $RuntimeJdk
        BuildJdk = $BuildJdk
        HarnessKind = $HarnessKind
        MainClass = $MainClass
        SettingsClass = $SettingsClass
        LangCount = $LangCount
        LangExtension = $LangExtension
    }
}

function New-Forge1122Row {
    return [pscustomobject]@{
        Key = 'forge1122'
        Project = 'forge1122'
        Loader = 'forge'
        Label = 'Forge'
        Minecraft = '1.12.2'
        ArtifactName = "mctranslator-$releaseVersion-Forge-1.12.2.jar"
        MetadataKind = 'forge1122'
        MetadataEntry = 'mcmod.info'
        MinecraftRange = '1.12.2'
        LoaderRange = $null
        JavaRange = $null
        TomlVersion = $null
        LoaderDependency = $null
        LoaderDependencyRange = $null
        SourceRelease = 8
        ClassMajor = 52
        RuntimeJdk = 8
        BuildJdk = 8
        HarnessKind = 'forgelegacy'
        MainClass = 'com.dragonmeow.mctranslator.forgelegacy.MinecraftTranslatorForge'
        SettingsClass = 'com.dragonmeow.mctranslator.forgelegacy.ForgeSettingsScreen'
        LangCount = 2
        LangExtension = 'lang'
    }
}

$rows = @(
    New-Forge1122Row
    New-TomlRow -Key 'forge1132' -Project 'forge1132' -Loader 'forge' `
        -Label 'Forge' -Minecraft '1.13.2' -MetadataEntry 'META-INF/mods.toml' `
        -MinecraftRange '[1.13.2]' -LoaderRange '[25,)' `
        -TomlVersion ('${file.jarVersion}') -LoaderDependency 'forge' `
        -LoaderDependencyRange '[25,)' -SourceRelease 8 -ClassMajor 52 `
        -RuntimeJdk 8 -BuildJdk 8 -HarnessKind 'forgelegacy' `
        -MainClass 'com.dragonmeow.mctranslator.forgelegacy.MinecraftTranslatorForge' `
        -SettingsClass 'com.dragonmeow.mctranslator.forgelegacy.ForgeSettingsScreen' `
        -LangCount 2 -LangExtension 'json'

    New-FabricRow -Key 'fabric1144' -Project 'fabric1144' -Minecraft '1.14.4' `
        -LoaderRange '>=0.16.0' -JavaRange '>=8' -SourceRelease 8 `
        -ClassMajor 52 -RuntimeJdk 8 -HarnessKind 'legacy' `
        -MainClass 'com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod' `
        -SettingsClass 'com.dragonmeow.mctranslator.legacy.LegacySettingsScreen'
    New-FabricRow -Key 'fabric1152' -Project 'fabric1152' -Minecraft '1.15.2' `
        -LoaderRange '>=0.16.0' -JavaRange '>=8' -SourceRelease 8 `
        -ClassMajor 52 -RuntimeJdk 8 -HarnessKind 'legacy' `
        -MainClass 'com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod' `
        -SettingsClass 'com.dragonmeow.mctranslator.legacy.LegacySettingsScreen'
    New-FabricRow -Key 'fabric1165' -Project 'fabric1165' -Minecraft '1.16.5' `
        -LoaderRange '>=0.16.0' -JavaRange '>=8' -SourceRelease 8 `
        -ClassMajor 52 -RuntimeJdk 8 -HarnessKind 'legacy' `
        -MainClass 'com.dragonmeow.mctranslator.legacy.LegacyTranslatorMod' `
        -SettingsClass 'com.dragonmeow.mctranslator.legacy.LegacySettingsScreen'
    New-FabricRow -Key 'fabric1171' -Project 'fabric1171' -Minecraft '1.17.1' `
        -LoaderRange '>=0.16.0' -JavaRange '>=16' -SourceRelease 16 `
        -ClassMajor 60 -RuntimeJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric.MctranslatorFabric' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric.TranslationConfigScreen'
    New-FabricRow -Key 'fabric1182' -Project 'fabric1182' -Minecraft '1.18.2' `
        -LoaderRange '>=0.16.0' -JavaRange '>=17' -SourceRelease 17 `
        -ClassMajor 61 -RuntimeJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric.MctranslatorFabric' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric.TranslationConfigScreen'
    New-FabricRow -Key 'fabric1194' -Project 'fabric1194' -Minecraft '1.19.4' `
        -LoaderRange '>=0.16.0' -JavaRange '>=17' -SourceRelease 17 `
        -ClassMajor 61 -RuntimeJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric.MctranslatorFabric' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric.TranslationConfigScreen'
    New-FabricRow -Key 'fabric120' -Project 'fabric120' -Minecraft '1.20.1' `
        -LoaderRange '>=0.16.0' -JavaRange '>=17' -SourceRelease 17 `
        -ClassMajor 61 -RuntimeJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric.MctranslatorFabric' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric.TranslationConfigScreen'
    New-FabricRow -Key 'fabric1211' -Project '.' -Minecraft '1.21.1' `
        -LoaderRange '>=0.16.0' -JavaRange '>=21' -SourceRelease 21 `
        -ClassMajor 65 -RuntimeJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric.MctranslatorFabric' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric.TranslationConfigScreen'
    New-FabricRow -Key 'fabric12111' -Project 'fabric12111' -Minecraft '1.21.11' `
        -LoaderRange '>=0.16.0' -JavaRange '>=21' -SourceRelease 21 `
        -ClassMajor 65 -RuntimeJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric.MctranslatorFabric' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric.TranslationConfigScreen'
    New-FabricRow -Key 'fabric2612' -Project 'fabric2612' -Minecraft '26.1.2' `
        -LoaderRange '>=0.19.0' -JavaRange '>=25' -SourceRelease 25 `
        -ClassMajor 69 -RuntimeJdk 25 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric26.MctranslatorFabric26' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric26.Fabric26ConfigScreen'
    New-FabricRow -Key 'fabric26' -Project 'fabric26' -Minecraft '26.2' `
        -LoaderRange '>=0.19.0' -JavaRange '>=25' -SourceRelease 25 `
        -ClassMajor 69 -RuntimeJdk 25 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.fabric26.MctranslatorFabric26' `
        -SettingsClass 'com.dragonmeow.mctranslator.fabric26.Fabric26ConfigScreen'

    New-TomlRow -Key 'neoforge120' -Project 'neoforge120' -Loader 'neoforge' `
        -Label 'NeoForge' -Minecraft '1.20.1' -MetadataEntry 'META-INF/mods.toml' `
        -MinecraftRange '[1.20.1,1.20.2)' -LoaderRange '[47,)' `
        -TomlVersion '1.0.4' -LoaderDependency 'forge' `
        -LoaderDependencyRange '[47,)' -SourceRelease 17 -ClassMajor 61 `
        -RuntimeJdk 21 -BuildJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.neoforge.MctranslatorNeoForge' `
        -SettingsClass 'com.dragonmeow.mctranslator.neoforge.TranslationConfigScreen' `
        -LangCount 143 -LangExtension 'json'
    New-TomlRow -Key 'neoforge1211' -Project 'neoforge' -Loader 'neoforge' `
        -Label 'NeoForge' -Minecraft '1.21.1' `
        -MetadataEntry 'META-INF/neoforge.mods.toml' `
        -MinecraftRange '[1.21.1,1.21.2)' -LoaderRange '[4,)' `
        -TomlVersion '1.0.4' -LoaderDependency 'neoforge' `
        -LoaderDependencyRange '[21.1.0,)' -SourceRelease 21 -ClassMajor 65 `
        -RuntimeJdk 21 -BuildJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.neoforge.MctranslatorNeoForge' `
        -SettingsClass 'com.dragonmeow.mctranslator.neoforge.TranslationConfigScreen' `
        -LangCount 143 -LangExtension 'json'
    New-TomlRow -Key 'neoforge26' -Project 'neoforge26' -Loader 'neoforge' `
        -Label 'NeoForge' -Minecraft '26.2' `
        -MetadataEntry 'META-INF/neoforge.mods.toml' `
        -MinecraftRange '[26.2,26.3)' -LoaderRange '[4,)' `
        -TomlVersion '1.0.4' -LoaderDependency 'neoforge' `
        -LoaderDependencyRange '[26.2,)' -SourceRelease 25 -ClassMajor 69 `
        -RuntimeJdk 25 -BuildJdk 21 -HarnessKind 'modern' `
        -MainClass 'com.dragonmeow.mctranslator.neoforge26.MctranslatorNeoForge26' `
        -SettingsClass 'com.dragonmeow.mctranslator.neoforge26.Neo26ConfigScreen' `
        -LangCount 143 -LangExtension 'json'
)

function Get-HarnessSpec {
    param(
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][ValidateSet('core', 'codex')]
        [string]$Purpose
    )

    if ($Kind -ceq 'modern') {
        if ($Purpose -ceq 'core') {
            return [pscustomobject]@{
                Source = 'modern\com\dragonmeow\mctranslator\translate\InlineCoreRegression.java'
                Main = 'com.dragonmeow.mctranslator.translate.InlineCoreRegression'
                Marker = ('INLINE_CORE_OK hostile=24 codex=21 ' +
                    'coverage=recovery-assembly,result-progress,batch-budget,codex-state')
                TransformPackage = $null
            }
        }
        return [pscustomobject]@{
            Source = 'modern\com\dragonmeow\mctranslator\translate\InlineCodexSimulation.java'
            Main = 'com.dragonmeow.mctranslator.translate.InlineCodexSimulation'
            Marker = 'INLINE_CODEX_OK modern'
            TransformPackage = $null
        }
    }
    if ($Kind -ceq 'legacy') {
        if ($Purpose -ceq 'core') {
            return [pscustomobject]@{
                Source = 'legacy-core\com\dragonmeow\mctranslator\legacy\InlineLegacyCoreSimulation.java'
                Main = 'com.dragonmeow.mctranslator.legacy.InlineLegacyCoreSimulation'
                Marker = 'INLINE_LEGACY_CORE_OK'
                TransformPackage = $null
            }
        }
        return [pscustomobject]@{
            Source = 'legacy\com\dragonmeow\mctranslator\legacy\InlineCodexSimulation.java'
            Main = 'com.dragonmeow.mctranslator.legacy.InlineCodexSimulation'
            Marker = 'INLINE_CODEX_OK legacy'
            TransformPackage = $null
        }
    }
    if ($Kind -ceq 'forgelegacy') {
        if ($Purpose -ceq 'core') {
            # The complete Forge core harness is derived from the one canonical
            # Java-8 source at run time.  Only the package declaration changes,
            # so new hostile races added to the canonical harness cannot be
            # omitted from Forge by a stale copied verification file.
            return @(
                [pscustomobject]@{
                    Source = 'legacy-core\com\dragonmeow\mctranslator\legacy\InlineLegacyCoreSimulation.java'
                    Main = 'com.dragonmeow.mctranslator.forgelegacy.InlineLegacyCoreSimulation'
                    Marker = 'INLINE_LEGACY_CORE_OK'
                    TransformPackage = 'com.dragonmeow.mctranslator.forgelegacy'
                },
                [pscustomobject]@{
                    Source = 'forgelegacy\com\dragonmeow\mctranslator\forgelegacy\InlineForgeGlueRegression.java'
                    Main = 'com.dragonmeow.mctranslator.forgelegacy.InlineForgeGlueRegression'
                    Marker = 'INLINE_FORGE_GLUE_OK scenarios=725760'
                    TransformPackage = $null
                }
            )
        }
        return [pscustomobject]@{
            Source = 'forgelegacy\com\dragonmeow\mctranslator\forgelegacy\InlineCodexSimulation.java'
            Main = 'com.dragonmeow.mctranslator.forgelegacy.InlineCodexSimulation'
            Marker = 'INLINE_CODEX_OK legacy'
            TransformPackage = $null
        }
    }
    throw "Unknown harness kind: $Kind"
}

function Get-CoreClasses {
    param([Parameter(Mandatory = $true)][string]$Kind)

    if ($Kind -ceq 'modern') {
        return @(
            'com.dragonmeow.mctranslator.config.TranslatorConfig',
            'com.dragonmeow.mctranslator.service.ChatDeliveryQueue',
            'com.dragonmeow.mctranslator.service.ChatDeliverySession',
            'com.dragonmeow.mctranslator.service.ChatRequestProfile',
            'com.dragonmeow.mctranslator.service.RecoveryAssembly',
            'com.dragonmeow.mctranslator.translate.TemplateText',
            'com.dragonmeow.mctranslator.translate.CodexAppServerClient',
            'com.dragonmeow.mctranslator.translate.SessionTokenUsage',
            'com.dragonmeow.mctranslator.cache.TranslationCache',
            'com.dragonmeow.mctranslator.cache.FileStore'
        )
    }
    if ($Kind -ceq 'legacy') {
        return @(
            'com.dragonmeow.mctranslator.legacy.LegacyConfig',
            'com.dragonmeow.mctranslator.legacy.LegacyChatDeliveryQueue',
            'com.dragonmeow.mctranslator.legacy.LegacyChatRequestProfile',
            'com.dragonmeow.mctranslator.legacy.LegacyTemplateText',
            'com.dragonmeow.mctranslator.legacy.LegacyTranslator',
            'com.dragonmeow.mctranslator.legacy.LegacyCodexClient',
            'com.dragonmeow.mctranslator.legacy.LegacySessionTokenUsage'
        )
    }
    if ($Kind -ceq 'forgelegacy') {
        return @(
            'com.dragonmeow.mctranslator.forgelegacy.LegacyConfig',
            'com.dragonmeow.mctranslator.forgelegacy.LegacyChatDeliveryQueue',
            'com.dragonmeow.mctranslator.forgelegacy.LegacyChatRequestProfile',
            'com.dragonmeow.mctranslator.forgelegacy.LegacyTemplateText',
            'com.dragonmeow.mctranslator.forgelegacy.LegacyTranslator',
            'com.dragonmeow.mctranslator.forgelegacy.LegacyCodexClient',
            'com.dragonmeow.mctranslator.forgelegacy.LegacySessionTokenUsage'
        )
    }
    throw "Unknown class matrix kind: $Kind"
}

foreach ($row in $rows) {
    $projectRoot = Get-RepoPath $row.Project
    $artifactPath = Join-Path $projectRoot (Join-Path 'build\libs' $row.ArtifactName)
    $coreHarnesses = @(Get-HarnessSpec $row.HarnessKind 'core')
    $codexHarness = Get-HarnessSpec $row.HarnessKind 'codex'
    $coreClasses = @(Get-CoreClasses $row.HarnessKind)
    $requiredClasses = @($row.MainClass, $row.SettingsClass) + $coreClasses
    $gradleVersion = if ($row.Loader -ceq 'forge') {
        'wrapper'
    } elseif ($row.Key -in @('fabric12111', 'fabric2612', 'fabric26', 'neoforge26')) {
        '9.5.0'
    } elseif ($row.Key -in @('neoforge120', 'neoforge1211')) {
        '8.13'
    } else {
        '8.10'
    }
    $finalDependencyNamespace = if ($row.Loader -ceq 'forge') {
        'forge-srg'
    } elseif ($row.Key -in @('fabric2612', 'fabric26')) {
        'fabric-official'
    } elseif ($row.Loader -ceq 'fabric') {
        'fabric-intermediary'
    } else {
        'project-runtime'
    }
    $row | Add-Member -NotePropertyName ProjectRoot -NotePropertyValue $projectRoot
    $row | Add-Member -NotePropertyName ArtifactPath -NotePropertyValue $artifactPath
    $row | Add-Member -NotePropertyName CoreHarnesses -NotePropertyValue $coreHarnesses
    $row | Add-Member -NotePropertyName CodexHarness -NotePropertyValue $codexHarness
    $row | Add-Member -NotePropertyName CoreClasses -NotePropertyValue $coreClasses
    $row | Add-Member -NotePropertyName RequiredClasses `
        -NotePropertyValue @($requiredClasses | Select-Object -Unique)
    $row | Add-Member -NotePropertyName GradleVersion -NotePropertyValue $gradleVersion
    $row | Add-Member -NotePropertyName FinalDependencyNamespace `
        -NotePropertyValue $finalDependencyNamespace
}

function Get-JdkCandidates {
    param([Parameter(Mandatory = $true)][int]$Major)

    if ($Major -eq 8) {
        return @(Join-Path $repoRoot '.jdks\temurin8\jdk8u492-b09')
    }

    $candidates = @()
    $override = [Environment]::GetEnvironmentVariable(
        "MCTRANSLATOR_JDK$Major", 'Process')
    if (-not [string]::IsNullOrWhiteSpace($override)) {
        $candidates += $override
    }
    $candidates += "C:\Program Files\Java\jdk-$Major"
    foreach ($parent in @(
            'C:\Program Files\Java',
            'C:\Program Files\Eclipse Adoptium',
            (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'))) {
        if ([string]::IsNullOrWhiteSpace($parent) -or
                -not (Test-Path -LiteralPath $parent -PathType Container)) {
            continue
        }
        $candidates += @(Get-ChildItem -LiteralPath $parent -Directory -Force |
            Where-Object { $_.Name -like "jdk-$Major*" } |
            Sort-Object Name -Descending |
            ForEach-Object { $_.FullName })
    }
    return @($candidates | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    } | Select-Object -Unique)
}

function Test-JdkMajor {
    param(
        [Parameter(Mandatory = $true)][string]$JdkHome,
        [Parameter(Mandatory = $true)][int]$Major
    )

    $java = Join-Path $JdkHome 'bin\java.exe'
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javac -PathType Leaf)) {
        return $false
    }
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $versionOutput = @(& $javac -version 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
    }
    if ($exitCode -ne 0) { return $false }
    $version = [string]::Join(' ', @($versionOutput | ForEach-Object {
        [string]$_
    }))
    if ($Major -eq 8) {
        return $version -match '(?i)\bjavac\s+1\.8(?:\.|\s|$)'
    }
    return $version -match ("(?i)\bjavac\s+{0}(?:\.|\s|$)" -f $Major)
}

function Resolve-Jdk {
    param(
        [Parameter(Mandatory = $true)][int]$Major,
        [switch]$AllowMissing
    )

    foreach ($candidate in @(Get-JdkCandidates $Major)) {
        try {
            $full = [System.IO.Path]::GetFullPath([string]$candidate)
        } catch {
            continue
        }
        if (Test-JdkMajor $full $Major) {
            if ($Major -eq 8) {
                $requiredTemurin = [System.IO.Path]::GetFullPath(
                    (Join-Path $repoRoot '.jdks\temurin8\jdk8u492-b09'))
                Require (Test-SamePath $full $requiredTemurin) `
                    "Java 8 verification must use the repository Temurin 8: $full"
            }
            return $full
        }
    }

    $message = "JDK $Major was not found. Set MCTRANSLATOR_JDK$Major to its home."
    if ($AllowMissing) {
        Write-Warning $message
        return $null
    }
    throw $message
}

function Get-ClassSourcePath {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$ClassName
    )
    $relative = $ClassName.Replace('.', [System.IO.Path]::DirectorySeparatorChar) +
        '.java'
    return Join-Path $Row.ProjectRoot (Join-Path 'src\main\java' $relative)
}

foreach ($row in $rows) {
    $settingsSource = Get-ClassSourcePath $row $row.SettingsClass
    $deliveryKeys = @($requiredDeliveryKeys)
    $uiDeliveryKeys = @()
    if (Test-Path -LiteralPath $settingsSource -PathType Leaf) {
        $settingsText = [System.IO.File]::ReadAllText(
            $settingsSource, [System.Text.Encoding]::UTF8)
        $keyMatches = [regex]::Matches(
            $settingsText,
            '"(?<key>config\.mctranslator\.chat_delivery(?:\.[a-z_]+)?)"')
        $uiDeliveryKeys = @($keyMatches | ForEach-Object {
            $_.Groups['key'].Value
        } | Select-Object -Unique)
        $deliveryKeys += $uiDeliveryKeys
    }
    if ($row.HarnessKind -ceq 'legacy') {
        $deliveryKeys += 'config.mctranslator.chat_delivery.short'
    }
    $row | Add-Member -NotePropertyName UiDeliveryKeys `
        -NotePropertyValue $uiDeliveryKeys
    $row | Add-Member -NotePropertyName DeliveryKeys `
        -NotePropertyValue @($deliveryKeys | Select-Object -Unique)
}

function Get-LanguageValues {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Extension
    )

    $values = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::Ordinal)
    if ($Extension -ceq 'json') {
        $json = $Text | ConvertFrom-Json
        foreach ($property in @($json.PSObject.Properties)) {
            $values.Add([string]$property.Name, [string]$property.Value)
        }
        return $values
    }

    foreach ($line in @($Text -split '\r?\n')) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        $key = $line.Substring(0, $separator).Trim()
        $values.Add($key, $line.Substring($separator + 1))
    }
    return $values
}

function Assert-DeliveryLanguageText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Extension,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string[]]$Keys
    )

    $values = Get-LanguageValues $Text $Extension
    foreach ($key in $Keys) {
        Require ($values.ContainsKey($key)) "$Description is missing language key $key"
        Require (-not [string]::IsNullOrWhiteSpace([string]$values[$key])) `
            "$Description has an empty language value for $key"
    }
}

function Get-SourceReadiness {
    param([Parameter(Mandatory = $true)]$Row)

    $issues = @()
    if (-not (Test-Path -LiteralPath $Row.ProjectRoot -PathType Container)) {
        $issues += "missing project $($Row.ProjectRoot)"
        return [pscustomobject]@{ Ready = $false; Issues = $issues; LangCount = 0 }
    }
    foreach ($className in $Row.RequiredClasses) {
        $source = Get-ClassSourcePath $Row $className
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            $issues += "missing source class $className"
        }
    }
    if ($Row.HarnessKind -in @('legacy', 'forgelegacy')) {
        $mainSource = Get-ClassSourcePath $Row $Row.MainClass
        if (Test-Path -LiteralPath $mainSource -PathType Leaf) {
            $mainText = [System.IO.File]::ReadAllText(
                $mainSource, [System.Text.Encoding]::UTF8)
            foreach ($anchor in @(
                    'ITEM_WARM_SCAN_INTERVAL_NANOS',
                    'nextItemWarmScanAtNanos',
                    'warmedItemNames',
                    'TRANSLATOR.prefetch(')) {
                if (-not $mainText.Contains($anchor)) {
                    $issues += "legacy item-warm path is missing $anchor"
                }
            }
            if (-not $mainText.Contains('350L')) {
                $issues += 'legacy item-warm scan is not gated at 350 ms'
            }
            if ([regex]::IsMatch(
                    $mainText, 'ignored\s*->\s*\{\s*\}')) {
                $issues += 'legacy render path still registers an empty callback'
            }
        }
    }
    $requiredUiKeys = @(
        'config.mctranslator.chat_delivery.ordered',
        'config.mctranslator.chat_delivery.ready_first',
        $(if ($Row.HarnessKind -ceq 'legacy') {
            'config.mctranslator.chat_delivery.short'
        } else {
            'config.mctranslator.chat_delivery'
        })
    )
    foreach ($key in $requiredUiKeys) {
        if (@($Row.UiDeliveryKeys | Where-Object { $_ -ceq $key }).Count -ne 1) {
            $issues += "settings UI does not reference $key"
        }
    }

    $resourceRoot = Join-Path $Row.ProjectRoot 'src\main\resources'
    $langRoot = Join-Path $resourceRoot 'assets\mctranslator\lang'
    $langFiles = if (Test-Path -LiteralPath $langRoot -PathType Container) {
        @(Get-ChildItem -LiteralPath $langRoot -File -Force)
    } else {
        @()
    }
    if ($langFiles.Count -ne $Row.LangCount) {
        $issues += "language count $($langFiles.Count), expected $($Row.LangCount)"
    }
    $wrongExtensions = @($langFiles | Where-Object {
        $_.Extension.TrimStart('.') -cne $Row.LangExtension
    })
    if ($wrongExtensions.Count -ne 0) {
        $issues += 'unexpected language extension'
    }
    foreach ($locale in @('en_us', 'zh_tw')) {
        $languagePath = Join-Path $langRoot "$locale.$($Row.LangExtension)"
        if (-not (Test-Path -LiteralPath $languagePath -PathType Leaf)) {
            $issues += "missing language $locale.$($Row.LangExtension)"
            continue
        }
        try {
            $text = [System.IO.File]::ReadAllText(
                $languagePath, [System.Text.Encoding]::UTF8)
            Assert-DeliveryLanguageText $text $Row.LangExtension `
                "$($Row.Key) source $locale" $Row.DeliveryKeys
        } catch {
            $issues += $_.Exception.Message
        }
    }
    return [pscustomobject]@{
        Ready = $issues.Count -eq 0
        Issues = $issues
        LangCount = $langFiles.Count
    }
}

function Assert-MatrixDefinition {
    Require ($rows.Count -eq 16) "Release matrix must contain exactly 16 projects"
    Require (@($rows | Group-Object Key | Where-Object Count -ne 1).Count -eq 0) `
        'Release matrix contains duplicate keys'
    Require (@($rows | Group-Object ArtifactName | Where-Object Count -ne 1).Count -eq 0) `
        'Release matrix contains duplicate artifact names'
    Require (@($rows | Group-Object Project | Where-Object Count -ne 1).Count -eq 0) `
        'Release matrix contains duplicate project roots'
    Require (@($rows | Where-Object Loader -eq 'fabric').Count -eq 11) `
        'Release matrix must contain 11 Fabric projects'
    Require (@($rows | Where-Object Loader -eq 'forge').Count -eq 2) `
        'Release matrix must contain two Forge projects'
    Require (@($rows | Where-Object Loader -eq 'neoforge').Count -eq 3) `
        'Release matrix must contain three NeoForge projects'
    Require (@($rows | Where-Object ClassMajor -eq 52).Count -eq 5) `
        'Exactly five Java 8 artifacts are required'
    Require (@($rows | Where-Object ClassMajor -eq 60).Count -eq 1) `
        'Exactly one Java 16 artifact is required'
    Require (@($rows | Where-Object ClassMajor -eq 61).Count -eq 4) `
        'Exactly four Java 17 artifacts are required'
    Require (@($rows | Where-Object ClassMajor -eq 65).Count -eq 3) `
        'Exactly three Java 21 artifacts are required'
    Require (@($rows | Where-Object ClassMajor -eq 69).Count -eq 3) `
        'Exactly three Java 25 artifacts are required'
    Require (@($rows | Where-Object {
        $_.ClassMajor -eq 52 -and $_.RuntimeJdk -ne 8
    }).Count -eq 0) 'Every Java 8 artifact must run on repository Temurin 8'
    Require (@($rows | Where-Object {
        $_.ClassMajor -in @(60, 61, 65) -and $_.RuntimeJdk -ne 21
    }).Count -eq 0) 'Java 16/17/21 artifacts must run inline on JDK 21'
    Require (@($rows | Where-Object {
        $_.ClassMajor -eq 69 -and $_.RuntimeJdk -ne 25
    }).Count -eq 0) 'Java 25 artifacts must run inline on JDK 25'
    Require (@($rows | Where-Object {
        $_.ClassMajor -ne ($_.SourceRelease + 44)
    }).Count -eq 0) 'Source release and class-major matrix disagree'
    Require (@($rows | Where-Object GradleVersion -eq 'wrapper').Count -eq 2) `
        'Exactly two Forge projects must use local wrappers'
    Require (@($rows | Where-Object GradleVersion -eq '8.10').Count -eq 8) `
        'Exactly eight stable Loom projects must use Gradle 8.10'
    Require (@($rows | Where-Object GradleVersion -eq '8.13').Count -eq 2) `
        'Exactly two NeoForge projects must use Gradle 8.13'
    Require (@($rows | Where-Object GradleVersion -eq '9.5.0').Count -eq 4) `
        'Exactly four 1.21.11/26.x projects must use Gradle 9.5.0'
    $definedCoreRuns = 0
    foreach ($row in $rows) { $definedCoreRuns += $row.CoreHarnesses.Count }
    Require ($definedCoreRuns -eq 18) `
        'Each project needs canonical core and each Forge project also needs glue core'
    Require (@($rows | Where-Object {
        $_.Loader -ceq 'forge' -and $_.CoreHarnesses.Count -ne 2
    }).Count -eq 0) 'Each Forge project must run canonical core and Forge glue'
    Require (@($rows | Where-Object {
        $_.Loader -cne 'forge' -and $_.CoreHarnesses.Count -ne 1
    }).Count -eq 0) 'Each non-Forge project must run exactly one canonical core harness'
    Require (@($rows | Where-Object {
        $_.FinalDependencyNamespace -ceq 'fabric-intermediary'
    }).Count -eq 9) 'Exactly nine mapped Fabric projects must use intermediary runtime'
    Require (@($rows | Where-Object {
        $_.FinalDependencyNamespace -ceq 'fabric-official'
    }).Count -eq 2) 'Exactly two unobfuscated Fabric projects must use official runtime'
    Require (@($rows | Where-Object {
        $_.FinalDependencyNamespace -ceq 'forge-srg'
    }).Count -eq 2) 'Exactly two reobfuscated Forge projects must use SRG runtime'
    Require (@($rows | Where-Object {
        $_.FinalDependencyNamespace -ceq 'project-runtime'
    }).Count -eq 3) 'Exactly three NeoForge projects must use project runtime'

    foreach ($file in @(
            $initScript, $launcherSource, $sourceInitScript, $sourceLauncher, $fakeCodex,
            $fakeCodexPython, $protocolAssertion)) {
        Require (Test-Path -LiteralPath $file -PathType Leaf) `
            "Missing verification infrastructure: $file"
    }
    foreach ($row in $rows) {
        $wrapper = if ($row.GradleVersion -ceq 'wrapper') {
            Join-Path $row.ProjectRoot 'gradlew.bat'
        } else {
            [string]$gradleExecutables[$row.GradleVersion]
        }
        Require (Test-Path -LiteralPath $wrapper -PathType Leaf) `
            "Missing Gradle wrapper for $($row.Key): $wrapper"
        foreach ($harness in @($row.CoreHarnesses) + @($row.CodexHarness)) {
            $harnessPath = Join-Path $verificationRoot $harness.Source
            Require (Test-Path -LiteralPath $harnessPath -PathType Leaf) `
                "Missing $($row.HarnessKind) harness: $harnessPath"
        }
    }
}

function Read-ZipEntryText {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][string]$EntryName
    )

    $entry = $Archive.GetEntry($EntryName)
    Require ($null -ne $entry) "JAR is missing resource: $EntryName"
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

function Get-EntrySha256Lower {
    param([Parameter(Mandatory = $true)]$Entry)

    $stream = $Entry.Open()
    try {
        return Get-StreamSha256Lower $stream
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

function Assert-ArchiveClass {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][string]$ClassName,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $entryName = $ClassName.Replace('.', '/') + '.class'
    Require ($null -ne $Archive.GetEntry($entryName)) `
        "$Description is missing class $entryName"
}

function Assert-FabricMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Row
    )

    $metadata = (Read-ZipEntryText $Archive 'fabric.mod.json') | ConvertFrom-Json
    Require ([string]$metadata.id -ceq 'mctranslator') `
        "$($Row.Key) has wrong Fabric id"
    Require ([string]$metadata.version -ceq $releaseVersion) `
        "$($Row.Key) has wrong Fabric version: $($metadata.version)"
    Require ([string]$metadata.environment -ceq 'client') `
        "$($Row.Key) is not client-only"
    Require ([string]$metadata.depends.minecraft -ceq $Row.MinecraftRange) `
        "$($Row.Key) has wrong Minecraft range: $($metadata.depends.minecraft)"
    Require ([string]$metadata.depends.fabricloader -ceq $Row.LoaderRange) `
        "$($Row.Key) has wrong Fabric Loader range: $($metadata.depends.fabricloader)"
    Require ([string]$metadata.depends.java -ceq $Row.JavaRange) `
        "$($Row.Key) has wrong Java range: $($metadata.depends.java)"

    $clientEntrypoints = @($metadata.entrypoints.client)
    Require ($clientEntrypoints.Count -eq 1) `
        "$($Row.Key) must expose exactly one Fabric client entrypoint"
    Require ([string]$clientEntrypoints[0] -ceq $Row.MainClass) `
        "$($Row.Key) has wrong Fabric entrypoint: $($clientEntrypoints[0])"
    Assert-ArchiveClass $Archive $Row.MainClass "$($Row.Key) Fabric metadata"
}

function Assert-Forge1122Metadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Row
    )

    $records = @((Read-ZipEntryText $Archive 'mcmod.info') | ConvertFrom-Json)
    $matches = @($records | Where-Object {
        [string]$_.modid -ceq 'mctranslator'
    })
    Require ($matches.Count -eq 1) `
        "$($Row.Key) must contain one mctranslator mcmod.info record"
    Require ([string]$matches[0].version -ceq $releaseVersion) `
        "$($Row.Key) has wrong mcmod.info version: $($matches[0].version)"
    Require ([string]$matches[0].mcversion -ceq $Row.MinecraftRange) `
        "$($Row.Key) has wrong mcmod.info Minecraft version"

    $manifest = Read-ZipEntryText $Archive 'META-INF/MANIFEST.MF'
    $implementationVersion = Get-ManifestValue $manifest 'Implementation-Version'
    Require ($implementationVersion -ceq $releaseVersion) `
        "$($Row.Key) has wrong main-section Implementation-Version: $implementationVersion"
    Assert-ArchiveClass $Archive $Row.MainClass "$($Row.Key) Forge metadata"
}

function Assert-TomlMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Row
    )

    $toml = Read-ZipEntryText $Archive $Row.MetadataEntry
    Require ((Get-TomlValue $toml 'modLoader') -ceq 'javafml') `
        "$($Row.Key) has wrong TOML modLoader"
    Require ((Get-TomlValue $toml 'loaderVersion') -ceq $Row.LoaderRange) `
        "$($Row.Key) has wrong TOML loaderVersion"

    $modBlocks = @(Get-TomlBlocks $toml 'mods')
    $mainMods = @($modBlocks | Where-Object {
        (Get-TomlValue $_ 'modId') -ceq 'mctranslator'
    })
    Require ($mainMods.Count -eq 1) `
        "$($Row.Key) must contain one mctranslator TOML mod block"
    $tomlVersion = Get-TomlValue $mainMods[0] 'version'
    Require ($tomlVersion -ceq $Row.TomlVersion) `
        "$($Row.Key) has wrong TOML version: $tomlVersion"

    $dependencies = @(Get-TomlBlocks $toml 'dependencies.mctranslator')
    $minecraftDependencies = @($dependencies | Where-Object {
        (Get-TomlValue $_ 'modId') -ceq 'minecraft'
    })
    Require ($minecraftDependencies.Count -eq 1) `
        "$($Row.Key) must contain one Minecraft dependency"
    $minecraftRange = Get-TomlValue $minecraftDependencies[0] 'versionRange'
    Require ($minecraftRange -ceq $Row.MinecraftRange) `
        "$($Row.Key) has wrong Minecraft TOML range: $minecraftRange"

    $loaderDependencies = @($dependencies | Where-Object {
        (Get-TomlValue $_ 'modId') -ceq $Row.LoaderDependency
    })
    Require ($loaderDependencies.Count -eq 1) `
        "$($Row.Key) must contain one $($Row.LoaderDependency) dependency"
    $dependencyRange = Get-TomlValue $loaderDependencies[0] 'versionRange'
    Require ($dependencyRange -ceq $Row.LoaderDependencyRange) `
        "$($Row.Key) has wrong loader dependency range: $dependencyRange"

    if ($Row.TomlVersion -ceq '${file.jarVersion}') {
        $manifest = Read-ZipEntryText $Archive 'META-INF/MANIFEST.MF'
        $implementationVersion = Get-ManifestValue $manifest 'Implementation-Version'
        Require ($implementationVersion -ceq $releaseVersion) `
            "$($Row.Key) has wrong main-section Implementation-Version: $implementationVersion"
    }
    Assert-ArchiveClass $Archive $Row.MainClass "$($Row.Key) TOML metadata"
}

function Assert-ExactStringSet {
    param(
        [Parameter(Mandatory = $true)][string[]]$Actual,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Require ($Actual.Count -eq $Expected.Count) `
        "$Description count is $($Actual.Count), expected $($Expected.Count)"
    foreach ($expectedValue in $Expected) {
        Require (@($Actual | Where-Object { $_ -ceq $expectedValue }).Count -eq 1) `
            "$Description is missing or duplicates $expectedValue"
    }
    foreach ($actualValue in $Actual) {
        Require (@($Expected | Where-Object { $_ -ceq $actualValue }).Count -eq 1) `
            "$Description contains unexpected $actualValue"
    }
}

function Get-ClassMajor {
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $stream = $Entry.Open()
    try {
        $header = New-Object byte[] 8
        $offset = 0
        while ($offset -lt $header.Length) {
            $read = $stream.Read($header, $offset, $header.Length - $offset)
            if ($read -le 0) { break }
            $offset += $read
        }
    } finally {
        $stream.Dispose()
    }
    Require ($offset -eq 8) "$Description has a truncated class header"
    Require ($header[0] -eq 0xCA -and $header[1] -eq 0xFE -and
            $header[2] -eq 0xBA -and $header[3] -eq 0xBE) `
        "$Description has an invalid class-file magic"
    return (([int]$header[6] -shl 8) -bor [int]$header[7])
}

function Assert-JarResources {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)]$Row
    )

    $resourceRoot = Join-Path $Row.ProjectRoot 'src\main\resources'
    Require (Test-Path -LiteralPath $resourceRoot -PathType Container) `
        "$($Row.Key) source resource root is missing"
    $sourceFiles = @(Get-ChildItem -LiteralPath $resourceRoot -Recurse -File -Force)
    Require ($sourceFiles.Count -gt 0) "$($Row.Key) has no source resources"

    foreach ($source in $sourceFiles) {
        $relative = $source.FullName.Substring($resourceRoot.Length + 1).Replace('\', '/')
        $entry = $Archive.GetEntry($relative)
        Require ($null -ne $entry) `
            "$($Row.Key) JAR is missing source resource $relative"
        if ($relative.StartsWith('assets/mctranslator/lang/',
                [System.StringComparison]::Ordinal)) {
            $sourceHash = (Get-FileHash -LiteralPath $source.FullName `
                -Algorithm SHA256).Hash.ToLowerInvariant()
            $entryHash = Get-EntrySha256Lower $entry
            Require ($sourceHash -ceq $entryHash) `
                "$($Row.Key) language resource differs from source: $relative"
        }
    }

    $sourceLangEntries = @($sourceFiles | Where-Object {
        $_.DirectoryName.Equals(
            (Join-Path $resourceRoot 'assets\mctranslator\lang'),
            [System.StringComparison]::OrdinalIgnoreCase)
    } | ForEach-Object {
        'assets/mctranslator/lang/' + $_.Name
    })
    $jarLangEntries = @($Archive.Entries | Where-Object {
        $_.FullName.StartsWith('assets/mctranslator/lang/',
            [System.StringComparison]::Ordinal) -and
        -not $_.FullName.EndsWith('/', [System.StringComparison]::Ordinal)
    } | ForEach-Object { $_.FullName })
    Require ($sourceLangEntries.Count -eq $Row.LangCount) `
        "$($Row.Key) source language count changed: $($sourceLangEntries.Count)"
    Assert-ExactStringSet $jarLangEntries $sourceLangEntries `
        "$($Row.Key) JAR language entry set"

    foreach ($locale in @('en_us', 'zh_tw')) {
        $entryName = "assets/mctranslator/lang/$locale.$($Row.LangExtension)"
        $text = Read-ZipEntryText $Archive $entryName
        Assert-DeliveryLanguageText $text $Row.LangExtension `
            "$($Row.Key) JAR $entryName" $Row.DeliveryKeys
    }
    return $sourceFiles.Count
}

function Assert-FinalJar {
    param([Parameter(Mandatory = $true)]$Row)

    Require (Test-Path -LiteralPath $Row.ArtifactPath -PathType Leaf) `
        "Missing final JAR for $($Row.Key): $($Row.ArtifactPath)"
    Require ((Split-Path -Leaf $Row.ArtifactPath) -ceq $Row.ArtifactName) `
        "$($Row.Key) final JAR filename is not exact"

    $hashBefore = (Get-FileHash -LiteralPath $Row.ArtifactPath `
        -Algorithm SHA256).Hash.ToLowerInvariant()
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Row.ArtifactPath)
    try {
        $duplicates = @($archive.Entries | Group-Object FullName |
            Where-Object Count -ne 1)
        Require ($duplicates.Count -eq 0) `
            "$($Row.Key) JAR contains duplicate or case-colliding entries"

        switch ($Row.MetadataKind) {
            'fabric' { Assert-FabricMetadata $archive $Row }
            'forge1122' { Assert-Forge1122Metadata $archive $Row }
            'toml' { Assert-TomlMetadata $archive $Row }
            default { throw "Unknown metadata kind: $($Row.MetadataKind)" }
        }

        foreach ($className in $Row.RequiredClasses) {
            Assert-ArchiveClass $archive $className "$($Row.Key) required matrix"
        }
        $ownClasses = @($archive.Entries | Where-Object {
            $_.FullName -match '^com/dragonmeow/mctranslator/.+\.class$'
        })
        Require ($ownClasses.Count -gt 0) "$($Row.Key) JAR has no implementation classes"
        foreach ($entry in $ownClasses) {
            $major = Get-ClassMajor $entry "$($Row.Key):$($entry.FullName)"
            Require ($major -eq $Row.ClassMajor) `
                "$($Row.Key):$($entry.FullName) has class major $major; expected $($Row.ClassMajor)"
        }
        $resourceCount = Assert-JarResources $archive $Row
    } finally {
        $archive.Dispose()
    }
    $hashAfter = (Get-FileHash -LiteralPath $Row.ArtifactPath `
        -Algorithm SHA256).Hash.ToLowerInvariant()
    Require ($hashAfter -ceq $hashBefore) `
        "$($Row.Key) final JAR changed during validation"

    return [pscustomobject]@{
        Hash = $hashAfter
        ClassCount = $ownClasses.Count
        ResourceCount = $resourceCount
    }
}

function Invoke-WithProcessEnvironment {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    $previous = @{}
    foreach ($key in $Values.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable(
            [string]$key, 'Process')
        [Environment]::SetEnvironmentVariable(
            [string]$key, [string]$Values[$key], 'Process')
    }
    try {
        & $Action
    } finally {
        foreach ($key in $Values.Keys) {
            [Environment]::SetEnvironmentVariable(
                [string]$key, $previous[$key], 'Process')
        }
    }
}

function Invoke-GradleLogged {
    param(
        [Parameter(Mandatory = $true)][string]$Wrapper,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$JdkHome,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Require (Test-Path -LiteralPath $Wrapper -PathType Leaf) `
        "$Description wrapper is missing: $Wrapper"
    Require (Test-Path -LiteralPath $WorkingDirectory -PathType Container) `
        "$Description working directory is missing: $WorkingDirectory"
    Require (Test-Path -LiteralPath $JdkHome -PathType Container) `
        "$Description JDK is missing: $JdkHome"
    $logDirectory = Split-Path -Parent $LogPath
    if (-not (Test-Path -LiteralPath $logDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $logDirectory | Out-Null
    }

    $oldJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
    $oldPath = [Environment]::GetEnvironmentVariable('Path', 'Process')
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JdkHome, 'Process')
    [Environment]::SetEnvironmentVariable(
        'Path', (Join-Path $JdkHome 'bin') + [System.IO.Path]::PathSeparator + $oldPath,
        'Process')
    Push-Location $WorkingDirectory
    $savedErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Wrapper @Arguments 2>&1 | Tee-Object -FilePath $LogPath
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorPreference
        Pop-Location
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $oldJavaHome, 'Process')
        [Environment]::SetEnvironmentVariable('Path', $oldPath, 'Process')
    }
    Require ($exitCode -eq 0) `
        "$Description failed with exit code $exitCode; log: $LogPath"
}

function Get-GradleInvocation {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string[]]$TaskArguments
    )

    if ($Row.GradleVersion -ceq 'wrapper') {
        return [pscustomobject]@{
            Wrapper = Join-Path $Row.ProjectRoot 'gradlew.bat'
            WorkingDirectory = $Row.ProjectRoot
            Arguments = $TaskArguments
        }
    }
    $arguments = @()
    if ($Row.Project -cne '.') {
        $arguments += '-p'
        $arguments += $Row.ProjectRoot
    }
    $arguments += $TaskArguments
    return [pscustomobject]@{
        Wrapper = [string]$gradleExecutables[$Row.GradleVersion]
        WorkingDirectory = $repoRoot
        Arguments = $arguments
    }
}

function Invoke-CleanBuild {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$RunRoot,
        [Parameter(Mandatory = $true)][string]$JdkHome
    )

    $invocation = Get-GradleInvocation $Row @(
        '--no-daemon', '--max-workers=2', '--stacktrace', 'clean', 'build')
    $log = Join-Path $RunRoot ("logs\{0}\build.log" -f $Row.Key)
    Write-Output ("MATRIX_BUILD_BEGIN project={0} minecraft={1}" -f
        $Row.Key, $Row.Minecraft)
    Invoke-GradleLogged -Wrapper $invocation.Wrapper `
        -Arguments $invocation.Arguments `
        -WorkingDirectory $invocation.WorkingDirectory `
        -JdkHome $JdkHome -LogPath $log `
        -Description "$($Row.Key) clean build"
    Require (Test-Path -LiteralPath $Row.ArtifactPath -PathType Leaf) `
        "$($Row.Key) build did not create exact artifact $($Row.ArtifactName)"
    Write-Output ("MATRIX_BUILD_OK project={0} artifact={1}" -f
        $Row.Key, $Row.ArtifactPath)
}

function Get-MaterializedHarnessSource {
    param(
        [Parameter(Mandatory = $true)]$Harness,
        [Parameter(Mandatory = $true)][string]$InvocationRoot
    )

    $source = Join-Path $verificationRoot $Harness.Source
    Require (Test-Path -LiteralPath $source -PathType Leaf) `
        "Harness source is missing: $source"
    if ([string]::IsNullOrWhiteSpace([string]$Harness.TransformPackage)) {
        return $source
    }

    $canonicalText = [System.IO.File]::ReadAllText(
        $source, [System.Text.Encoding]::UTF8)
    $canonicalPackage = 'com.dragonmeow.mctranslator.legacy'
    $expectedDeclaration = "package $canonicalPackage;"
    $replacementDeclaration = "package $($Harness.TransformPackage);"
    $occurrences = [regex]::Matches(
        $canonicalText, [regex]::Escape($expectedDeclaration)).Count
    Require ($occurrences -eq 1) `
        "Canonical legacy harness package declaration changed; refusing a partial transform"
    $transformed = $canonicalText.Replace(
        $canonicalPackage, [string]$Harness.TransformPackage)
    Require ($transformed.Contains($replacementDeclaration) -and
            -not $transformed.Contains($canonicalPackage)) `
        'Canonical-to-Forge harness transform left a legacy package reference behind'

    $generated = Join-Path $InvocationRoot 'generated\InlineLegacyCoreSimulation.java'
    $generatedDirectory = Split-Path -Parent $generated
    if (-not (Test-Path -LiteralPath $generatedDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $generatedDirectory | Out-Null
    }
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($generated, $transformed, $utf8NoBom)
    return $generated
}

function Invoke-InlineHarness {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)]$Harness,
        [Parameter(Mandatory = $true)][ValidateSet('source', 'final')]
        [string]$Mode,
        [Parameter(Mandatory = $true)][string]$InvocationName,
        [Parameter(Mandatory = $true)][string]$RunRoot,
        [Parameter(Mandatory = $true)][string]$GradleJdk,
        [Parameter(Mandatory = $true)][string]$RuntimeJdk,
        [string]$EarlyTurn = '',
        [string]$CompletedFirst = ''
    )

    $invocationRoot = Join-Path $RunRoot ("inline\{0}\{1}\{2}" -f
        $Mode, $Row.Key, $InvocationName)
    Require (-not (Test-Path -LiteralPath $invocationRoot)) `
        "Inline invocation path already exists: $invocationRoot"
    $runtime = Join-Path $invocationRoot 'runtime'
    $output = Join-Path $invocationRoot 'classes'
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $harnessSource = Get-MaterializedHarnessSource $Harness $invocationRoot
    $fakeLog = Join-Path $invocationRoot 'fake-codex.jsonl'
    $gradleLog = Join-Path $invocationRoot 'gradle.log'
    $anchors = [string]::Join(';', [string[]]$Row.RequiredClasses)

    $environment = @{
        MCTRANSLATOR_CODEX_PATH = $fakeCodex
        MCTRANSLATOR_FAKE_LOG = $fakeLog
        MCTRANSLATOR_FAKE_EARLY_TURN = $EarlyTurn
        MCTRANSLATOR_FAKE_COMPLETED_FIRST = $CompletedFirst
    }
    if ($Mode -ceq 'final') {
        $environment.MCTRANSLATOR_FINAL_JAR = $Row.ArtifactPath
        $environment.MCTRANSLATOR_FINAL_HARNESS = $harnessSource
        $environment.MCTRANSLATOR_FINAL_LAUNCHER = $launcherSource
        $environment.MCTRANSLATOR_FINAL_MAIN = $Harness.Main
        $environment.MCTRANSLATOR_FINAL_ANCHORS = $anchors
        $environment.MCTRANSLATOR_FINAL_OUTPUT = $output
        $environment.MCTRANSLATOR_FINAL_RUNTIME = $runtime
        $environment.MCTRANSLATOR_FINAL_JAVA = Join-Path $RuntimeJdk 'bin\java.exe'
        $environment.MCTRANSLATOR_FINAL_JAVAC = Join-Path $RuntimeJdk 'bin\javac.exe'
        $environment.MCTRANSLATOR_FINAL_JAVA_RELEASE = [string]$Row.SourceRelease
        $environment.MCTRANSLATOR_FINAL_DEPENDENCY_NAMESPACE = `
            $Row.FinalDependencyNamespace
        $selectedInit = $initScript
        $selectedTask = 'finalJarInline'
        $classpathMarker = 'FINAL_JAR_CLASSPATH_OK'
        $codeSourceMarker = 'FINAL_JAR_CODE_SOURCE_OK'
    } else {
        $environment.MCTRANSLATOR_SOURCE_HARNESS = $harnessSource
        $environment.MCTRANSLATOR_SOURCE_LAUNCHER = $sourceLauncher
        $environment.MCTRANSLATOR_SOURCE_MAIN = $Harness.Main
        $environment.MCTRANSLATOR_SOURCE_ANCHORS = $anchors
        $environment.MCTRANSLATOR_SOURCE_OUTPUT = $output
        $environment.MCTRANSLATOR_SOURCE_RUNTIME = $runtime
        $environment.MCTRANSLATOR_SOURCE_JAVA = Join-Path $RuntimeJdk 'bin\java.exe'
        $environment.MCTRANSLATOR_SOURCE_JAVAC = Join-Path $RuntimeJdk 'bin\javac.exe'
        $environment.MCTRANSLATOR_SOURCE_JAVA_RELEASE = [string]$Row.SourceRelease
        $selectedInit = $sourceInitScript
        $selectedTask = 'sourceOutputInline'
        $classpathMarker = 'SOURCE_OUTPUT_CLASSPATH_OK'
        $codeSourceMarker = 'SOURCE_OUTPUT_CODE_SOURCE_OK'
    }
    $taskArguments = @(
        '--no-daemon', '--max-workers=2', '--stacktrace',
        '--init-script', $selectedInit, $selectedTask)
    $gradle = Get-GradleInvocation $Row $taskArguments
    Write-Host ("MATRIX_INLINE_BEGIN mode={0} project={1} run={2} main={3}" -f
        $Mode, $Row.Key, $InvocationName, $Harness.Main)
    Invoke-WithProcessEnvironment $environment {
        Invoke-GradleLogged -Wrapper $gradle.Wrapper `
            -Arguments $gradle.Arguments `
            -WorkingDirectory $gradle.WorkingDirectory `
            -JdkHome $GradleJdk -LogPath $gradleLog `
            -Description "$($Row.Key) $Mode $InvocationName inline"
    } | Out-Host

    $logText = [System.IO.File]::ReadAllText(
        $gradleLog, [System.Text.Encoding]::UTF8)
    Require ([regex]::Matches(
        $logText, ("(?m)^{0} phase=compile\b" -f
            [regex]::Escape($classpathMarker))).Count -eq 1) `
        "$($Row.Key) $InvocationName did not prove its compile classpath"
    Require ([regex]::Matches(
        $logText, ("(?m)^{0} phase=execute\b" -f
            [regex]::Escape($classpathMarker))).Count -eq 1) `
        "$($Row.Key) $InvocationName did not prove its execution classpath"
    if ($Mode -ceq 'final') {
        $expectedNamespace = $Row.FinalDependencyNamespace
        foreach ($classpathPhase in @('compile', 'execute')) {
            $namespacePattern = ("(?m)^FINAL_JAR_DEPENDENCY_NAMESPACE_OK " +
                "phase={0} namespace={1} productionFiles=\d+ minecraft=\d+\r?$" -f
                [regex]::Escape($classpathPhase),
                [regex]::Escape($expectedNamespace))
            Require ([regex]::Matches(
                $logText, $namespacePattern).Count -eq 1) `
                ("$($Row.Key) $InvocationName did not prove the exact " +
                    "$expectedNamespace dependency namespace for $classpathPhase")
        }
    }
    Require ([regex]::Matches(
        $logText, ("(?m)^{0}\b" -f
            [regex]::Escape($codeSourceMarker))).Count -eq 1) `
        "$($Row.Key) $InvocationName did not prove exact $Mode CodeSource"
    Require ($logText.Contains([string]$Harness.Marker)) `
        "$($Row.Key) $InvocationName is missing marker $($Harness.Marker)"
    Write-Host ("MATRIX_INLINE_OK mode={0} project={1} run={2} codesource={3}" -f
        $Mode, $Row.Key, $InvocationName, $codeSourceMarker)

    return [pscustomobject]@{
        FakeLog = $fakeLog
        GradleLog = $gradleLog
    }
}

function Assert-CodexProtocol {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$ProtocolLog
    )

    Require (Test-Path -LiteralPath $LogPath -PathType Leaf) `
        "Codex fake log was not created: $LogPath"
    $output = @(& $protocolAssertion -LogPath $LogPath 2>&1)
    $text = [string]::Join([Environment]::NewLine, @($output | ForEach-Object {
        [string]$_
    }))
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($ProtocolLog, $text, $utf8NoBom)
    Require ($text.Contains('INLINE_PROTOCOL_OK')) `
        "Codex protocol assertion did not complete: $LogPath"
    Write-Host $text
}

function Invoke-ProjectInlineSuite {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][ValidateSet('source', 'final')]
        [string]$Mode,
        [Parameter(Mandatory = $true)][string]$RunRoot,
        [Parameter(Mandatory = $true)][string]$GradleJdk,
        [Parameter(Mandatory = $true)][string]$RuntimeJdk
    )

    $coreCount = 0
    $forgeGlueCount = 0
    $codexCount = 0
    $protocolCount = 0
    $codeSourceCount = 0
    $coreIndex = 0
    foreach ($harness in $Row.CoreHarnesses) {
        $coreIndex++
        $coreName = if (-not [string]::IsNullOrWhiteSpace(
                [string]$harness.TransformPackage)) {
            "core-$coreIndex-canonical"
        } else {
            "core-$coreIndex"
        }
        $null = Invoke-InlineHarness -Row $Row -Harness $harness `
            -Mode $Mode -InvocationName $coreName -RunRoot $RunRoot `
            -GradleJdk $GradleJdk -RuntimeJdk $RuntimeJdk
        $coreCount++
        $codeSourceCount++
        if ($harness.Marker -ceq 'INLINE_FORGE_GLUE_OK scenarios=725760') {
            $forgeGlueCount++
        }
    }

    $scenarioIndex = 0
    foreach ($scenario in @(
            [pscustomobject]@{ Name = 'normal'; Early = ''; Completed = '' },
            [pscustomobject]@{ Name = 'early'; Early = '1'; Completed = '' },
            [pscustomobject]@{ Name = 'completed-first'; Early = ''; Completed = '1' },
            [pscustomobject]@{ Name = 'early-completed-first'; Early = '1'; Completed = '1' }
        )) {
        $scenarioIndex++
        $invocationName = "codex-$scenarioIndex-$($scenario.Name)"
        $result = Invoke-InlineHarness -Row $Row `
            -Harness $Row.CodexHarness -Mode $Mode `
            -InvocationName $invocationName -RunRoot $RunRoot `
            -GradleJdk $GradleJdk -RuntimeJdk $RuntimeJdk `
            -EarlyTurn $scenario.Early -CompletedFirst $scenario.Completed
        Require ($null -ne $result -and
                $null -ne $result.PSObject.Properties['FakeLog']) `
            "$($Row.Key) $Mode $invocationName returned no inline result"
        $protocolLog = Join-Path (
            Split-Path -Parent $result.FakeLog) 'protocol.log'
        Assert-CodexProtocol $result.FakeLog $protocolLog
        $codexCount++
        $protocolCount++
        $codeSourceCount++
    }

    return [pscustomobject]@{
        Core = $coreCount
        ForgeGlue = $forgeGlueCount
        Codex = $codexCount
        Protocol = $protocolCount
        CodeSource = $codeSourceCount
    }
}

Assert-MatrixDefinition

$jdkHomes = @{}
foreach ($major in @(8, 21, 25)) {
    $jdkHomes[$major] = Resolve-Jdk $major -AllowMissing:$DryRun.IsPresent
}

$sourceReadiness = @{}
foreach ($row in $rows) {
    $readiness = Get-SourceReadiness $row
    $sourceReadiness[$row.Key] = $readiness
    if (-not $DryRun) {
        Require ($readiness.Ready) `
            "$($Row.Key) source preflight failed: $($readiness.Issues -join '; ')"
    }
}

$initText = [System.IO.File]::ReadAllText(
    $initScript, [System.Text.Encoding]::UTF8)
Require ($initText.Contains('project.configurations.runtimeClasspath')) `
    'Final-JAR init script does not use dependency-only runtimeClasspath'
Require ($initText.Contains("tasks.named('generateRemapClasspath')")) `
    'Mapped Fabric final-JAR verification does not use Loom production classpath'
Require ($initText.Contains('MCTRANSLATOR_FINAL_DEPENDENCY_NAMESPACE')) `
    'Final-JAR init script does not require an explicit production namespace'
Require (-not $initText.Contains('project.sourceSets.main.runtimeClasspath')) `
    'Final-JAR init script must not put source-set runtime output on a classpath'
Require ($initText.Contains('FINAL_JAR_CLASSPATH_OK')) `
    'Final-JAR init script lacks its classpath proof marker'
Require ($initText.Contains('options.forkOptions.executable')) `
    'Final-JAR init script does not pin the exact javac executable'
Require ($initText.Contains("executable = javaExecutable.absolutePath")) `
    'Final-JAR init script does not pin the exact java executable'

$launcherText = [System.IO.File]::ReadAllText(
    $launcherSource, [System.Text.Encoding]::UTF8)
Require ($launcherText.Contains('getProtectionDomain().getCodeSource()')) `
    'Final-JAR launcher lacks a CodeSource proof'
Require ($launcherText.Contains('getResources(resourceName)')) `
    'Final-JAR launcher does not reject duplicate class resources'

$sourceInitText = [System.IO.File]::ReadAllText(
    $sourceInitScript, [System.Text.Encoding]::UTF8)
Require ($sourceInitText.Contains('project.sourceSets.main.runtimeClasspath')) `
    'Source-output init script does not execute compiled source outputs'
Require ($sourceInitText.Contains('SOURCE_OUTPUT_CLASSPATH_OK')) `
    'Source-output init script lacks its classpath proof marker'
Require ($sourceInitText.Contains('options.forkOptions.executable')) `
    'Source-output init script does not pin the exact javac executable'
Require ($sourceInitText.Contains("executable = javaExecutable.absolutePath")) `
    'Source-output init script does not pin the exact java executable'
$sourceLauncherText = [System.IO.File]::ReadAllText(
    $sourceLauncher, [System.Text.Encoding]::UTF8)
Require ($sourceLauncherText.Contains('SOURCE_OUTPUT_CODE_SOURCE_OK')) `
    'Source-output launcher lacks a CodeSource proof marker'
Require ($sourceLauncherText.Contains('getResources(resourceName)')) `
    'Source-output launcher does not reject duplicate class resources'

if ($DryRun) {
    foreach ($major in @(8, 21, 25)) {
        $jdk = $jdkHomes[$major]
        $state = if ($null -eq $jdk) { 'missing' } else { [string]$jdk }
        Write-Output ("MATRIX_DRY_JDK major={0} home={1}" -f $major, $state)
    }
    foreach ($row in $rows) {
        $readiness = $sourceReadiness[$row.Key]
        $issues = if ($readiness.Issues.Count -eq 0) {
            'none'
        } else {
            [string]::Join(' | ', [string[]]$readiness.Issues)
        }
        Write-Output (("MATRIX_DRY_PROJECT project={0} loader={1} minecraft={2} " +
            "artifact={3} gradle={4} buildJdk={5} runtimeJdk={6} sourceRelease={7} " +
            "classMajor={8} coreHarnesses={9} langs={10} deliveryKeys={11} " +
            "finalNamespace={12} sourceReady={13} issues={14}") -f
            $row.Key, $row.Loader, $row.Minecraft, $row.ArtifactName,
            $row.GradleVersion, $row.BuildJdk, $row.RuntimeJdk,
            $row.SourceRelease, $row.ClassMajor,
            $row.CoreHarnesses.Count, $readiness.LangCount,
            $row.DeliveryKeys.Count, $row.FinalDependencyNamespace,
            $readiness.Ready, $issues)
    }
    $coreHarnessCount = 0
    foreach ($row in $rows) { $coreHarnessCount += $row.CoreHarnesses.Count }
    Require ($coreHarnessCount -eq 18) `
        "Dry matrix expected 18 core runs (16 canonical + two Forge glue), got $coreHarnessCount"
    Write-Output (("MATRIX_DRY_OK projects=16 builds=16 finalJars=16 " +
        "sourceCore={0} finalCore={0} coreTotal={1} sourceCodex=64 finalCodex=64 " +
        "codexTotal=128 protocolTotal=128 sourceCodeSource=82 finalCodeSource=82 " +
        "package=false runDirCreated=false") -f
        $coreHarnessCount, ($coreHarnessCount * 2))
    return
}

$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' +
    [guid]::NewGuid().ToString('N')
$runRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $verificationRoot "matrix-run-$runId"))
$verificationPrefix = $verificationRoot.TrimEnd([char[]]@('\', '/')) +
    [System.IO.Path]::DirectorySeparatorChar
Require ($runRoot.StartsWith(
        $verificationPrefix, [System.StringComparison]::OrdinalIgnoreCase)) `
    "Matrix run root escapes verification: $runRoot"
Require ([System.IO.Path]::GetDirectoryName($runRoot).Equals(
        $verificationRoot, [System.StringComparison]::OrdinalIgnoreCase)) `
    "Matrix run root must be a unique direct child of verification: $runRoot"
Require (-not (Test-Path -LiteralPath $runRoot)) `
    "Unique matrix run root already exists: $runRoot"
New-Item -ItemType Directory -Path $runRoot | Out-Null

$counts = [pscustomobject]@{
    Builds = 0
    Jars = 0
    Metadata = 0
    ClassMajor = 0
    Resources = 0
    SourceCore = 0
    FinalCore = 0
    SourceForgeGlue = 0
    FinalForgeGlue = 0
    SourceCodex = 0
    FinalCodex = 0
    SourceProtocol = 0
    FinalProtocol = 0
    SourceCodeSource = 0
    FinalCodeSource = 0
}
$currentProject = 'preflight'

try {
    if ($Phase -ceq 'All' -or $Phase -ceq 'Source') {
        foreach ($row in $rows) {
            $currentProject = $row.Key
            $gradleJdk = [string]$jdkHomes[[int]$row.BuildJdk]
            $runtimeJdk = [string]$jdkHomes[[int]$row.RuntimeJdk]
            $suite = Invoke-ProjectInlineSuite -Row $row -Mode 'source' `
                -RunRoot $runRoot -GradleJdk $gradleJdk -RuntimeJdk $runtimeJdk
            $counts.SourceCore += $suite.Core
            $counts.SourceForgeGlue += $suite.ForgeGlue
            $counts.SourceCodex += $suite.Codex
            $counts.SourceProtocol += $suite.Protocol
            $counts.SourceCodeSource += $suite.CodeSource
            Write-Output (("MATRIX_SOURCE_PROJECT_OK project={0} minecraft={1} " +
                "core={2} codex=4 protocol=4 codesource={3}") -f
                $row.Key, $row.Minecraft, $suite.Core, $suite.CodeSource)
        }
    }

    if ($Phase -ceq 'All' -or $Phase -ceq 'Build') {
        foreach ($row in $rows) {
            $currentProject = $row.Key
            $buildJdk = [string]$jdkHomes[[int]$row.BuildJdk]
            Invoke-CleanBuild $row $runRoot $buildJdk
            $counts.Builds++
        }
    }

    if ($Phase -ceq 'All' -or $Phase -ceq 'FinalJar') {
        foreach ($row in $rows) {
            $currentProject = $row.Key
            Write-Output ("MATRIX_JAR_BEGIN project={0} path={1}" -f
                $row.Key, $row.ArtifactPath)
            $jarResult = Assert-FinalJar $row
            $counts.Jars++
            $counts.Metadata++
            $counts.ClassMajor++
            $counts.Resources++

            $gradleJdk = [string]$jdkHomes[[int]$row.BuildJdk]
            $runtimeJdk = [string]$jdkHomes[[int]$row.RuntimeJdk]
            $suite = Invoke-ProjectInlineSuite -Row $row -Mode 'final' `
                -RunRoot $runRoot -GradleJdk $gradleJdk -RuntimeJdk $runtimeJdk
            $counts.FinalCore += $suite.Core
            $counts.FinalForgeGlue += $suite.ForgeGlue
            $counts.FinalCodex += $suite.Codex
            $counts.FinalProtocol += $suite.Protocol
            $counts.FinalCodeSource += $suite.CodeSource

            Write-Output (("MATRIX_PROJECT_OK project={0} minecraft={1} sha256={2} " +
                "classes={3} resources={4} core={5} codex=4 protocol=4 codesource={6}") -f
                $row.Key, $row.Minecraft, $jarResult.Hash,
                $jarResult.ClassCount, $jarResult.ResourceCount,
                $suite.Core, $suite.CodeSource)
        }
    }
} catch {
    Write-Error ("MATRIX_FAIL project={0} phase={1} run={2} cause={3}" -f
        $currentProject, $Phase, $runRoot, $_.Exception.Message)
    throw
}

$expectedBuilds = if ($Phase -ceq 'All' -or $Phase -ceq 'Build') { 16 } else { 0 }
$expectedFinal = if ($Phase -ceq 'All' -or $Phase -ceq 'FinalJar') { 16 } else { 0 }
$expectedSource = if ($Phase -ceq 'All' -or $Phase -ceq 'Source') { 16 } else { 0 }
$expectedSourceCore = if ($expectedSource -eq 16) { 18 } else { 0 }
$expectedFinalCore = if ($expectedFinal -eq 16) { 18 } else { 0 }
$expectedSourceCodex = if ($expectedSource -eq 16) { 64 } else { 0 }
$expectedFinalCodex = if ($expectedFinal -eq 16) { 64 } else { 0 }
$expectedSourceCodeSource = if ($expectedSource -eq 16) { 82 } else { 0 }
$expectedFinalCodeSource = if ($expectedFinal -eq 16) { 82 } else { 0 }
Require ($counts.Builds -eq $expectedBuilds) `
    "Matrix build count is $($counts.Builds), expected $expectedBuilds"
Require ($counts.Jars -eq $expectedFinal -and
        $counts.Metadata -eq $expectedFinal -and
        $counts.ClassMajor -eq $expectedFinal -and
        $counts.Resources -eq $expectedFinal) `
    'Matrix final-JAR validation counts are incomplete'
Require ($counts.SourceCore -eq $expectedSourceCore -and
        $counts.FinalCore -eq $expectedFinalCore) `
    'Matrix source/final core counts are incomplete'
Require ($counts.SourceForgeGlue -eq $(if ($expectedSource -eq 16) { 2 } else { 0 }) -and
        $counts.FinalForgeGlue -eq $(if ($expectedFinal -eq 16) { 2 } else { 0 })) `
    'Matrix source/final Forge glue counts are incomplete'
Require ($counts.SourceCodex -eq $expectedSourceCodex -and
        $counts.SourceProtocol -eq $expectedSourceCodex -and
        $counts.FinalCodex -eq $expectedFinalCodex -and
        $counts.FinalProtocol -eq $expectedFinalCodex) `
    'Matrix source/final Codex protocol counts are incomplete'
Require ($counts.SourceCodeSource -eq $expectedSourceCodeSource -and
        $counts.FinalCodeSource -eq $expectedFinalCodeSource) `
    'Matrix source/final CodeSource counts are incomplete'

Write-Output (("MATRIX_OK phase={0} projects=16 builds={1} jars={2} metadata={3} " +
    "classMajor={4} resources={5} sourceCore={6} finalCore={7} " +
    "sourceForgeGlue={8} finalForgeGlue={9} sourceCodex={10} finalCodex={11} " +
    "sourceProtocol={12} finalProtocol={13} sourceCodeSource={14} " +
    "finalCodeSource={15} package=false run={16}") -f
    $Phase, $counts.Builds, $counts.Jars, $counts.Metadata,
    $counts.ClassMajor, $counts.Resources, $counts.SourceCore, $counts.FinalCore,
    $counts.SourceForgeGlue, $counts.FinalForgeGlue,
    $counts.SourceCodex, $counts.FinalCodex,
    $counts.SourceProtocol, $counts.FinalProtocol,
    $counts.SourceCodeSource, $counts.FinalCodeSource, $runRoot)
