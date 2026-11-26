param([Parameter(Mandatory=$true)][int]$ApiPid)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$ApiPid"
$jar=Join-Path $projectRoot 'server\target\video-search-0.1.0.jar'
if (-not $process -or $process.CommandLine -notlike "*$jar*") { throw 'Process is not the verified project JAR; nothing stopped.' }
$state=Get-Content -LiteralPath (Join-Path $projectRoot '.data/browser-state.json') -Raw | ConvertFrom-Json
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
foreach ($cookie in $state.cookies) { $session.Cookies.Add([uri]'http://127.0.0.1:8090/',[System.Net.Cookie]::new($cookie.name,$cookie.value,'/','127.0.0.1')) }
$created=Join-Path $projectRoot '.data/crash-created.json'
if (Test-Path -LiteralPath $created) { throw 'A previous crash test receipt exists. Preserve it and choose a fresh test before rerunning.' }
$python='C:\ProgramData\miniconda3\python.exe'
$arguments=@(('"'+(Join-Path $PSScriptRoot 'exercise.py')+'"'),'--file',('"'+(Join-Path $projectRoot '.data/crash-sample.mp4')+'"'),'--library','crash-recovery-test','--cloud','--record-created',('"'+$created+'"'))
$helper=Start-Process -WindowStyle Hidden -FilePath $python -ArgumentList $arguments -PassThru -RedirectStandardOutput (Join-Path $projectRoot 'output/crash-helper.log') -RedirectStandardError (Join-Path $projectRoot 'output/crash-helper-error.log')
$until=[DateTime]::UtcNow.AddSeconds(40)
while ([DateTime]::UtcNow -lt $until) {
  if (Test-Path -LiteralPath $created) {
    $receipt=Get-Content -LiteralPath $created -Raw | ConvertFrom-Json
    $task=Invoke-RestMethod -Uri ('http://127.0.0.1:8090/api/v1/tasks/'+$receipt.taskId) -WebSession $session
    if (@($task.children | Where-Object {$_.stage -eq 'VISUAL' -and $_.status -eq 'RUNNING'}).Count -gt 0) {
      $current=Get-CimInstance Win32_Process -Filter "ProcessId=$ApiPid"
      if ($current.CommandLine -notlike "*$jar*") { throw 'Process identity changed; nothing stopped.' }
      $report=@{taskId=$receipt.taskId;helperPid=$helper.Id;apiPid=$ApiPid;before=$task;killedAt=[DateTime]::UtcNow.ToString('o');mode='local dispatcher / MySQL; real forced JVM termination'}
      $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $projectRoot 'output/crash-recovery.json') -Encoding utf8
      Stop-Process -Id $ApiPid -Force
      Write-Output ('Stopped verified JVM while VISUAL was RUNNING. Task: '+$receipt.taskId)
      return
    }
  }
  Start-Sleep -Milliseconds 100
}
throw 'Did not observe a running visual task; did not terminate anything. Helper task remains in the database.'
