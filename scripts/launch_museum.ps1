# Starts the Yukkuri museum, the single in-game test map. Never compiles the mod.
#
#   launcher.cmd            generate the world when it is missing or out of date, then start the game
#   launcher.cmd -Fresh     always generate a brand-new world first (discards the old museum world)
#   launcher.cmd -Resume    keep the installed world even if the generator changed
#   launcher.cmd -Menu      open the Minecraft menu instead of the world
#
# The world is built from scripts/build_museum_full.py into .local/experimental-map/museum (replaced as a whole, never
# repaired in place). The mod itself comes from the prepared CI runtime named in .local/experimental-map/museum-runtime.json
# ("runtime", "java"; optional "python" for the interpreter that has nbtlib). Update that runtime from a CI build that contains
# the museum commands whenever command/Museum*.java changes.
param([switch]$Menu, [switch]$Fresh, [switch]$Resume)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskRoot = Join-Path $projectRoot '.local/experimental-map'
$manifestFile = Join-Path $taskRoot 'museum-runtime.json'
if (-not (Test-Path -LiteralPath $manifestFile)) { throw 'Museum runtime is missing (.local/experimental-map/museum-runtime.json). See docs/EXPERIMENTAL_MAP.md.' }
$runtimeConfig = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
$runtimeRoot = Join-Path $projectRoot $runtimeConfig.runtime
$javaExe = Join-Path $projectRoot $runtimeConfig.java
$gameRoot = Join-Path $taskRoot 'museum'
$world = 'MCyukkuri-Museum'
$logRoot = Join-Path $taskRoot 'play-museum'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$processFile = Join-Path $logRoot 'process.json'
if (Test-Path -LiteralPath $processFile) {
    $previous = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    $existing = Get-Process -Id $previous.pid -ErrorAction SilentlyContinue
    if ($existing -and [string]$existing.StartTime.ToUniversalTime().Ticks -eq [string]$previous.startedTicks) {
        Write-Host "Museum is already running (PID $($existing.Id))."
        exit 0
    }
}
foreach ($name in @('clientRunClasspath.txt','clientRunVmArgs.txt','clientRunProgramArgs.txt','mod/META-INF/neoforge.mods.toml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeRoot $name))) { throw "Missing runtime file: $name" }
}

function Find-Python {
    $candidates = @()
    if ($runtimeConfig.PSObject.Properties.Name -contains 'python') { $candidates += $runtimeConfig.python }
    $candidates += @('py', 'python')
    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if (-not $command) { continue }
        $output = & $command.Source --version 2>&1
        if ($LASTEXITCODE -eq 0 -and "$output" -match 'Python 3') { return $command.Source }
    }
    throw 'No Python 3 found. Put "python": "<path to python.exe>" into .local/experimental-map/museum-runtime.json.'
}

$generator = Join-Path $projectRoot 'scripts/build_museum_full.py'
$worldFile = Join-Path $gameRoot ('saves/' + $world + '/level.dat')
$build = $Fresh
$python = $null
if (-not (Test-Path -LiteralPath $worldFile)) { $build = $true }
elseif (-not $Fresh -and -not $Resume) {
    $python = Find-Python
    & $python $generator --stale | Out-Null
    if ($LASTEXITCODE -ne 0) { $build = $true; Write-Host 'The museum generator changed: building a fresh world.' }
}
if ($build) {
    if (-not $python) { $python = Find-Python }
    Write-Host 'Generating the museum world (data pack and level metadata only; no compilation)...'
    & $python $generator --install
    if ($LASTEXITCODE -ne 0) { throw 'World generation failed.' }
}
if (-not (Test-Path -LiteralPath $worldFile)) { throw 'Museum save is missing.' }

$arguments = @(
    ('"@' + (Join-Path $runtimeRoot 'clientRunClasspath.txt') + '"'),
    ('"@' + (Join-Path $runtimeRoot 'clientRunVmArgs.txt') + '"'),
    '-Xmx6G',
    ('"-Dfml.modFolders=mcyukkuri%%' + (Join-Path $runtimeRoot 'mod') + '"'),
    'net.neoforged.devlaunch.Main',
    ('"@' + (Join-Path $runtimeRoot 'clientRunProgramArgs.txt') + '"'),
    '--username','LabPlayer','--width','1440','--height','900'
)
if (-not $Menu) { $arguments += @('--quickPlaySingleplayer',$world) }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$client = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $gameRoot -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $logRoot "$stamp.log") -RedirectStandardError (Join-Path $logRoot "$stamp.err.log")
@{pid=$client.Id;startedTicks=[string]$client.StartTime.ToUniversalTime().Ticks;stdout=(Join-Path $logRoot "$stamp.log");stderr=(Join-Path $logRoot "$stamp.err.log");world=$world} | ConvertTo-Json | Set-Content -LiteralPath $processFile -Encoding utf8
Write-Host "Starting the Yukkuri museum (PID $($client.Id)). First entry builds the halls (about 30 s); you are teleported to the lobby when done."
