param([ValidateSet('all','index','evaluate')][string]$Stage='all',[string]$Query='',[ValidateSet(1,2)][int]$SampleSeconds=1,[ValidateSet('images','video')][string]$InputMode='images',[switch]$Cloud,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
if ($Stage -ne 'evaluate' -and -not $Cloud) { throw 'Paid model calls require -Cloud. Offline evaluation uses -Stage evaluate.' }
$projectRoot=Split-Path -Parent $PSScriptRoot
$workspaceRoot=Split-Path -Parent $projectRoot
if(-not (Test-Path -LiteralPath (Join-Path $projectRoot 'samples/beverage/fixture-v1/queries.json'))){throw 'Private experiment samples are missing. See docs/evaluation/README.md; normal tests generate their own media and need no private videos.'}
. (Join-Path $PSScriptRoot 'runtime.ps1')
$java=Initialize-Java
$env:FFMPEG_PATH=& (Join-Path $PSScriptRoot 'resolve-ffmpeg.ps1') -WorkspaceRoot $workspaceRoot
$env:VISION_INPUT_MODE=$InputMode
if (Test-Path -LiteralPath (Join-Path $workspaceRoot '.env')) {
  foreach ($line in Get-Content -LiteralPath (Join-Path $workspaceRoot '.env') -Encoding UTF8) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim().Trim('"').Trim("'"),'Process') }
  }
}
if (-not $SkipBuild) {
  Push-Location (Join-Path $projectRoot 'server')
  try { & (Resolve-Maven) "-Dmaven.repo.local=$(Join-Path $workspaceRoot '.cache/m2')" package; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } } finally { Pop-Location }
}
$labArguments=@('-jar',(Join-Path $projectRoot 'server/target/video-search-0.1.0.jar'),'lab','--root',$projectRoot,'--stage',$Stage,'--sample-interval-ms',($SampleSeconds*1000).ToString(),'--cloud')
if ($Stage -eq 'evaluate') { $labArguments=@('-jar',(Join-Path $projectRoot 'server/target/video-search-0.1.0.jar'),'evaluate',$projectRoot,(Join-Path $projectRoot 'samples/beverage/fixture-v1')) }
if ($Query) { $labArguments+=@('--query',$Query) }
& $java @labArguments
if ($LASTEXITCODE -ne 0) { throw 'Experiment failed; retained model caches and budget reservations for diagnosis.' }
