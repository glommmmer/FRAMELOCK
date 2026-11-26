param([switch]$Worker,[switch]$Infra,[switch]$Local,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$workspaceRoot=Split-Path -Parent $projectRoot
& (Join-Path $PSScriptRoot 'init-env.ps1')
. (Join-Path $PSScriptRoot 'runtime.ps1')
$java=Initialize-Java
foreach ($file in @((Join-Path $workspaceRoot '.env'),(Join-Path $projectRoot '.env'))) {
  if (Test-Path -LiteralPath $file) { foreach ($line in Get-Content -LiteralPath $file -Encoding UTF8) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim().Trim('"').Trim("'"),'Process') }
  } }
}
if(-not $env:VS_OCR_MODE){$env:VS_OCR_MODE='wsl'}
if(-not $env:VS_OCR_ENABLED){$env:VS_OCR_ENABLED='true'}
$env:VS_DATA_DIR=Join-Path $projectRoot '.data'
$env:FFMPEG_PATH=& (Join-Path $PSScriptRoot 'resolve-ffmpeg.ps1') -WorkspaceRoot $workspaceRoot
$env:VS_MQ_ENABLED=if ($Infra) {'true'} else {'false'}
$env:VS_REDIS_ENABLED=if ($Infra) {'true'} else {'false'}
$env:VS_WORKER_ENABLED=if ($Worker) {'true'} else {'false'}
if ($Local -and $Infra) { throw 'Choose Local or Infra, not both.' }
$env:VS_DISPATCHER=if ($Local) {'local'} else {'mq'}
$env:VS_INDEX_MODE=if ($Local) {'local'} else {'qdrant'}
if ($Worker -and -not $Infra) { throw 'Worker requires real infrastructure: use -Infra.' }
if (-not $SkipBuild) {
  $maven=Resolve-Maven
  & $maven '-q' "-Dmaven.repo.local=$(Join-Path $workspaceRoot '.cache/m2')" -f (Join-Path $projectRoot 'server/pom.xml') package
  if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
}
Set-Location -LiteralPath $projectRoot
& (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Xmx512m' '-jar' (Join-Path $projectRoot 'server/target/video-search-0.1.0.jar')
if ($LASTEXITCODE -ne 0) { throw 'API exited unsuccessfully' }
