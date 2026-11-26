$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'runtime.ps1')
$selection=@(Get-WslArguments)
$distro=if($selection.Count){$selection[1]}else{'<default>'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$stateFolder=Join-Path $projectRoot '.data'
New-Item -ItemType Directory -Path $stateFolder -Force | Out-Null
$stateFile=Join-Path $stateFolder 'wsl-keepalive.json'
if (Test-Path -LiteralPath $stateFile) {
    $state=Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    $existing=Get-Process -Id $state.pid -ErrorAction SilentlyContinue
    if ($existing -and $existing.ProcessName -eq 'wsl' -and $existing.StartTime.ToUniversalTime().ToString('O') -eq $state.started -and $state.PSObject.Properties.Name -contains 'persistent' -and $state.persistent -and $state.distro -eq $distro) { return }
    if($existing -and $existing.ProcessName -eq 'wsl' -and $existing.StartTime.ToUniversalTime().ToString('O') -eq $state.started){
      $command=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.pid)" -ErrorAction SilentlyContinue
      if($command.CommandLine -match 'sleep\s+(infinity|21600)'){Stop-Process -Id $existing.Id}
    }
}
# WSL systemd services alone do not keep the distro alive after a console client exits.
$helper=Start-Process -FilePath 'wsl.exe' -ArgumentList ($selection+@('--','sleep','infinity')) -WindowStyle Hidden -PassThru
@{pid=$helper.Id;started=$helper.StartTime.ToUniversalTime().ToString('O');persistent=$true;distro=$distro;arguments=($selection+@('--','sleep','infinity'))} | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
