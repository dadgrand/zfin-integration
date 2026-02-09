param(
    [string]$ServiceName = "ZfinBridge",
    [string]$NssmPath = "",
    [string]$AppDir = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AppDir)) {
    $AppDir = Split-Path -Parent $MyInvocation.MyCommand.Path
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
    Write-Host "Service '$ServiceName' removed."
} else {
    Write-Host "Service '$ServiceName' does not exist."
}
