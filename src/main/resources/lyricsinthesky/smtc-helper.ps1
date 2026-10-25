# Reads the Windows media session (SMTC) and prints one JSON line per poll.
# Started by the mod with Windows PowerShell 5.1, which can load WinRT types; pwsh 7 cannot.
# Argument 1: PID of the Minecraft process. The helper exits once that process is gone.
param([int]$ParentPid = 0, [int]$IntervalMs = 250)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Await($op, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($op))
    if (-not $task.Wait(2000)) { return $null }
    return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$manager = Await ($managerType::RequestAsync()) $managerType

function Pick-Session {
    # Prefer the session Windows calls current; fall back to any session that is playing.
    $current = $manager.GetCurrentSession()
    if ($current -ne $null -and $current.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { return $current }
    foreach ($s in $manager.GetSessions()) {
        if ($s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { return $s }
    }
    return $current
}

$lastParentCheck = [DateTime]::UtcNow
while ($true) {
    if ($ParentPid -gt 0 -and ([DateTime]::UtcNow - $lastParentCheck).TotalSeconds -ge 3) {
        $lastParentCheck = [DateTime]::UtcNow
        if (-not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit 0 }
    }

    $line = $null
    try {
        $session = Pick-Session
        if ($session -eq $null) {
            $line = @{ ok = $false; nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
        } else {
            $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
            $timeline = $session.GetTimelineProperties()
            $playback = $session.GetPlaybackInfo()
            $updated = $timeline.LastUpdatedTime.ToUnixTimeMilliseconds()
            $line = @{
                ok          = $true
                app         = $session.SourceAppUserModelId
                title       = if ($props) { $props.Title } else { '' }
                artist      = if ($props) { $props.Artist } else { '' }
                album       = if ($props) { $props.AlbumTitle } else { '' }
                status      = [string]$playback.PlaybackStatus
                positionMs  = [long]$timeline.Position.TotalMilliseconds
                durationMs  = [long]($timeline.EndTime - $timeline.StartTime).TotalMilliseconds
                updatedAtMs = $updated
                nowMs       = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            }
        }
    } catch {
        $line = @{ ok = $false; error = $_.Exception.Message; nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
    }

    [Console]::Out.WriteLine(($line | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds $IntervalMs
}
