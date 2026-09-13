# Start the installed laboratory world using the exact frozen runtime in its manifest.
# Writes client logs/options only to .local/experimental-map/play; never rebuilds or
# replaces worlds. The release world under run/saves is reached through --gameDir.
param([string]$GameDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$taskRoot = Join-Path $projectRoot '.local/experimental-map'
$manifestPath = Join-Path $taskRoot 'release/runtime.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Laboratory release is missing. See docs/EXPERIMENTAL_MAP.md.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$runtimeRoot = Join-Path $projectRoot $manifest.runtime
$gameRoot = Join-Path $projectRoot 'run'
if ($GameDirectory) {
    $gameRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $GameDirectory))
    $allowedRoot = [System.IO.Path]::GetFullPath($taskRoot) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $gameRoot.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Override must be within the experimental-map task directory.' }
}
$worldPath = Join-Path $gameRoot 'saves/MCyukkuri-Lab/level.dat'
if (-not (Test-Path -LiteralPath $worldPath)) { throw 'The installed laboratory world is missing.' }
$javaExe = $manifest.java
if (-not (Test-Path -LiteralPath $javaExe)) { throw 'The required local JDK 21 was not found.' }
$argsRoot = Join-Path $runtimeRoot 'moddev'
$mainClasses = Join-Path $runtimeRoot 'classes/java/main'
$mainResources = Join-Path $runtimeRoot 'resources/main'
foreach ($entry in @('clientRunClasspath.txt','clientRunVmArgs.txt','clientRunProgramArgs.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $argsRoot $entry))) { throw "Missing frozen client arguments: $entry" }
}
# Use a task-owned launch cwd; the actual game directory is passed explicitly.
$launchRoot = Join-Path $taskRoot 'play'
New-Item -ItemType Directory -Force -Path $launchRoot | Out-Null
$processFile = Join-Path $launchRoot 'process.json'
if (Test-Path -LiteralPath $processFile) {
    $previous = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    $existing = Get-Process -Id $previous.pid -ErrorAction SilentlyContinue
    if ($existing -and $existing.StartTime.ToUniversalTime().ToString('o') -eq $previous.started) {
        Write-Host 'The laboratory client is already running.'
        exit 0
    }
}
$javaArgs = @(
    ('"@' + (Join-Path $argsRoot 'clientRunClasspath.txt') + '"'),
    ('"@' + (Join-Path $argsRoot 'clientRunVmArgs.txt') + '"'),
    '-Xmx4G',
    ('"-Dfml.modFolders=mcyukkuri%%' + $mainClasses + ';mcyukkuri%%' + $mainResources + '"'),
    'net.neoforged.devlaunch.Main',
    ('"@' + (Join-Path $argsRoot 'clientRunProgramArgs.txt') + '"'),
    '--gameDir', ('"' + $gameRoot + '"'),
    '--username', 'LabPlayer', '--width', '1440', '--height', '900',
    '--quickPlaySingleplayer', 'MCyukkuri-Lab'
)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$programText = [System.IO.File]::ReadAllText((Join-Path $argsRoot 'clientRunProgramArgs.txt'))
$programText = [regex]::Replace($programText, '(?m)^--gameDir\r?\n[^\r\n]*(?:\r?\n|$)', '')
$programPath = Join-Path $launchRoot 'program-args.txt'
[System.IO.File]::WriteAllText($programPath, $programText, [System.Text.UTF8Encoding]::new($false))
# Replace the original program file so --gameDir occurs exactly once.
$javaArgs[5] = '"@' + $programPath + '"'
$client = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $launchRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $launchRoot "$stamp.log") -RedirectStandardError (Join-Path $launchRoot "$stamp.err.log")
@{pid=$client.Id;started=$client.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $processFile -Encoding utf8
Write-Host 'Starting MCyukkuri laboratory. The game window will open shortly.'
