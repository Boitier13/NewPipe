[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Msi, [Parameter(Mandatory = $true)][string]$Exe)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# jpackage's EXE wraps an MSI in the read-only "msi" RT_RCDATA resource.
# Do not execute the EXE, install a product, or modify the user's registry to inspect it.
Add-Type @'
using System;
using System.IO;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class BoitierMsiResource {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr FindResource(IntPtr module, string name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll")] static extern IntPtr LockResource(IntPtr data);
    [DllImport("kernel32.dll")] static extern bool FreeLibrary(IntPtr module);
    public static void Export(string exe, string destination) {
        IntPtr module = LoadLibraryEx(exe, IntPtr.Zero, 2);
        if (module == IntPtr.Zero) throw new Win32Exception();
        try {
            IntPtr resource = FindResource(module, "msi", new IntPtr(10));
            if (resource == IntPtr.Zero) throw new Win32Exception();
            uint size = SizeofResource(module, resource);
            if (size < 8 || size > 1024L * 1024 * 1024) throw new IOException("Invalid embedded MSI size");
            IntPtr pointer = LockResource(LoadResource(module, resource));
            if (pointer == IntPtr.Zero) throw new Win32Exception();
            byte[] bytes = new byte[(int)size];
            Marshal.Copy(pointer, bytes, 0, bytes.Length);
            if (BitConverter.ToString(bytes, 0, 8) != "D0-CF-11-E0-A1-B1-1A-E1") throw new IOException("Invalid MSI header");
            using (var output = new FileStream(destination, FileMode.CreateNew)) output.Write(bytes, 0, bytes.Length);
        } finally { FreeLibrary(module); }
    }
}
'@

$work = Join-Path $env:RUNNER_TEMP ('package-privacy-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$embedded = Join-Path $work 'embedded.msi'
[BoitierMsiResource]::Export((Resolve-Path $Exe).Path, $embedded)
$index = 0
foreach ($package in @((Resolve-Path $Msi).Path, $embedded)) {
    $index++
    $target = Join-Path $work "payload-$index"
    $log = Join-Path $work "extract-$index.log"
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($package, 0)
    $view = $database.OpenView('SELECT `Key`, `Name`, `Value` FROM `Registry`')
    $view.Execute()
    while ($record = $view.Fetch()) {
        $text = (1..3 | ForEach-Object { $record.StringData($_) }) -join ' '
        if ($text -match 'JavaSoft\\Prefs|NewPipeDesktop|PRIVATE_NEWPIPE') { throw 'Legacy/personal registry settings in MSI' }
    }
    $view.Close()
    # Administrative extraction only, not a first interactive install/launch.
    $process = Start-Process msiexec.exe -ArgumentList @('/a', "`"$package`"", '/qn', '/norestart', "TARGETDIR=`"$target`"", '/l*v', "`"$log`"") -PassThru
    if (-not $process.WaitForExit(180000)) { throw "MSI extraction timeout. See $log" }
    if ($process.ExitCode -notin @(0, 3010)) { throw "MSI extraction failed: $($process.ExitCode). See $log" }
    if (-not (Get-ChildItem -LiteralPath $target -Recurse -Filter BoitierTube.exe)) { throw 'No application in MSI payload' }
    # The administrative source MSI is metadata, not a profile; scan all other payload files recursively.
    python scripts/audit_distribution_privacy.py $target
    if ($LASTEXITCODE -ne 0) { throw "Personal-data structural audit failed for MSI payload $index" }
}
Write-Host '::notice::Standalone MSI and EXE embedded MSI payloads passed privacy structure checks'
