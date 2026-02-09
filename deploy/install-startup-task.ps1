param(
    [string]$TaskName = "ZfinBridge",
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

$Arguments = "-jar `"$JarPath`" --config `"$ConfigPath`""
$Action = New-ScheduledTaskAction -Execute $JavaExe -Argument $Arguments -WorkingDirectory $AppDir
$Trigger = New-ScheduledTaskTrigger -AtStartup
$Settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1)

if ([string]::IsNullOrWhiteSpace($RunAsUser)) {
    $Principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $Action `
        -Trigger $Trigger `
        -Principal $Principal `
        -Settings $Settings `
        -Description "ZFIN bridge (Bank <-> ZFIN file relay)" `
        -Force | Out-Null
} else {
    if ([string]::IsNullOrWhiteSpace($RunAsPassword)) {
        throw "RunAsPassword is required when RunAsUser is provided."
    }

    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $Action `
        -Trigger $Trigger `
        -Settings $Settings `
        -Description "ZFIN bridge (Bank <-> ZFIN file relay)" `
        -User $RunAsUser `
        -Password $RunAsPassword `
        -RunLevel Highest `
        -Force | Out-Null
}

Start-ScheduledTask -TaskName $TaskName
Write-Host "Scheduled task '$TaskName' installed and started."
