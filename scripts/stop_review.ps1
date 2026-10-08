$root=Split-Path $PSScriptRoot -Parent
$record=Join-Path $root '.local/review/server.json'
if(Test-Path $record){
    $saved=Get-Content $record -Raw | ConvertFrom-Json
    $server=Get-Process -Id $saved.pid -ErrorAction SilentlyContinue
    if($server -and $server.StartTime.ToUniversalTime().ToString('o') -eq $saved.started){Stop-Process -Id $server.Id;Write-Host 'Review server stopped.'}
}
