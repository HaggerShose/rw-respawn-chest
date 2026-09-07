# Build RespawnChest release zip: RespawnChest/RespawnChest.jar + README.md beside it
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Jar = Join-Path $Root 'target\RespawnChest.jar'
$Readme = Join-Path $Root 'README.md'
$Stage = Join-Path $Root 'target\release-stage'
$PluginDir = Join-Path $Stage 'RespawnChest'
$Zip = Join-Path $Root 'RespawnChest.zip'

Set-Location $Root
Write-Host 'Building...'
mvn -q clean package
if (-not (Test-Path $Jar)) {
	Write-Error "JAR missing: $Jar"
}

Write-Host 'Staging...'
if (Test-Path $Stage) {
	Remove-Item -Recurse -Force $Stage
}
New-Item -ItemType Directory -Path $PluginDir | Out-Null
Copy-Item $Jar (Join-Path $PluginDir 'RespawnChest.jar')
Copy-Item $Readme (Join-Path $Stage 'README.md')

Write-Host "Writing $Zip"
if (Test-Path $Zip) {
	Remove-Item -Force $Zip
}
Compress-Archive -Path (Join-Path $Stage '*') -DestinationPath $Zip

Write-Host "Done: $Zip"
