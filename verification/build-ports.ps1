[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [Alias('Target')]
    [string[]]$Targets = @(),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$python = (Get-Command python -ErrorAction Stop).Source
$commonGit = & git -C $repoRoot rev-parse --path-format=absolute --git-common-dir
if ($LASTEXITCODE -ne 0) { throw 'Cannot locate the repository tool directory.' }
$toolRoot = Split-Path -Parent ($commonGit.Trim())

# release_matrix.py checks exact coverage and duplicate targets. Do not maintain
# a second target list here: target-specific source/dependency pins live in JSON.
$matrixJson = & $python (Join-Path $PSScriptRoot 'release_matrix.py')
if ($LASTEXITCODE -ne 0) { throw 'The release matrix is invalid.' }
$matrixRows = ConvertFrom-Json -InputObject ($matrixJson -join "`n")
$matrix = @($matrixRows | Where-Object { $_.expanded })
$knownKeys = @($matrix | ForEach-Object { '{0}/{1}' -f $_.loader, $_.minecraft })
$requested = @($Targets | ForEach-Object { $_.ToLowerInvariant() } | Select-Object -Unique)
$unknown = @($requested | Where-Object { $_ -notin $knownKeys })
if ($unknown.Count -gt 0) {
    throw "Unknown expanded port target(s): $($unknown -join ', '). Maintained targets use verify-release-matrix.ps1."
}
$selected = @($matrix | Where-Object {
    $requested.Count -eq 0 -or ('{0}/{1}' -f $_.loader, $_.minecraft) -in $requested
})
if ($selected.Count -eq 0) { throw 'No port targets selected.' }

function Find-LauncherJdk {
    param([int]$Major)
    $candidates = @(
        $env:JAVA_HOME,
        (Join-Path $env:ProgramFiles "Java\jdk-$Major"),
        (Join-Path $toolRoot ".jdks\jdk-$Major")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($candidate in $candidates) {
        $release = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
                (Test-Path -LiteralPath $release) -and
                ((Get-Content -LiteralPath $release -Raw) -match "(?m)^JAVA_VERSION=`"$Major(?:[.\-`"])") ) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw "JDK $Major is required. Install it in Program Files\Java\jdk-$Major, .jdks\jdk-$Major, or select it with JAVA_HOME."
}

$plans = @(
    foreach ($target in $selected) {
        $projectPath = Join-Path $repoRoot $target.project
        $specs = Get-Content -LiteralPath (Join-Path $projectPath 'targets.json') -Raw | ConvertFrom-Json
        $spec = $specs.PSObject.Properties[$target.minecraft].Value
        switch ($target.project) {
            'ports/fabric-legacy' {
                $gradleVersion = '8.10'; $launcherJava = 21
                $gradleHome = Join-Path $toolRoot '.gradle-agent-home'
            }
            'ports/fabric-modern' {
                $gradleVersion = '9.5.0'; $launcherJava = 25
                $gradleHome = Join-Path $toolRoot '.gradle-agent-home\fabric-modern'
            }
            'ports/neoforge' {
                $gradleVersion = if ($target.java -ge 25) { '9.5.0' } else { '8.13' }
                $launcherJava = if ($target.java -ge 25) { 25 } else { 21 }
                $gradleHome = Join-Path $toolRoot '.gradle-agent-home\neoforge-ports'
            }
            default { throw "No toolchain policy for $($target.project)." }
        }
        $gradle = Join-Path $toolRoot ".gradle-local\gradle-$gradleVersion\bin\gradle.bat"
        if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
            throw "Missing Gradle $gradleVersion at $gradle."
        }
        $toolchainProperty = $spec.PSObject.Properties['toolchain']
        $compilerJava = if ($target.project -eq 'ports/fabric-legacy') { $launcherJava }
            elseif ($toolchainProperty) { [int]$toolchainProperty.Value }
            else { [int]$spec.java }
        [pscustomobject]@{
            Target = '{0}/{1}' -f $target.loader, $target.minecraft
            Project = $target.project
            Minecraft = $target.minecraft
            GradleVersion = $gradleVersion
            LauncherJava = $launcherJava
            CompilerJava = $compilerJava
            ClassTargetJava = [int]$target.java
            JavaHome = Find-LauncherJdk $launcherJava
            GradleExecutable = $gradle
            GradleUserHome = $gradleHome
            ProjectPath = $projectPath
            ProjectCache = Join-Path $projectPath ".gradle\targets\$($target.minecraft)"
            Jar = Join-Path $repoRoot $target.jar
        }
    }
)

if ($DryRun) {
    Write-Host "PORT_BUILD_PLAN targets=$($plans.Count) dryRun=True"
    $plans
    return
}

# This process-local JDK workaround makes Windows fall back to its TCP wakeup
# pipe when AF_UNIX is unavailable in a desktop subprocess. Never create this
# deliberately absent directory or modify system/network settings.
$socketFallback = Join-Path $toolRoot '.gradle-agent-home\absent-port-socket-directory'
if (Test-Path -LiteralPath $socketFallback) {
    throw "The socket fallback path must remain absent: $socketFallback"
}
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$savedLocation = Get-Location
try {
    Set-Location -LiteralPath $repoRoot
    $env:JAVA_OPTS = (($savedEnvironment['JAVA_OPTS'], '-Xmx3G') -join ' ').Trim()
    $env:JAVA_TOOL_OPTIONS = (($savedEnvironment['JAVA_TOOL_OPTIONS'],
            ('-Djdk.net.unixdomain.tmpdir="{0}"' -f $socketFallback)) -join ' ').Trim()
    foreach ($plan in $plans) {
        $env:JAVA_HOME = $plan.JavaHome
        Write-Host "BUILD_PORT $($plan.Target) Gradle=$($plan.GradleVersion) launcherJava=$($plan.LauncherJava)"
        $logDirectory = Join-Path $plan.ProjectPath 'build\logs'
        New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
        $log = Join-Path $logDirectory "$($plan.Minecraft).log"
        $gradleArguments = @(
            '-g', $plan.GradleUserHome,
            '--project-cache-dir', $plan.ProjectCache,
            '-p', $plan.ProjectPath,
            "-Ptarget=$($plan.Minecraft)",
            '--console=plain', '--no-daemon', '--max-workers=2',
            'clean', 'build'
        )
        # Windows PowerShell treats Java's informational stderr as an error
        # record. The executable exit code, not its output stream, is decisive.
        $savedErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $plan.GradleExecutable @gradleArguments 2>&1 | Tee-Object -FilePath $log | Out-Host
            $buildExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $savedErrorAction
        }
        if ($buildExitCode -ne 0) { throw "Build failed for $($plan.Target). See $log" }
        if (-not (Test-Path -LiteralPath $plan.Jar -PathType Leaf)) {
            throw "Build did not produce the expected artifact: $($plan.Jar)"
        }
    }
    $verificationArguments = @((Join-Path $PSScriptRoot 'verify-port-artifacts.py'))
    if ($requested.Count -gt 0) { $verificationArguments += @($plans | ForEach-Object { $_.Target }) }
    & $python @verificationArguments
    if ($LASTEXITCODE -ne 0) { throw 'Port artifact/static verification failed; do not publish these artifacts.' }
    Write-Host "PORT_BUILD_COMPLETE targets=$($plans.Count) runtimeTested=False"
} finally {
    Set-Location -LiteralPath $savedLocation.Path
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
