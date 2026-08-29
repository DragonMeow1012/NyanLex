[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
. (Join-Path $PSScriptRoot 'release-transaction.ps1')

function Require-TestCondition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-TestTreeHasMarker {
    param([string]$Path, [string]$Marker)

    Require-TestCondition (Test-Path -LiteralPath $Path -PathType Container) `
        "Missing test tree: $Path"
    $files = @(Get-ChildItem -LiteralPath $Path -Force -Recurse -File)
    Require-TestCondition ($files.Count -eq 1 -and $files[0].Name -ceq $Marker) `
        "Test tree marker mismatch at ${Path}: $($files.Name -join ', ')"
}

function Assert-TestTreeHasNoReparsePoint {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $items = @(
        Get-Item -LiteralPath $Path -Force
        Get-ChildItem -LiteralPath $Path -Force -Recurse
    )
    foreach ($item in $items) {
        $isReparse = ([int]$item.Attributes -band
            [int][System.IO.FileAttributes]::ReparsePoint) -ne 0
        Require-TestCondition (-not $isReparse) `
            "Test tree unexpectedly contains a reparse point: $($item.FullName)"
    }
}

function New-TestScenario {
    param([string]$Name, [bool]$ExistingRelease)

    $parent = Join-Path $script:runRoot $Name
    New-Item -ItemType Directory -Path $parent | Out-Null
    $release = Join-Path $parent 'release'
    $stage = Join-Path $parent 'stage'
    $backup = Join-Path $parent 'backup'
    $failed = Join-Path $parent 'failed'
    $cleaned = Join-Path $parent 'cleaned-backup'
    New-Item -ItemType Directory -Path $stage | Out-Null
    [System.IO.File]::WriteAllText(
        (Join-Path $stage 'new.marker'), 'new', [System.Text.UTF8Encoding]::new($false))
    if ($ExistingRelease) {
        New-Item -ItemType Directory -Path $release | Out-Null
        [System.IO.File]::WriteAllText(
            (Join-Path $release 'old.marker'), 'old', [System.Text.UTF8Encoding]::new($false))
    }
    return [pscustomobject]@{
        Release = $release
        Stage = $stage
        Backup = $backup
        Failed = $failed
        Cleaned = $cleaned
    }
}

function Invoke-TestSwap {
    param(
        [Parameter(Mandatory = $true)]$Scenario,
        [bool]$ExistingRelease,
        [string]$FaultPoint = ''
    )

    $assertExpected = {
        param([string]$Path, [string]$Description)
        Assert-TestTreeHasMarker $Path 'old.marker'
    }
    $assertMovable = {
        param([string]$Path, [string]$Description)
        Assert-TestTreeHasNoReparsePoint $Path
        Require-TestCondition (Test-Path -LiteralPath $Path -PathType Container) `
            "$Description is not a directory: $Path"
    }
    $assertInstalled = {
        param([string]$Path, [string]$Description)
        Assert-TestTreeHasMarker $Path 'new.marker'
    }
    $cleaned = [string]$Scenario.Cleaned
    $removeBackup = {
        param([string]$Path)
        Assert-TestTreeHasMarker $Path 'old.marker'
        Require-TestCondition (-not (Test-Path -LiteralPath $cleaned)) `
            "Cleanup sink already exists: $cleaned"
        [System.IO.Directory]::Move($Path, $cleaned)
    }

    return Invoke-ValidatedReleaseSwap `
        -ReleaseRoot $Scenario.Release `
        -StageRoot $Scenario.Stage `
        -BackupRoot $Scenario.Backup `
        -FailedInstallRoot $Scenario.Failed `
        -HadExistingRelease $ExistingRelease `
        -AssertExpectedTree $assertExpected `
        -AssertMovableTree $assertMovable `
        -AssertInstalledTree $assertInstalled `
        -RemoveValidatedBackup $removeBackup `
        -FaultPoint $FaultPoint
}

function Invoke-ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string[]]$MessageFragments
    )

    $caught = $null
    try {
        $null = & $Action
    } catch {
        $caught = $_
    }
    Require-TestCondition ($null -ne $caught) 'Expected transaction failure did not occur'
    foreach ($fragment in $MessageFragments) {
        Require-TestCondition ($caught.Exception.Message.Contains($fragment)) `
            "Transaction error omitted '$fragment': $($caught.Exception.Message)"
    }
}

$verificationRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$runName = 'transaction-dry-' + [guid]::NewGuid().ToString('N')
$runRoot = [System.IO.Path]::GetFullPath((Join-Path $verificationRoot $runName))
$prefix = $verificationRoot.TrimEnd([char[]]@('\', '/')) +
    [System.IO.Path]::DirectorySeparatorChar
Require-TestCondition ($runRoot.StartsWith(
        $prefix, [System.StringComparison]::OrdinalIgnoreCase)) `
    "Test run escapes verification: $runRoot"
Require-TestCondition ((Split-Path -Leaf $runRoot) -cmatch
        '^transaction-dry-[0-9a-f]{32}$') `
    "Unsafe test run name: $runRoot"
Require-TestCondition (-not (Test-Path -LiteralPath $runRoot)) `
    "Unique test run already exists: $runRoot"

try {
    New-Item -ItemType Directory -Path $runRoot | Out-Null

    $installMove = New-TestScenario 'install-move' $true
    Invoke-ExpectedFailure {
        Invoke-TestSwap $installMove $true 'install-move'
    } @('old release was restored', $installMove.Stage)
    Assert-TestTreeHasMarker $installMove.Release 'old.marker'
    Assert-TestTreeHasMarker $installMove.Stage 'new.marker'
    Require-TestCondition (-not (Test-Path -LiteralPath $installMove.Backup)) `
        'Install-move rollback leaked backup'

    $transientInstallMove = New-TestScenario 'transient-install-move' $true
    $transientResult = Invoke-TestSwap `
        $transientInstallMove $true 'install-move-once'
    Require-TestCondition ($transientResult.ReplacedExistingRelease -and
            $transientResult.BackupRemoved) `
        'Transient install retry returned the wrong result'
    Assert-TestTreeHasMarker $transientInstallMove.Release 'new.marker'
    Assert-TestTreeHasMarker $transientInstallMove.Cleaned 'old.marker'
    Require-TestCondition (-not (Test-Path -LiteralPath $transientInstallMove.Stage) -and
            -not (Test-Path -LiteralPath $transientInstallMove.Backup) -and
            -not (Test-Path -LiteralPath $transientInstallMove.Failed)) `
        'Transient install retry leaked a live transaction path'

    $postExisting = New-TestScenario 'post-existing' $true
    Invoke-ExpectedFailure {
        Invoke-TestSwap $postExisting $true 'post-install-validation'
    } @('old release was restored', $postExisting.Failed)
    Assert-TestTreeHasMarker $postExisting.Release 'old.marker'
    Assert-TestTreeHasMarker $postExisting.Failed 'new.marker'
    Require-TestCondition (-not (Test-Path -LiteralPath $postExisting.Stage) -and
            -not (Test-Path -LiteralPath $postExisting.Backup)) `
        'Post-install rollback left stage or backup behind'

    $postFirst = New-TestScenario 'post-first' $false
    Invoke-ExpectedFailure {
        Invoke-TestSwap $postFirst $false 'post-install-validation'
    } @('no prior release', $postFirst.Stage)
    Require-TestCondition (-not (Test-Path -LiteralPath $postFirst.Release)) `
        'Failed first install remained at release root'
    Assert-TestTreeHasMarker $postFirst.Stage 'new.marker'

    $cleanupFault = New-TestScenario 'backup-cleanup' $true
    Invoke-ExpectedFailure {
        Invoke-TestSwap $cleanupFault $true 'backup-cleanup'
    } @('new release is fully validated', $cleanupFault.Backup)
    Assert-TestTreeHasMarker $cleanupFault.Release 'new.marker'
    Assert-TestTreeHasMarker $cleanupFault.Backup 'old.marker'
    Require-TestCondition (-not (Test-Path -LiteralPath $cleanupFault.Stage)) `
        'Validated installed release still had a staging tree'

    $success = New-TestScenario 'success' $true
    $result = Invoke-TestSwap $success $true
    Require-TestCondition ($result.ReplacedExistingRelease -and $result.BackupRemoved) `
        'Successful transaction returned the wrong result'
    Assert-TestTreeHasMarker $success.Release 'new.marker'
    Assert-TestTreeHasMarker $success.Cleaned 'old.marker'
    Require-TestCondition (-not (Test-Path -LiteralPath $success.Stage) -and
            -not (Test-Path -LiteralPath $success.Backup) -and
            -not (Test-Path -LiteralPath $success.Failed)) `
        'Successful transaction leaked a live transaction path'

    Write-Output ('RELEASE_TRANSACTION_DRY_OK scenarios=6 rollback=verified ' +
        'transient_retry=verified cleanup_failure=retained')
} finally {
    if (Test-Path -LiteralPath $runRoot) {
        $full = [System.IO.Path]::GetFullPath($runRoot)
        Require-TestCondition ($full.StartsWith(
                $prefix, [System.StringComparison]::OrdinalIgnoreCase)) `
            "Refusing cleanup outside verification: $full"
        Require-TestCondition ((Split-Path -Leaf $full) -cmatch
                '^transaction-dry-[0-9a-f]{32}$') `
            "Refusing cleanup of an unexpected directory: $full"
        Assert-TestTreeHasNoReparsePoint $full
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
