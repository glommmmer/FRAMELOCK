$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$projectRoot=Split-Path -Parent $PSScriptRoot
$workspaceRoot=Split-Path -Parent $projectRoot
& (Join-Path $PSScriptRoot 'init-env.ps1')
& (Join-Path $PSScriptRoot 'build.ps1')
& (Join-Path $PSScriptRoot 'keep-wsl.ps1')
& (Join-Path $PSScriptRoot 'prepare-minio.ps1')
foreach ($file in @((Join-Path $workspaceRoot '.env'),(Join-Path $projectRoot '.env'))) {
  foreach ($line in Get-Content -LiteralPath $file -Encoding UTF8) { if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim().Trim('"').Trim("'"),'Process') } }
}
# Container clients resolve the broker by its service DNS; host development uses 127.0.0.1.
$env:VS_BROKER_IP='broker'
$linuxPath='/mnt/'+$projectRoot.Substring(0,1).ToLowerInvariant()+$projectRoot.Substring(2).Replace('\','/')
# Use a generated, ignored env file; never put credential values in command arguments.
$lines=@();foreach ($name in @('VS_DB_PASSWORD','VS_MYSQL_ROOT_PASSWORD','VS_REDIS_PASSWORD','VS_ADMIN_USER','VS_ADMIN_PASSWORD','DASHSCOPE_API_KEY','DASHSCOPE_BASE_URL','OSS_ENDPOINT','OSS_BUCKET','OSS_ACCESS_KEY_ID','OSS_ACCESS_KEY_SECRET','VS_BROKER_IP','VS_MINIO_ACCESS_KEY','VS_MINIO_SECRET_KEY')) { $value=[Environment]::GetEnvironmentVariable($name,'Process');if($value){$lines+="$name=$value"} }
[IO.File]::WriteAllLines((Join-Path $projectRoot '.data/full.env'),$lines,[Text.UTF8Encoding]::new($false))
Invoke-ProjectWsl --cd $linuxPath -- docker compose --env-file .data/full.env -f deploy/compose.yml --profile full up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Full deployment failed; inspect container logs without printing secrets.' }
