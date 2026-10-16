param(
    [switch]$Menu,
    [switch]$PrepareOnly,
    [switch]$Fresh,
    [switch]$NormalTiming,
    [string]$ManifestPath = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$taskRoot = Join-Path $projectRoot '.local/living-showcase'
if (-not $ManifestPath) { $ManifestPath = Join-Path $taskRoot 'runtime.json' }
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "Missing pinned CI artifact manifest: $ManifestPath. See docs/LIVING_SHOWCASE.md. No build will be attempted."
}
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
function Resolve-ProjectPath([string]$value) {
    if (-not $value) { throw 'The runtime manifest contains an empty required path.' }
    $path = [IO.Path]::GetFullPath((Join-Path $projectRoot $value))
    if (-not $path.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Runtime path must stay in the repository: $path"
    }
    return $path
}
$runtimeSource = Resolve-ProjectPath $manifest.runtime
$javaExe = Resolve-ProjectPath $manifest.java
$templateLevel = Resolve-ProjectPath $manifest.templateLevel
$jarPath = Resolve-ProjectPath $manifest.artifact.path
if ($manifest.commit -notmatch '^[0-9a-f]{40}$' -or $manifest.artifact.sha256 -notmatch '^[0-9a-fA-F]{64}$') {
    throw 'Pin the full CI commit and artifact SHA-256 in runtime.json. A stale or unidentified artifact is not launched.'
}
$sourceRevision = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the current Git revision.' }
if ($sourceRevision -ne $manifest.commit) {
    throw "CI artifact is for $($manifest.commit), but the checkout is $sourceRevision. Update runtime.json after CI. No fallback build will run."
}
foreach ($path in @($javaExe, $templateLevel)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required existing runtime file is missing: $path" }
}
foreach ($name in @('clientRunClasspath.txt','clientRunVmArgs.txt','clientRunProgramArgs.txt','clientLegacyClasspath.txt','clientLog4j2.xml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeSource $name) -PathType Leaf)) { throw "Existing NeoForge runtime is incomplete: $name" }
}
New-Item -ItemType Directory -Force -Path $taskRoot | Out-Null
$processFile = Join-Path $taskRoot 'process.json'
if (Test-Path -LiteralPath $processFile) {
    $previous = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
    $existing = Get-Process -Id $previous.pid -ErrorAction SilentlyContinue
    if ($existing -and [string]$existing.StartTime.ToUniversalTime().Ticks -eq [string]$previous.startedTicks) {
        throw "The living showcase is already running (PID $($existing.Id)). Close it before preparing another session."
    }
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $jarPath -Parent) | Out-Null
    if ($manifest.artifact.url) {
        if ([uri]$manifest.artifact.url -notmatch '^https://') { throw 'Artifact downloads require HTTPS.' }
        Invoke-WebRequest -Uri $manifest.artifact.url -OutFile ($jarPath + '.download')
        if ((Get-FileHash -LiteralPath ($jarPath + '.download') -Algorithm SHA256).Hash -ne $manifest.artifact.sha256) {
            throw "Downloaded artifact hash does not match the pinned CI SHA-256: $jarPath.download"
        }
        Move-Item -LiteralPath ($jarPath + '.download') -Destination $jarPath
    } elseif ($manifest.artifact.runId -and $manifest.artifact.name) {
        $downloadRoot = Join-Path $taskRoot ('downloads/' + $manifest.artifact.runId)
        New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
        & gh run download $manifest.artifact.runId --repo Longshou/MCyukkuri --name $manifest.artifact.name --dir $downloadRoot
        if ($LASTEXITCODE -ne 0) { throw 'CI artifact download failed. No local compilation will be attempted.' }
        $candidates = @(Get-ChildItem -LiteralPath $downloadRoot -Filter '*.jar' -File -Recurse | Where-Object {
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -eq $manifest.artifact.sha256
        })
        if ($candidates.Count -ne 1) { throw 'CI download did not contain exactly one Jar with the pinned hash.' }
        Copy-Item -LiteralPath $candidates[0].FullName -Destination $jarPath
    } else {
        throw "Pinned Jar is missing: $jarPath. Set artifact.url or artifact.runId/name to enable downloading."
    }
}
if ((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash -ne $manifest.artifact.sha256) {
    throw "Jar hash mismatch: $jarPath"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    foreach ($entry in @('META-INF/neoforge.mods.toml', 'io/github/longshou/mcyukkuri/command/LivingShowcaseCommands.class')) {
        if (-not $archive.GetEntry($entry)) { throw "CI Jar is not the living-showcase version; missing $entry" }
    }
} finally { $archive.Dispose() }
$runtimeRoot = Join-Path $taskRoot ('runtimes/' + $manifest.commit + '-' + $manifest.artifact.sha256.Substring(0,12).ToLowerInvariant())
$modRoot = Join-Path $runtimeRoot 'mod'
if (-not (Test-Path -LiteralPath $modRoot -PathType Container)) {
    New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($jarPath, $modRoot)
}
# Only reuse the installed engine/dependency jars. The old mod directory is replaced everywhere.
foreach ($name in @('clientRunClasspath.txt','clientRunVmArgs.txt','clientRunProgramArgs.txt','clientLegacyClasspath.txt','clientLog4j2.xml')) {
    $content = Get-Content -LiteralPath (Join-Path $runtimeSource $name) -Raw
    $content = $content.Replace($runtimeSource.Replace('\','\\'), $runtimeRoot.Replace('\','\\'))
    $content = $content.Replace($runtimeSource, $runtimeRoot)
    $content = $content -replace '(?m)^-Dneoforge.enableGameTest=true\r?\n?', ''
    [IO.File]::WriteAllText((Join-Path $runtimeRoot $name), $content, [Text.UTF8Encoding]::new($false))
}
$pythonExe = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
if (-not (Test-Path -LiteralPath $pythonExe -PathType Leaf)) { throw "Bundled Python is missing: $pythonExe" }
$activeFile = Join-Path $taskRoot 'active-game.json'
$gameRoot = $null
if (-not $Fresh -and (Test-Path -LiteralPath $activeFile)) {
    $active = Get-Content -LiteralPath $activeFile -Raw | ConvertFrom-Json
    if ($active.commit -eq $manifest.commit -and [bool]$active.normalTiming -eq [bool]$NormalTiming) { $gameRoot = Resolve-ProjectPath $active.game }
}
if (-not $gameRoot) {
    $gameRoot = Join-Path $taskRoot ('games/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    $generatorArgs = @((Join-Path $PSScriptRoot 'build_living_showcase.py'), '--template-level', $templateLevel, '--output', $gameRoot)
    if ($NormalTiming) { $generatorArgs += '--normal-timing' }
    & $pythonExe @generatorArgs
    if ($LASTEXITCODE -ne 0) { throw 'Map generation failed. The game was not launched.' }
    @{commit=$manifest.commit; game=$gameRoot.Substring($projectRoot.Length + 1); normalTiming=[bool]$NormalTiming} |
        ConvertTo-Json | Set-Content -LiteralPath $activeFile -Encoding utf8
}
$world = 'MCyukkuri-Living-Showcase'
if (-not (Test-Path -LiteralPath (Join-Path $gameRoot "saves/$world/level.dat") -PathType Leaf)) { throw 'Living showcase save is missing.' }
if ($PrepareOnly) {
    Write-Host "Prepared existing CI Jar and disposable save: $gameRoot. No Java or game process was started."
    return
}
$logRoot = Join-Path $taskRoot 'logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$arguments = @(
    ('"@' + (Join-Path $runtimeRoot 'clientRunClasspath.txt') + '"'),
    ('"@' + (Join-Path $runtimeRoot 'clientRunVmArgs.txt') + '"'),
    '-Xmx6G', ('"-Dfml.modFolders=mcyukkuri%%' + $modRoot + '"'),
    'net.neoforged.devlaunch.Main', ('"@' + (Join-Path $runtimeRoot 'clientRunProgramArgs.txt') + '"'),
    '--username','LivingVisitor','--width','1440','--height','900'
)
if (-not $Menu) { $arguments += @('--quickPlaySingleplayer',$world) }
$client = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $gameRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "$stamp.log") -RedirectStandardError (Join-Path $logRoot "$stamp.err.log")
@{pid=$client.Id; startedTicks=[string]$client.StartTime.ToUniversalTime().Ticks; game=$gameRoot; commit=$manifest.commit;
    stdout=(Join-Path $logRoot "$stamp.log"); stderr=(Join-Path $logRoot "$stamp.err.log")} | ConvertTo-Json | Set-Content -LiteralPath $processFile -Encoding utf8
Write-Host "Living showcase started from pinned CI Jar $($manifest.commit) (PID $($client.Id))."
