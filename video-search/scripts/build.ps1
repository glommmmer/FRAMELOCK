param([switch]$SkipTests,[switch]$RealStackTests)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$workspaceRoot=Split-Path -Parent $projectRoot
. (Join-Path $PSScriptRoot 'runtime.ps1')
$java=Initialize-Java
$env:FFMPEG_PATH=& (Join-Path $PSScriptRoot 'resolve-ffmpeg.ps1') -WorkspaceRoot $workspaceRoot
Push-Location (Join-Path $projectRoot 'web')
try {
  if (-not (Test-Path node_modules/vite/bin/vite.js)) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } }
  & npm.cmd test
  if ($LASTEXITCODE -ne 0) { throw 'Frontend behavior tests failed.' }
  & node node_modules/vite/bin/vite.js build
  if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
$repository=Get-ToolSetting 'MAVEN_REPO_LOCAL'
if(-not $repository){$repository=Join-Path $workspaceRoot '.cache/m2'}
$arguments=@('-q','-s',(Join-Path $PSScriptRoot 'maven-settings.xml'),"-Dmaven.repo.local=$repository",'-f',(Join-Path $projectRoot 'server/pom.xml'),'package')
if ($SkipTests -and $RealStackTests) {throw 'Choose SkipTests or RealStackTests.'}
if ($SkipTests) { $arguments+='-DskipTests' }
if ($RealStackTests) {$arguments+=@('-Dframelock.real=true','-Dframelock.pipeline=true')}
$maven=Resolve-Maven
& $maven @arguments
if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
Write-Output 'Built frontend and executable API/Worker JAR.'
