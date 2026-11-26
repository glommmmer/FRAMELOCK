$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$linuxPath='/mnt/'+$projectRoot.Substring(0,1).ToLowerInvariant()+$projectRoot.Substring(2).Replace('\','/')
$jar=[IO.Path]::GetFullPath((Join-Path $projectRoot 'server/target/video-search-0.1.0.jar'))
foreach ($process in Get-CimInstance Win32_Process -Filter "Name='java.exe'") {
  if($process.CommandLine -and ($process.CommandLine.IndexOf($jar,[StringComparison]::OrdinalIgnoreCase) -ge 0)) { Stop-Process -Id $process.ProcessId; Write-Output ('Stopped project JVM '+$process.ProcessId) }
}
Write-Output 'Stopping only this project containers; keeping data volumes and other WSL programs.'
Invoke-ProjectWsl --cd $linuxPath -- docker compose --env-file .env -f deploy/compose.yml --profile infra --profile full stop
if ($LASTEXITCODE -ne 0) { throw 'Could not stop project containers.' }
$keepaliveFile=Join-Path $projectRoot '.data/wsl-keepalive.json'
if (Test-Path -LiteralPath $keepaliveFile) {
  $state=Get-Content -LiteralPath $keepaliveFile -Raw | ConvertFrom-Json
  $helper=Get-Process -Id $state.pid -ErrorAction SilentlyContinue
  $command=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.pid)" -ErrorAction SilentlyContinue
  if ($helper -and $helper.ProcessName -eq 'wsl' -and $helper.StartTime.ToUniversalTime().ToString('O') -eq $state.started -and $command.CommandLine -match 'sleep\s+(infinity|21600)' -and ( -not $state.distro -or $state.distro -eq '<default>' -or $command.CommandLine.Contains($state.distro))) {
    Stop-Process -Id $helper.Id
    Remove-Item -LiteralPath $keepaliveFile
    Write-Output 'Stopped this project WSL keepalive helper.'
  }
}
