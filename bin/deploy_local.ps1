# Build via make-release.ps1, then install into local Rising World Plugins folder.
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$MakeRelease = Join-Path $PSScriptRoot 'make-release.ps1'
$Jar = Join-Path $Root 'target\RespawnChest.jar'
$DeployDir = 'C:\Program Files (x86)\Steam\steamapps\common\RisingWorld\Plugins\RespawnChest'
$DeployJar = Join-Path $DeployDir 'RespawnChest.jar'

Set-Location $Root
Write-Host 'Building release...'
& $MakeRelease
if ($LASTEXITCODE -ne 0) {
	Write-Error "make-release.ps1 failed (exit $LASTEXITCODE)"
}
if (-not (Test-Path $Jar)) {
	Write-Error "JAR missing: $Jar"
}

Write-Host "Deploying to $DeployJar"
if (-not (Test-Path $DeployDir)) {
	New-Item -ItemType Directory -Path $DeployDir -Force | Out-Null
}
Copy-Item -Force $Jar $DeployJar

Write-Host "Done: $DeployJar"
