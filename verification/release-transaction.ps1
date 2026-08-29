Set-StrictMode -Version 2.0

function Assert-ReleaseTransactionCondition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) { throw $Message }
}

function Test-ReleaseDirectoryMoveFailureIsTransient {
    param([Parameter(Mandatory = $true)]$Failure)

    $exception = $Failure.Exception
    while ($null -ne $exception) {
        if ($exception -is [System.IO.IOException] -or
                $exception -is [System.UnauthorizedAccessException]) {
            return $true
        }
        $exception = $exception.InnerException
    }
    return $false
}

function Move-ReleaseDirectoryWithRetry {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [ValidateRange(1, 20)][int]$MaxAttempts = 7,
        [ValidateRange(0, 19)][int]$InjectedTransientFailures = 0
    )

    Assert-ReleaseTransactionCondition (Test-Path -LiteralPath $Source -PathType Container) `
        "Release move source is not a directory: $Source"
    Assert-ReleaseTransactionCondition (-not (Test-Path -LiteralPath $Destination)) `
        "Release move destination already exists: $Destination"

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            if ($attempt -le $InjectedTransientFailures) {
                throw [System.UnauthorizedAccessException]::new(
                    'Injected transient release-directory move failure')
            }
            [System.IO.Directory]::Move($Source, $Destination)
            return
        } catch {
            $failure = $_
            $sourceExists = Test-Path -LiteralPath $Source
            $destinationExists = Test-Path -LiteralPath $Destination

            if ($sourceExists -and [System.IO.Path]::GetFileName($Source) -match
                    '^\.\d+\.\d+\.\d+\.staging-[0-9a-f]{32}$') {
                foreach ($sourceFile in @(Get-ChildItem -LiteralPath $Source -Force -Recurse -File)) {
                    $exclusiveStream = $null
                    try {
                        $exclusiveStream = [System.IO.File]::Open(
                            $sourceFile.FullName,
                            [System.IO.FileMode]::Open,
                            [System.IO.FileAccess]::Read,
                            [System.IO.FileShare]::None)
                    } catch {
                        Write-Warning ("LOCK_DIAGNOSTIC file={0} cause={1}" -f
                            $sourceFile.FullName, $_.Exception.Message)
                    } finally {
                        if ($null -ne $exclusiveStream) { $exclusiveStream.Dispose() }
                    }
                }
            }

            # Directory.Move is atomic on one volume. If the call reported an
            # error after the rename became visible, accept only the exact
            # source-gone/destination-directory state.
            if (-not $sourceExists -and $destinationExists -and
                    (Test-Path -LiteralPath $Destination -PathType Container)) {
                return
            }
            if (-not $sourceExists -or $destinationExists) {
                throw ("Release directory move failed with an ambiguous filesystem state. " +
                    "SourceExists={0}; DestinationExists={1}; Source={2}; Destination={3}; " +
                    "Cause: {4}" -f $sourceExists, $destinationExists, $Source,
                    $Destination, $failure.Exception.Message)
            }
            if ($attempt -ge $MaxAttempts -or
                    -not (Test-ReleaseDirectoryMoveFailureIsTransient $failure)) {
                throw $failure
            }

            # Newly written ZIP/JAR trees can be held briefly by Defender,
            # Search, or an IDE. Retry only while the pre-move state is still
            # intact, with a small bounded backoff (4.7 seconds maximum).
            $delayMilliseconds = [Math]::Min(
                100 * [Math]::Pow(2, $attempt - 1), 1600)
            Write-Verbose ("Retrying release directory move after transient failure " +
                "(attempt {0}/{1}, delay {2}ms): {3}" -f $attempt,
                $MaxAttempts, [int]$delayMilliseconds, $failure.Exception.Message)
            Start-Sleep -Milliseconds ([int]$delayMilliseconds)
        }
    }
}

function Invoke-ValidatedReleaseSwap {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ReleaseRoot,
        [Parameter(Mandatory = $true)][string]$StageRoot,
        [Parameter(Mandatory = $true)][string]$BackupRoot,
        [Parameter(Mandatory = $true)][string]$FailedInstallRoot,
        [Parameter(Mandatory = $true)][bool]$HadExistingRelease,
        [Parameter(Mandatory = $true)][scriptblock]$AssertExpectedTree,
        [Parameter(Mandatory = $true)][scriptblock]$AssertMovableTree,
        [Parameter(Mandatory = $true)][scriptblock]$AssertInstalledTree,
        [Parameter(Mandatory = $true)][scriptblock]$RemoveValidatedBackup,
        [ValidateSet('', 'install-move', 'install-move-once',
            'post-install-validation', 'backup-cleanup')]
        [string]$FaultPoint = ''
    )

    $release = [System.IO.Path]::GetFullPath($ReleaseRoot)
    $stage = [System.IO.Path]::GetFullPath($StageRoot)
    $backup = [System.IO.Path]::GetFullPath($BackupRoot)
    $failed = [System.IO.Path]::GetFullPath($FailedInstallRoot)
    $parent = [System.IO.Path]::GetDirectoryName($release)
    foreach ($path in @($stage, $backup, $failed)) {
        Assert-ReleaseTransactionCondition (
            [System.IO.Path]::GetDirectoryName($path).Equals(
                $parent, [System.StringComparison]::OrdinalIgnoreCase)) `
            "Release transaction paths must share one parent: $path"
    }
    $distinct = @(@($release, $stage, $backup, $failed) | Sort-Object -Unique)
    Assert-ReleaseTransactionCondition ($distinct.Count -eq 4) `
        'Release transaction paths must be distinct'
    Assert-ReleaseTransactionCondition (Test-Path -LiteralPath $stage -PathType Container) `
        "Validated staging directory is missing: $stage"
    Assert-ReleaseTransactionCondition (
        (Test-Path -LiteralPath $release) -eq $HadExistingRelease) `
        "Release existence changed before commit: $release"
    Assert-ReleaseTransactionCondition (-not (Test-Path -LiteralPath $backup)) `
        "Backup path already exists: $backup"
    Assert-ReleaseTransactionCondition (-not (Test-Path -LiteralPath $failed)) `
        "Failed-install recovery path already exists: $failed"

    & $AssertInstalledTree $stage 'Staged release at transaction boundary'
    if ($HadExistingRelease) {
        & $AssertExpectedTree $release 'Existing release at transaction boundary'
        Move-ReleaseDirectoryWithRetry $release $backup
    }

    try {
        if ($FaultPoint -ceq 'install-move') {
            throw 'Injected release transaction fault: install-move'
        }
        $injectedTransientFailures =
            if ($FaultPoint -ceq 'install-move-once') { 1 } else { 0 }
        Move-ReleaseDirectoryWithRetry $stage $release `
            -InjectedTransientFailures $injectedTransientFailures
    } catch {
        $installFailure = $_
        if ($HadExistingRelease) {
            try {
                Assert-ReleaseTransactionCondition (-not (Test-Path -LiteralPath $release)) `
                    "Failed install left a release-root entry; rollback is unsafe: $release"
                & $AssertExpectedTree $backup 'Backup used for install rollback'
                Move-ReleaseDirectoryWithRetry $backup $release
            } catch {
                throw ("Release install failed: {0}; rollback also failed: {1}. " +
                    "Preserved staging/backup paths for manual recovery: {2}, {3}" -f
                    $installFailure.Exception.Message, $_.Exception.Message, $stage, $backup)
            }
            throw ("Release install failed and the old release was restored. " +
                "Validated staging was retained at {0}. Cause: {1}" -f
                $stage, $installFailure.Exception.Message)
        }
        throw ("Release install failed with no prior release. Validated staging was retained " +
            "at {0}. Cause: {1}" -f $stage, $installFailure.Exception.Message)
    }

    $postInstallFailure = $null
    try {
        if ($FaultPoint -ceq 'post-install-validation') {
            throw 'Injected release transaction fault: post-install-validation'
        }
        & $AssertInstalledTree $release 'Installed release'
    } catch {
        $postInstallFailure = $_
    }

    if ($null -ne $postInstallFailure) {
        if ($HadExistingRelease) {
            try {
                & $AssertMovableTree $release 'Failed installed release'
                Assert-ReleaseTransactionCondition (-not (Test-Path -LiteralPath $failed)) `
                    "Failed-install recovery path appeared during rollback: $failed"
                Move-ReleaseDirectoryWithRetry $release $failed
                & $AssertExpectedTree $backup 'Validated backup for post-install rollback'
                Move-ReleaseDirectoryWithRetry $backup $release
            } catch {
                throw ("Post-install validation failed: {0}; rollback also failed: {1}. " +
                    "Inspect release/backup/failed-install paths: {2}, {3}, {4}" -f
                    $postInstallFailure.Exception.Message, $_.Exception.Message,
                    $release, $backup, $failed)
            }
            throw ("Post-install validation failed and the old release was restored. " +
                "The failed installed tree was retained at {0}. Cause: {1}" -f
                $failed, $postInstallFailure.Exception.Message)
        }

        try {
            & $AssertMovableTree $release 'Failed first installed release'
            Assert-ReleaseTransactionCondition (-not (Test-Path -LiteralPath $stage)) `
                "Original staging path reappeared during recovery: $stage"
            Move-ReleaseDirectoryWithRetry $release $stage
        } catch {
            throw ("Post-install validation failed with no prior release: {0}; moving the " +
                "failed tree back to staging also failed: {1}. Inspect {2} and {3}" -f
                $postInstallFailure.Exception.Message, $_.Exception.Message, $release, $stage)
        }
        throw ("Post-install validation failed with no prior release. The failed tree was " +
            "moved back to the unique staging recovery path {0}. Cause: {1}" -f
            $stage, $postInstallFailure.Exception.Message)
    }

    if ($HadExistingRelease) {
        try {
            & $AssertExpectedTree $backup 'Validated old-release backup before cleanup'
            if ($FaultPoint -ceq 'backup-cleanup') {
                throw 'Injected release transaction fault: backup-cleanup'
            }
            & $RemoveValidatedBackup $backup
        } catch {
            throw ("The new release is fully validated and retained at {0}, but cleanup of " +
                "the old-release backup failed. Backup recovery path: {1}. Cause: {2}" -f
                $release, $backup, $_.Exception.Message)
        }
    }

    [pscustomobject]@{
        ReleaseRoot = $release
        ReplacedExistingRelease = $HadExistingRelease
        BackupRemoved = $HadExistingRelease
    }
}
