$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
[IO.Directory]::CreateDirectory((Join-Path $projectRoot '.data')) | Out-Null
$localEnv=Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $localEnv)) {
  $lines=@('VS_ADMIN_USER=demo')
  foreach ($name in @('VS_DB_PASSWORD','VS_MYSQL_ROOT_PASSWORD','VS_REDIS_PASSWORD','VS_ADMIN_PASSWORD')) { $lines+="$name=$([guid]::NewGuid().ToString('N'))" }
  [IO.File]::WriteAllLines($localEnv,$lines,[Text.UTF8Encoding]::new($false))
}

# Add missing MinIO credentials without changing existing login/cloud credentials.
$present=Get-Content -LiteralPath $localEnv -Encoding UTF8
$extra=@()
foreach($name in @('VS_MINIO_ACCESS_KEY','VS_MINIO_SECRET_KEY')) {
  if(-not ($present -match ("^"+$name+"="))) {$extra+="$name=$([guid]::NewGuid().ToString('N'))"}
}
if($extra.Count) {[IO.File]::AppendAllLines($localEnv,[string[]]$extra,[Text.UTF8Encoding]::new($false))}
