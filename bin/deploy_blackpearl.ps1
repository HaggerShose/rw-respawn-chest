# Build via make-release.ps1, then install on blackpearl test server.
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$MakeRelease = Join-Path $PSScriptRoot 'make-release.ps1'
$Jar = Join-Path $Root 'target\RespawnChest.jar'
$DeployDir = '/opt/risingworld/Plugins/RespawnChest'
$DeployJar = "$DeployDir/RespawnChest.jar"
$HostName = 'blackpearl-cursor'
$TmpJar = '/tmp/RespawnChest.jar'

Set-Location $Root
Write-Host 'Building release...'
& $MakeRelease
if ($LASTEXITCODE -ne 0) {
	Write-Error "make-release.ps1 failed (exit $LASTEXITCODE)"
}
if (-not (Test-Path $Jar)) {
	Write-Error "JAR missing: $Jar"
}

Write-Host "Deploying to ${HostName}:$DeployJar"
scp $Jar "${HostName}:$TmpJar"
if ($LASTEXITCODE -ne 0) {
	Write-Error "scp failed (exit $LASTEXITCODE)"
}
ssh $HostName "sudo mkdir -p $DeployDir && sudo cp $TmpJar $DeployJar && sudo chown -R risingworld:risingworld $DeployDir && sudo chmod 755 $DeployDir && sudo chmod 644 $DeployJar && rm -f $TmpJar && ls -la $DeployDir && sudo systemctl restart risingworld"
if ($LASTEXITCODE -ne 0) {
	Write-Error "ssh install failed (exit $LASTEXITCODE)"
}

Write-Host "Done: ${HostName}:$DeployJar"
