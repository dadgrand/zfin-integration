param(
    [string]$ServiceName = "ZfinBridge",
    [string]$NssmPath = "",
    [string]$JavaExe = "",
    [string]$AppDir = "",
    [string]$RunAsUser = "",
    [string]$RunAsPassword = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AppDir)) {
    $AppDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}

$JarPath = Join-Path $AppDir "zfin-bridge.jar"
$ConfigPath = Join-Path $AppDir "config.ini"

if (-not (Test-Path $JarPath)) {
    throw "Jar not found: $JarPath"
}
if (-not (Test-Path $ConfigPath)) {
    throw "Config not found: $ConfigPath"
}

if ([string]::IsNullOrWhiteSpace($JavaExe)) {
    if ($env:JAVA_HOME) {
        $JavaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    } else {
        $JavaExe = "java"
    }
}

if ([string]::IsNullOrWhiteSpace($NssmPath)) {
    $candidate = Join-Path $AppDir "nssm.exe"
    if (Test-Path $candidate) {
        $NssmPath = $candidate
    } else {
        throw "NSSM not found. Put nssm.exe next to this script or pass -NssmPath."
    }
}

if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    & $NssmPath stop $ServiceName | Out-Null
    & $NssmPath remove $ServiceName confirm | Out-Null
}

& $NssmPath install $ServiceName $JavaExe "-jar `"$JarPath`" --config `"$ConfigPath`""
& $NssmPath set $ServiceName AppDirectory $AppDir
& $NssmPath set $ServiceName Start SERVICE_AUTO_START
& $NssmPath set $ServiceName AppStopMethodSkip 6
& $NssmPath set $ServiceName AppExit Default Restart
& $NssmPath set $ServiceName AppThrottle 5000

if (-not [string]::IsNullOrWhiteSpace($RunAsUser)) {
    if ([string]::IsNullOrWhiteSpace($RunAsPassword)) {
        throw "RunAsPassword is required when RunAsUser is provided."
    }
    & $NssmPath set $ServiceName ObjectName $RunAsUser $RunAsPassword
}

sc.exe failure $ServiceName reset= 0 actions= restart/5000/restart/5000/restart/5000 | Out-Null
sc.exe failureflag $ServiceName 1 | Out-Null

& $NssmPath start $ServiceName
Write-Host "Service '$ServiceName' installed and started via NSSM."
