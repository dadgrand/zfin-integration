$ErrorActionPreference = "Stop"

$AppDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $AppDir

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-JavaExe {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    return "java"
}

function Resolve-MappedDriveToUnc([string]$PathValue) {
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $PathValue
    }

    if ($PathValue -notmatch '^(?<drive>[A-Za-z]):\\(?<rest>.*)$') {
        return $PathValue
    }

    $driveName = $Matches['drive']
    $rest = $Matches['rest']

    $drive = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue
    if ($null -eq $drive) {
        return $PathValue
    }

    if ([string]::IsNullOrWhiteSpace($drive.DisplayRoot)) {
        return $PathValue
    }

    $uncRoot = $drive.DisplayRoot.TrimEnd('\\')
    if ([string]::IsNullOrWhiteSpace($rest)) {
        return $uncRoot
    }

    return "$uncRoot\\$rest"
}

function Update-ConfigZfinRootToUnc([string]$ConfigPath) {
    $lines = Get-Content -Path $ConfigPath -Encoding UTF8
    $updated = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*zfin_root\s*=\s*(?<value>.+?)\s*$') {
            $currentValue = $Matches['value']
            $newValue = Resolve-MappedDriveToUnc $currentValue
            if ($newValue -ne $currentValue) {
                $lines[$i] = "zfin_root=$newValue"
                $updated = $true
                Write-Host "zfin_root converted to UNC: $newValue"
            }
            break
        }
    }

    if ($updated) {
        Set-Content -Path $ConfigPath -Value $lines -Encoding UTF8
    }
}

if (-not (Test-IsAdmin)) {
    throw "Run this script as Administrator."
}

$JarPath = Join-Path $AppDir "zfin-bridge.jar"
$ConfigPath = Join-Path $AppDir "config.ini"

if (-not (Test-Path $JarPath)) {
    throw "Jar not found: $JarPath"
}
if (-not (Test-Path $ConfigPath)) {
    throw "Config not found: $ConfigPath"
}

$JavaExe = Get-JavaExe

Update-ConfigZfinRootToUnc $ConfigPath

# Pre-create local runtime folders for lock/log files.
New-Item -ItemType Directory -Force -Path (Join-Path $AppDir "runtime") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $AppDir "logs") | Out-Null

Write-Host "Running one-time self-test..."
& $JavaExe -jar $JarPath --config $ConfigPath --once
if ($LASTEXITCODE -ne 0) {
    throw "Self-test failed. Check logs in '$AppDir\logs'."
}

Write-Host "Installing startup task..."
& (Join-Path $AppDir "install-startup-task.ps1") -TaskName "ZfinBridge"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to install startup task."
}

Write-Host "Ready: ZfinBridge is installed and will start automatically on boot."
Write-Host "Logs: $AppDir\logs"
