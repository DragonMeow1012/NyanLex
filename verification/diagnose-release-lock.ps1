[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$type = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class RestartManagerLockProbe {
    private const int ErrorMoreData = 234;
    private const int MaxAppName = 255;
    private const int MaxServiceName = 63;

    [StructLayout(LayoutKind.Sequential)]
    private struct UniqueProcess {
        public int ProcessId;
        public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime;
    }

    private enum AppType {
        Unknown = 0,
        MainWindow = 1,
        OtherWindow = 2,
        Service = 3,
        Explorer = 4,
        Console = 5,
        Critical = 1000
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ProcessInfo {
        public UniqueProcess Process;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = MaxAppName + 1)]
        public string AppName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = MaxServiceName + 1)]
        public string ServiceShortName;
        public AppType ApplicationType;
        public uint AppStatus;
        public uint SessionId;
        [MarshalAs(UnmanagedType.Bool)]
        public bool Restartable;
    }

    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    private static extern int RmStartSession(
        out uint handle, int flags, string sessionKey);

    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    private static extern int RmRegisterResources(
        uint handle,
        uint fileCount,
        string[] fileNames,
        uint appCount,
        IntPtr apps,
        uint serviceCount,
        string[] services);

    [DllImport("rstrtmgr.dll")]
    private static extern int RmGetList(
        uint handle,
        out uint needed,
        ref uint count,
        [In, Out] ProcessInfo[] affectedApps,
        ref uint rebootReasons);

    [DllImport("rstrtmgr.dll")]
    private static extern int RmEndSession(uint handle);

    public static string[] GetOwners(string[] paths) {
        uint handle;
        int result = RmStartSession(
            out handle, 0, Guid.NewGuid().ToString("N"));
        if (result != 0) {
            throw new InvalidOperationException("RmStartSession=" + result);
        }
        try {
            result = RmRegisterResources(
                handle, (uint)paths.Length, paths, 0, IntPtr.Zero, 0, null);
            if (result != 0) {
                throw new InvalidOperationException(
                    "RmRegisterResources=" + result);
            }
            uint needed = 0;
            uint count = 0;
            uint reasons = 0;
            result = RmGetList(
                handle, out needed, ref count, null, ref reasons);
            if (result == 0) {
                return new string[0];
            }
            if (result != ErrorMoreData) {
                throw new InvalidOperationException(
                    "RmGetList(size)=" + result);
            }
            ProcessInfo[] infos = new ProcessInfo[needed];
            count = needed;
            result = RmGetList(
                handle, out needed, ref count, infos, ref reasons);
            if (result != 0) {
                throw new InvalidOperationException(
                    "RmGetList(data)=" + result);
            }
            List<string> owners = new List<string>();
            for (int index = 0; index < count; index++) {
                owners.Add(
                    infos[index].Process.ProcessId + "|" +
                    infos[index].AppName + "|" +
                    infos[index].ApplicationType);
            }
            return owners.ToArray();
        } finally {
            RmEndSession(handle);
        }
    }
}
'@

Add-Type -TypeDefinition $type
$repoRoot = [System.IO.Path]::GetFullPath(
    (Split-Path -Parent $PSScriptRoot))
$modsJarRoot = Join-Path $repoRoot 'mods-jar'
$packageScript = Join-Path $PSScriptRoot 'package-release.ps1'
$stagePattern = '^\.1\.0\.3\.staging-[0-9a-f]{32}$'
$before = @((Get-ChildItem -LiteralPath $modsJarRoot -Force -Directory |
    Where-Object { $_.Name -match $stagePattern }).FullName)
$id = [guid]::NewGuid().ToString('N')
$stdout = Join-Path ([System.IO.Path]::GetTempPath()) `
    "mctranslator-package-$id.out"
$stderr = Join-Path ([System.IO.Path]::GetTempPath()) `
    "mctranslator-package-$id.err"
$process = Start-Process `
    -FilePath 'powershell.exe' `
    -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        ('"' + $packageScript + '"')) `
    -PassThru `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr
$seen = @{}

try {
    while (-not $process.HasExited) {
        $stage = @(Get-ChildItem -LiteralPath $modsJarRoot -Force -Directory |
            Where-Object {
                $_.Name -match $stagePattern -and
                $before -notcontains $_.FullName
            } |
            Sort-Object CreationTimeUtc -Descending |
            Select-Object -First 1)
        if ($stage.Count -eq 1) {
            $paths = @((Get-ChildItem `
                    -LiteralPath $stage[0].FullName `
                    -Recurse `
                    -File `
                    -ErrorAction SilentlyContinue |
                Where-Object { $_.Extension -in @('.jar', '.zip') }).FullName)
            if ($paths.Count -gt 0) {
                try {
                    $owners = @(
                        [RestartManagerLockProbe]::GetOwners($paths))
                    foreach ($owner in $owners) {
                        if (-not $seen.ContainsKey($owner)) {
                            $seen[$owner] = $true
                            Write-Output (
                                "LOCK_OWNER $owner files=$($paths.Count)")
                        }
                    }
                } catch {
                    Write-Output "RM_PROBE_ERROR $($_.Exception.Message)"
                }
            }
        }
        Start-Sleep -Milliseconds 100
        $process.Refresh()
    }
    $process.WaitForExit()
    Write-Output (
        "PACKAGE_CHILD_EXIT=$($process.ExitCode) owners=$($seen.Count)")
    if (Test-Path -LiteralPath $stderr -PathType Leaf) {
        Get-Content -LiteralPath $stderr -Tail 4
    }
} finally {
    foreach ($path in @($stdout, $stderr)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}
