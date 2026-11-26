$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$root=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'start-infra.ps1')
$prepare=@'
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS framelock_alignment CHARACTER SET utf8mb4;
GRANT ALL ON framelock_alignment.* TO 'video_search'@'%';
SQL
'@
$prepare | Invoke-ProjectWsl -- docker exec -i video-search-mysql-1 sh
if($LASTEXITCODE -ne 0){throw 'Isolated MySQL integration database preparation failed.'}
& (Join-Path $PSScriptRoot 'build.ps1') -RealStackTests
if($LASTEXITCODE -ne 0){throw 'Real stack verification failed.'}
