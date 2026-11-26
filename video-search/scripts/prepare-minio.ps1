$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$root=Split-Path -Parent $PSScriptRoot
$cache=Join-Path $root '.data/minio-build'
[IO.Directory]::CreateDirectory($cache) | Out-Null
$release='RELEASE.2025-09-07T16-13-09Z'
$asset="minio.linux-amd64.$release"
$url="https://github.com/minio/minio/releases/download/$release/$asset"
$binary=Join-Path $cache 'minio'
$checksum=Join-Path $cache 'sha256sum'
if(-not (Test-Path -LiteralPath $checksum)){Invoke-WebRequest "$url.sha256sum" -OutFile $checksum -UseBasicParsing}
$expected=(Get-Content -LiteralPath $checksum -Raw).Split(' ')[0].Trim()
if(-not (Test-Path -LiteralPath $binary)){Invoke-WebRequest $url -OutFile $binary -UseBasicParsing}
if((Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash -ne $expected){throw 'MinIO official asset checksum mismatch.'}
if(-not (Test-Path -LiteralPath (Join-Path $cache 'LICENSE'))){Invoke-WebRequest "https://raw.githubusercontent.com/minio/minio/$release/LICENSE" -OutFile (Join-Path $cache 'LICENSE') -UseBasicParsing}
Copy-Item -LiteralPath (Join-Path $root 'deploy/minio.Dockerfile') -Destination (Join-Path $cache 'Dockerfile')
$linux='/mnt/'+$cache.Substring(0,1).ToLowerInvariant()+$cache.Substring(2).Replace('\','/')
Invoke-ProjectWsl -- chmod +x ($linux+'/minio')
Invoke-ProjectWsl -- docker build -q -t 'framelock-minio:2025-09-07' $linux
if($LASTEXITCODE -ne 0){throw 'MinIO local image build failed.'}
