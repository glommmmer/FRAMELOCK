param([switch]$CheckOnly,[switch]$MySqlOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'init-env.ps1')
$localEnv=Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $localEnv)) {
    $lines=@('VS_ADMIN_USER=demo')
    foreach ($name in @('VS_DB_PASSWORD','VS_MYSQL_ROOT_PASSWORD','VS_REDIS_PASSWORD','VS_ADMIN_PASSWORD')) { $lines+="$name=$([guid]::NewGuid().ToString('N'))" }
    [IO.File]::WriteAllLines($localEnv,$lines,[Text.UTF8Encoding]::new($false))
}
foreach ($line in Get-Content -LiteralPath $localEnv -Encoding UTF8) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
}
if ($CheckOnly) {
if (-not ('VideoSearchMemory' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class VideoSearchMemory {
  [StructLayout(LayoutKind.Sequential)] public class Status { public uint Length=64; public uint Load; public ulong Total,Available,TotalPage,AvailablePage,TotalVirtual,AvailableVirtual,Extended; }
  [DllImport("kernel32.dll")] public static extern bool GlobalMemoryStatusEx([In,Out] Status status);
}
'@
}
$memory=[VideoSearchMemory+Status]::new()
if (-not [VideoSearchMemory]::GlobalMemoryStatusEx($memory)) { throw 'Cannot inspect available host memory.' }
$available=[math]::Round($memory.Available/1GB,1)
Write-Output "Available host memory: $available GiB"
return
}
& (Join-Path $PSScriptRoot 'keep-wsl.ps1')
# The WSL Docker Engine is a system service, unlike the Docker Desktop GUI.
Invoke-ProjectWsl -u root -- systemctl start docker
if ($LASTEXITCODE -ne 0) { throw 'Could not start Docker Engine in the configured WSL distribution.' }
$minioImage=Invoke-ProjectWsl -- docker images --format "{{.ID}}" framelock-minio:2025-09-07
if(-not $minioImage){& (Join-Path $PSScriptRoot 'prepare-minio.ps1')}
$linuxPath='/mnt/'+$projectRoot.Substring(0,1).ToLowerInvariant()+$projectRoot.Substring(2).Replace('\','/')
Invoke-ProjectWsl --cd $linuxPath -- docker compose --env-file .env -f deploy/compose.yml --profile infra config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed.' }
if ($MySqlOnly) { Invoke-ProjectWsl --cd $linuxPath -- docker compose --env-file .env -f deploy/compose.yml --profile infra up -d --wait --wait-timeout 120 mysql minio }
else { Invoke-ProjectWsl --cd $linuxPath -- docker compose --env-file .env -f deploy/compose.yml --profile infra up -d }
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure startup failed.' }
