$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$saved=@{}
foreach($key in @('JAVA_HOME','MAVEN_CMD','VS_WSL_DISTRO','PATH')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
  $java=Initialize-Java
  if(-not (Test-Path -LiteralPath $java)){throw 'Java resolution failed.'}
  $env:JAVA_HOME=Join-Path $PSScriptRoot '__missing_jdk__'
  $rejected=$false
  try{Initialize-Java | Out-Null}catch{$rejected=$_.Exception.Message -like '*JAVA_HOME*'}
  if(-not $rejected){throw 'Missing JDK did not give an actionable error.'}
  $env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent $java)
  $maven=Resolve-Maven
  $env:MAVEN_CMD=$maven
  if((Resolve-Maven) -ne $maven){throw 'Explicit Maven executable was ignored.'}
  $env:MAVEN_CMD=Join-Path $PSScriptRoot '__missing_maven__.cmd'
  $rejected=$false
  try{Resolve-Maven | Out-Null}catch{$rejected=$_.Exception.Message -like '*MAVEN_CMD*'}
  if(-not $rejected){throw 'Missing Maven did not give an actionable error.'}
  $env:VS_WSL_DISTRO='framelock-test-selection'
  $selection=@(Get-WslArguments)
  if($selection.Count -ne 2 -or $selection[1] -ne $env:VS_WSL_DISTRO){throw 'Explicit WSL selection was ignored.'}
  # Invoke the real selected/default distribution from the caller; never install one.
  [Environment]::SetEnvironmentVariable('VS_WSL_DISTRO',$saved['VS_WSL_DISTRO'],'Process')
  $reply=Invoke-ProjectWsl -- echo framelock-runtime-ready
  if($reply -notcontains 'framelock-runtime-ready'){throw 'Configured WSL distribution did not respond.'}
  Write-Output 'Runtime checks passed: Java 21+, explicit/missing Maven, missing Java, WSL selection and command execution.'
} finally {
  foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
}
