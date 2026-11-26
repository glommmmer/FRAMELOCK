param([string]$WorkspaceRoot)
$ErrorActionPreference='Stop'
if ($env:FFMPEG_PATH) { return $env:FFMPEG_PATH }
$bundled=Join-Path $PSScriptRoot '../tools/ffmpeg/win-x64/ffmpeg.exe'
if (Test-Path -LiteralPath $bundled) { return [IO.Path]::GetFullPath($bundled) }
$installed=Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($installed) { return $installed.Source }
throw 'Install FFmpeg on PATH or set FFMPEG_PATH to its executable.'
