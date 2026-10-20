# Windows entry for offline log export. Reads runtime config only to locate Python.
# Writes reports under .local; never launches or compiles the game.
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$LogArguments)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$manifest = Join-Path $projectRoot '.local/experimental-map/museum-runtime.json'
$candidates = @()
if (Test-Path -LiteralPath $manifest) {
    $config = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    if ($config.python) { $candidates += $config.python }
}
$candidates += @('py', 'python')
foreach ($candidate in $candidates) {
    $command = Get-Command $candidate -ErrorAction SilentlyContinue
    if (-not $command) { continue }
    $version = & $command.Source --version 2>&1
    if ($LASTEXITCODE -ne 0 -or "$version" -notmatch 'Python 3') { continue }
    if (-not $LogArguments) { $LogArguments = @('--interactive') }
    & $command.Source (Join-Path $PSScriptRoot 'museum_logs.py') @LogArguments
    exit $LASTEXITCODE
}
throw 'No Python 3 found. Set python in .local/experimental-map/museum-runtime.json.'
