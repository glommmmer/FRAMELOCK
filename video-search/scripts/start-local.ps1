param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
Write-Output 'Reduced-resource mode: MySQL + MinIO (legacy/audio OSS) + local durable dispatcher/vector search. RocketMQ/Redis/Qdrant integration is not exercised.'
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
& (Join-Path $PSScriptRoot 'start-infra.ps1') -MySqlOnly
& (Join-Path $PSScriptRoot 'start-api.ps1') -Local -SkipBuild
