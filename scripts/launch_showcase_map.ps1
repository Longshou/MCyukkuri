# Starts the showcase museum (or, with -Manifest, another prepared map) on an already prepared CI runtime. Never builds.
# Runtime manifest: .local/experimental-map/museum-runtime.json (the full museum); falls back to showcase-runtime.json.
# The game window is visible; -Hidden exists only for scripted checks.
param([switch]$Menu, [switch]$Hidden, [string]$Manifest = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskRoot = Join-Path $projectRoot '.local/experimental-map'
if ($Manifest) { $manifestFile = if ([System.IO.Path]::IsPathRooted($Manifest)) { $Manifest } else { Join-Path $taskRoot $Manifest } }
elseif (Test-Path -LiteralPath (Join-Path $taskRoot 'museum-runtime.json')) { $manifestFile = Join-Path $taskRoot 'museum-runtime.json' }
else { $manifestFile = Join-Path $taskRoot 'showcase-runtime.json' }
if (-not (Test-Path -LiteralPath $manifestFile)) { throw 'Showcase runtime is missing. See docs/EXPERIMENTAL_MAP.md.' }
$manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
$runtimeRoot = Join-Path $projectRoot $manifest.runtime
$gameRoot = Join-Path $projectRoot $manifest.game
$javaExe = Join-Path $projectRoot $manifest.java
$logRoot = Join-Path $taskRoot 'play-showcase'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$processFile = Join-Path $logRoot 'process.json'
if (Test-Path -LiteralPath $processFile) {
    $previous = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    $existing = Get-Process -Id $previous.pid -ErrorAction SilentlyContinue
    if ($existing -and [string]$existing.StartTime.ToUniversalTime().Ticks -eq [string]$previous.startedTicks) {
        Write-Host "Showcase is already running (PID $($existing.Id))."
        exit 0
    }
}
foreach ($name in @('clientRunClasspath.txt','clientRunVmArgs.txt','clientRunProgramArgs.txt','mod/META-INF/neoforge.mods.toml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeRoot $name))) { throw "Missing runtime file: $name" }
}
if (-not (Test-Path -LiteralPath (Join-Path $gameRoot ('saves/' + $manifest.world + '/level.dat')))) { throw 'Showcase save is missing.' }
$arguments = @(
    ('"@' + (Join-Path $runtimeRoot 'clientRunClasspath.txt') + '"'),
    ('"@' + (Join-Path $runtimeRoot 'clientRunVmArgs.txt') + '"'),
    '-Xmx6G',
    ('"-Dfml.modFolders=mcyukkuri%%' + (Join-Path $runtimeRoot 'mod') + '"'),
    'net.neoforged.devlaunch.Main',
    ('"@' + (Join-Path $runtimeRoot 'clientRunProgramArgs.txt') + '"'),
    '--username','LabPlayer','--width','1440','--height','900'
)
if (-not $Menu) { $arguments += @('--quickPlaySingleplayer',$manifest.world) }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$style = if ($Hidden) { 'Hidden' } else { 'Normal' }
$client = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $gameRoot -WindowStyle $style -PassThru -RedirectStandardOutput (Join-Path $logRoot "$stamp.log") -RedirectStandardError (Join-Path $logRoot "$stamp.err.log")
@{pid=$client.Id;startedTicks=[string]$client.StartTime.ToUniversalTime().Ticks;stdout=(Join-Path $logRoot "$stamp.log");stderr=(Join-Path $logRoot "$stamp.err.log");world=$manifest.world} | ConvertTo-Json | Set-Content -LiteralPath $processFile -Encoding utf8
Write-Host "Starting the Yukkuri showcase museum (PID $($client.Id)). First entry builds the halls (about 30 s)."
