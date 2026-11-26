param([switch]$NoBrowser, [switch]$Rebuild, [switch]$Infra)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$jar = Join-Path $projectRoot 'server/target/video-search-0.1.0.jar'
$url = 'http://127.0.0.1:8090'
$runtimeMode=if($Infra){'mq'}else{'local'}
$output = Join-Path $projectRoot 'output'
[IO.Directory]::CreateDirectory($output) | Out-Null
function Test-Ready {
  try {
    $health = Invoke-RestMethod -Uri "$url/health" -TimeoutSec 5
    return ($health.application -eq 'FRAMELOCK' -and $health.status -eq 'UP' -and $health.database -eq 'UP' -and (-not $Infra -or $health.runtimeMode -eq $runtimeMode))
  } catch { return $false }
}
function Project-Jvms {
  return @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -and ($_.CommandLine.IndexOf($jar, [StringComparison]::OrdinalIgnoreCase) -ge 0) })
}
$key = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($projectRoot)).Replace('/','_').Replace('+','-')
$startupLock = [Threading.Mutex]::new($false, "Local\FRAMELOCK-$key")
$locked = $false
try {
  try { $locked = $startupLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
  if (-not $locked) { throw 'FRAMELOCK is already starting. Wait for the first startup window.' }
  if ((Test-Ready) -and -not $Rebuild) {
    & (Join-Path $PSScriptRoot 'keep-wsl.ps1')
    Write-Output "FRAMELOCK is already ready: $url"
    if (-not $NoBrowser) { Start-Process $url }
    return
  }
  $existing = @(Project-Jvms)
  if ($existing.Count) { try {$health=Invoke-RestMethod -Uri "$url/health" -TimeoutSec 5;if($health.runtimeMode -ne $runtimeMode){throw 'Stop FRAMELOCK before changing runtime mode.'}} catch {throw $_} }
  if ($Rebuild -and $existing.Count) { throw 'Stop FRAMELOCK before rebuilding. Use stop.ps1; it preserves your data.' }
  if ($Rebuild -or -not (Test-Path -LiteralPath $jar)) { & (Join-Path $PSScriptRoot 'build.ps1'); if ($LASTEXITCODE -ne 0) { throw 'Build failed.' } }
  Write-Output '[1/3] Starting the database and waiting for readiness...'
  if($Infra){& (Join-Path $PSScriptRoot 'start-infra.ps1')}else{& (Join-Path $PSScriptRoot 'start-infra.ps1') -MySqlOnly}
  if ($LASTEXITCODE -ne 0) { throw 'Database startup failed.' }
  # An existing JVM may simply be reconnecting after WSL was stopped. Never start a duplicate.
  $existing = @(Project-Jvms)
  if (-not $existing.Count) {
    $listener = Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw 'Port 8090 belongs to another process. It was not stopped.' }
    Write-Output '[2/3] Starting FRAMELOCK in the background...'
    $runner = Join-Path $PSScriptRoot 'start-api.ps1'
    $runSwitches=if($Infra){@('-Infra','-Worker')}else{@('-Local')}
    $child = Start-Process -FilePath 'powershell.exe' -ArgumentList (@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"'),'-SkipBuild')+$runSwitches) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'api-background.log') -RedirectStandardError (Join-Path $output 'api-background-error.log')
    @{pid=$child.Id;started=$child.StartTime.ToUniversalTime().ToString('O');mode=$runtimeMode;url=$url} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot '.data/launcher.json') -Encoding UTF8
  } else { Write-Output '[2/3] Reusing the existing project API...' }
  $deadline = [DateTime]::UtcNow.AddSeconds(120)
  while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-Ready) {
      Write-Output "[3/3] FRAMELOCK is ready: $url"
      Write-Output 'You may close this startup window. Use the stop command to stop the app.'
      if (-not $NoBrowser) { Start-Process $url }
      return
    }
    if ($child -and $child.HasExited) { throw 'The API process exited. Check output/api-background-error.log and output/api-background.log.' }
    Start-Sleep -Seconds 2
  }
  throw 'Startup timed out. Check output/api-background.log and the database container status.'
} finally {
  if ($locked) { $startupLock.ReleaseMutex() }
  $startupLock.Dispose()
}
