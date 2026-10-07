param([int]$Port=8765,[switch]$NoOpen)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$url="http://127.0.0.1:$Port/preview/review/index.html"
$ready=$false
try {
    $health=Invoke-RestMethod "http://127.0.0.1:$Port/review-health" -TimeoutSec 2
    if ($health.project -ne 'MCyukkuri' -or $health.root -ne $root) { throw 'Port is occupied by another service.' }
    $ready=$true
} catch {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is in use. Run this script with another -Port value." }
}
if (-not $ready) {
    $bundled=Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
    $pythonExe=if(Test-Path $bundled){$bundled}else{(Get-Command python.exe -ErrorAction Stop).Source}
    $logs=Join-Path $root '.local/review'
    New-Item -ItemType Directory -Force $logs | Out-Null
    & $pythonExe (Join-Path $PSScriptRoot 'build_review_catalog.py')
    if ($LASTEXITCODE -ne 0) { throw 'Could not index review assets.' }
    $server=Start-Process -FilePath $pythonExe -ArgumentList @('"'+(Join-Path $PSScriptRoot 'serve_review.py')+'"','--port',"$Port") -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'server.err.log')
    @{pid=$server.Id;started=$server.StartTime.ToUniversalTime().ToString('o');port=$Port} | ConvertTo-Json | Set-Content (Join-Path $logs 'server.json') -Encoding utf8
    for($attempt=0;$attempt -lt 30;$attempt++){
        Start-Sleep -Milliseconds 200
        try{$health=Invoke-RestMethod "http://127.0.0.1:$Port/review-health" -TimeoutSec 1;if($health.project -eq 'MCyukkuri'){$ready=$true;break}}catch{}
    }
    if(-not $ready){throw "Preview server did not start. See $logs"}
}
Write-Output $url
if(-not $NoOpen){Start-Process $url}
