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

# Apps made for music; their sessions win over a browser or video player playing at the same time.
$musicApps = 'Spotify|AppleMusic|AppleInc\.|iTunes|Amazon.*Music|Deezer|Tidal|Qobuz|ZuneMusic|YouTube.?Music|youtube-music|SoundCloud|foobar|MusicBee|AIMP|Winamp|Cider'

function Has-Length($s) {
    $timeline = $s.GetTimelineProperties()
    return ($timeline.EndTime - $timeline.StartTime).TotalMilliseconds -gt 0
}

function Rank($s) {
    # A playing music app first, then any other playing song; paused ones last. A livestream (a Twitch or YouTube live
    # stream in the browser) has no length and is never taken: it has no lyrics, and what is said in it is no song.
    if (-not (Has-Length $s)) { return -1 }
    if ($s.GetPlaybackInfo().PlaybackStatus -ne 'Playing') { return 0 }
    if ($s.SourceAppUserModelId -match $musicApps) { return 3 }
    return 2
}

function Pick-Session {
    # The session that ranks best; of equal ones the one Windows calls current. Only livestreams: none.
    $current = $manager.GetCurrentSession()
    $best = $null
    $bestRank = -1
    if ($current -ne $null) {
        $best = $current
        $bestRank = Rank $current
    }
    foreach ($s in $manager.GetSessions()) {
        $rank = Rank $s
        if ($rank -gt $bestRank) {
            $best = $s
            $bestRank = $rank
        }
    }
    if ($bestRank -lt 0) { return $null }
    return $best
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
            # Other players playing at the same time (a video beside the music, also in another tab of the same
            # browser); Hoergenau would hear them too. All playing sessions but the chosen one, once.
            $others = New-Object System.Collections.ArrayList
            foreach ($s in $manager.GetSessions()) {
                if ($s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { [void]$others.Add($s.SourceAppUserModelId) }
            }
            if ($playback.PlaybackStatus -eq 'Playing') { $others.Remove($session.SourceAppUserModelId) }
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
                othersPlaying = @($others)
            }
        }
    } catch {
        $line = @{ ok = $false; error = $_.Exception.Message; nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
    }

    [Console]::Out.WriteLine(($line | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds $IntervalMs
}
