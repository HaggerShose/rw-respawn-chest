# Sync lib/PluginAPI.jar from the Steam Rising World SDK and install it into .m2.
# Safe to run repeatedly (idempotent).
#
# Usage:
#   .\scripts\bootstrap-libs.ps1

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root "pom.xml"))) {
  $root = $PSScriptRoot
}
Set-Location $root

$libDir = Join-Path $root "lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null

$steamApi = "C:\Program Files (x86)\Steam\steamapps\common\RisingWorld\Data\SDK\PluginAPI.jar"
$libApi = Join-Path $libDir "PluginAPI.jar"

if (-not (Test-Path $steamApi)) {
  throw "PluginAPI not found at: $steamApi"
}

Write-Host "Copying PluginAPI from Steam SDK -> lib/PluginAPI.jar"
Copy-Item -Path $steamApi -Destination $libApi -Force

Write-Host "Installing PluginAPI into local .m2 ..."
mvn -q install:install-file `
  "-Dfile=$libApi" `
  "-DgroupId=net.rising-world" `
  "-DartifactId=plugin-api" `
  "-Dversion=0.9.3" `
  "-Dpackaging=jar"

Write-Host "Done. lib/PluginAPI.jar is ready; mvn package will reinstall it on validate."
