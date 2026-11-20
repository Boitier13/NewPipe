# SPDX-License-Identifier: GPL-3.0-or-later
# Run from any working directory. No installer is built unless -Package is supplied.
[CmdletBinding()]
param(
    [switch]$PreflightOnly,
    [switch]$Package
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PreflightOnly -and $Package) { throw 'Use either -PreflightOnly or -Package, not both.' }
if ($env:OS -ne 'Windows_NT') { throw 'This verification script requires Windows.' }
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a full JDK 21 installation, then reopen PowerShell.' }
foreach ($tool in @('java.exe', 'javac.exe', 'jpackage.exe')) {
    if (-not (Test-Path (Join-Path $env:JAVA_HOME "bin/$tool"))) {
        throw "Missing $tool in JAVA_HOME. Install a full JDK 21, not a JRE."
    }
}
$javaVersion = & (Join-Path $env:JAVA_HOME 'bin/java.exe') --version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(openjdk|java) 21[.+\s-]') {
    throw "JAVA_HOME must point to JDK 21. Detected: $javaVersion"
}
foreach ($file in @(
    'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
    'desktopApp/src/main/resources/boitiertube.ico',
    'desktopApp/src/main/resources/boitiertube.png',
    'shared/src/commonMain/composeResources/font/source_sans_3_regular.ttf',
    'shared/src/commonMain/composeResources/font/source_sans_3_semibold.ttf',
    'shared/src/commonMain/composeResources/files/LICENSES/OFL-1.1.txt'
)) {
    if (-not (Test-Path (Join-Path $root $file))) { throw "Missing resource: $file" }
}
if ($Package) {
    foreach ($tool in @('candle.exe', 'light.exe')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Missing $tool. Install WiX Toolset 3.x for JDK 21 packaging and add its bin directory to PATH."
        }
    }
}
Write-Host 'BoitierTube preflight passed. Dependency downloads require network access.'
if ($PreflightOnly) { return }
Push-Location $root
try {
    & .\gradlew.bat :shared:jvmTest :desktopApp:build :desktopApp:createDistributable --console=plain --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "Desktop build/tests failed (exit $LASTEXITCODE)." }
    if ($Package) {
        & .\gradlew.bat :desktopApp:packageMsi :desktopApp:packageExe --console=plain --stacktrace
        if ($LASTEXITCODE -ne 0) { throw "Windows packaging failed (exit $LASTEXITCODE)." }
    }
} finally {
    Pop-Location
}
