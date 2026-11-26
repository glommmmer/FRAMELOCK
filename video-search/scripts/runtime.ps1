# Tool settings only. Cloud credentials keep their existing loading rules.
function Get-ToolSetting([string]$Name) {
  $value=[Environment]::GetEnvironmentVariable($Name,'Process')
  if($value){return $value}
  $file=Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
  if(Test-Path -LiteralPath $file){
    foreach($line in Get-Content -LiteralPath $file -Encoding UTF8){
      if($line -match ('^'+[regex]::Escape($Name)+'=(.*)$')){return $Matches[1].Trim().Trim('"').Trim("'")}
    }
  }
}
function Read-ToolOutput([string]$Executable,[string]$Arguments) {
  $info=New-Object Diagnostics.ProcessStartInfo
  $info.FileName=$Executable;$info.Arguments=$Arguments;$info.UseShellExecute=$false
  $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.CreateNoWindow=$true
  $process=[Diagnostics.Process]::Start($info)
  try{$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();if(-not $process.WaitForExit(15000)){$process.Kill();throw 'Tool version check timed out.'};$text=$stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult();if($process.ExitCode -ne 0){throw 'Tool version check failed.'};return $text}finally{$process.Dispose()}
}
function Initialize-Java {
  $javaHome=Get-ToolSetting 'JAVA_HOME'
  if($javaHome){$java=Join-Path $javaHome 'bin/java.exe'}else{
    $found=Get-Command java.exe -ErrorAction SilentlyContinue
    if(-not $found){throw 'JDK 21+ is required. Set JAVA_HOME or add Java to PATH.'}
    $java=$found.Source
  }
  if(-not (Test-Path -LiteralPath $java)){throw 'JAVA_HOME does not point to an installed JDK. Set JAVA_HOME to JDK 21+.'}
  $version=Read-ToolOutput $java '-XshowSettings:properties --version'
  if($version -notmatch '(?m)^\s*java\.version\s*=\s*(\d+)' -or [int]$Matches[1] -lt 21){throw 'JDK 21+ is required.'}
  if($version -notmatch '(?m)^\s*java\.home\s*=\s*(.+)$'){throw 'Cannot resolve the Java installation directory.'}
  $javaHome=$Matches[1].Trim()
  if(-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe'))){throw 'A full JDK is required; this installation only supplies a runtime.'}
  $env:JAVA_HOME=$javaHome
  $env:PATH=(Join-Path $javaHome 'bin')+';'+$env:PATH
  return (Join-Path $javaHome 'bin/java.exe')
}
function Resolve-Maven {
  $configured=Get-ToolSetting 'MAVEN_CMD'
  if($configured){if(-not (Test-Path -LiteralPath $configured)){throw 'MAVEN_CMD must name an existing Maven executable.'};return $configured}
  $command=Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if(-not $command){throw 'Maven 3.9+ is required. Add mvn.cmd to PATH or set MAVEN_CMD.'}
  return $command.Source
}
function Get-WslArguments {
  $distro=Get-ToolSetting 'VS_WSL_DISTRO'
  if($distro){return @('-d',$distro)}
  return @()
}
function Invoke-ProjectWsl {
  $selection=@(Get-WslArguments)
  if($MyInvocation.ExpectingInput){$input | & wsl.exe @selection @args}else{& wsl.exe @selection @args}
  $exitCode=$LASTEXITCODE
  $global:LASTEXITCODE=$exitCode
  if($exitCode -ne 0){throw 'WSL command failed. Check VS_WSL_DISTRO and the required tools in that distribution.'}
}
