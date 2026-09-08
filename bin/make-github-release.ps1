# Bump version, commit, build zip, tag, push, create GitHub release.
# Usage: .\bin\make-github-release.ps1 -Version 1.1.0
# Prerequisite: clean working tree; gh authenticated.
param(
	[Parameter(Mandatory = $true)]
	[Alias('v')]
	[string]$Version
)

$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^\d+\.\d+\.\d+([.-][A-Za-z0-9.-]+)?$') {
	Write-Error "Invalid version '$Version' (expected e.g. 1.1.0)"
}

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Find-Gh {
	$cmd = Get-Command gh -ErrorAction SilentlyContinue
	if ($cmd) {
		return $cmd.Source
	}
	$fallback = 'C:\Program Files\GitHub CLI\gh.exe'
	if (Test-Path $fallback) {
		return $fallback
	}
	Write-Error 'gh not found. Install GitHub CLI and run gh auth login.'
}

$Gh = Find-Gh
$Tag = "v$Version"
$PluginYml = Join-Path $Root 'src\main\resources\resources\plugin.yml'
$Pom = Join-Path $Root 'pom.xml'
$Zip = Join-Path $Root 'RespawnChest.zip'
$MakeRelease = Join-Path $PSScriptRoot 'make-release.ps1'

$dirty = git status --porcelain
if ($dirty) {
	Write-Error "Working tree is not clean:`n$dirty"
}

$existingTag = git tag -l $Tag
if ($existingTag) {
	Write-Error "Tag $Tag already exists"
}

& $Gh auth status | Out-Null

Write-Host "Setting version $Version ..."
$yml = [System.IO.File]::ReadAllText($PluginYml) -replace '(?m)^version:\s*.+$', "version: $Version"
if ($yml -notmatch "(?m)^version:\s*$([regex]::Escape($Version))\s*$") {
	Write-Error "Failed to update version in plugin.yml"
}
[System.IO.File]::WriteAllText($PluginYml, ($yml -replace "`r`n", "`n").TrimEnd() + "`n")

$pomText = [System.IO.File]::ReadAllText($Pom)
$pomUpdated = [regex]::Replace(
	$pomText,
	'(<artifactId>rw-respawn-chest</artifactId>\s*<version>)[^<]+(</version>)',
	"`${1}$Version`${2}",
	1)
if ($pomUpdated -eq $pomText) {
	Write-Error 'Failed to update project version in pom.xml'
}
[System.IO.File]::WriteAllText($Pom, ($pomUpdated -replace "`r`n", "`n").TrimEnd() + "`n")

git add -- $PluginYml $Pom
git commit -m "Release $Version"
if ($LASTEXITCODE -ne 0) {
	Write-Error 'git commit failed'
}

Write-Host 'Building release zip ...'
& $MakeRelease
if (-not (Test-Path $Zip)) {
	Write-Error "Zip missing: $Zip"
}

Write-Host "Tagging $Tag ..."
git tag $Tag
if ($LASTEXITCODE -ne 0) {
	Write-Error "git tag $Tag failed"
}

Write-Host 'Pushing commit and tag ...'
git push origin HEAD
if ($LASTEXITCODE -ne 0) {
	Write-Error 'git push HEAD failed'
}
git push origin $Tag
if ($LASTEXITCODE -ne 0) {
	Write-Error "git push $Tag failed"
}

Write-Host "Creating GitHub release $Tag ..."
& $Gh release create $Tag $Zip --title "RespawnChest $Version" --generate-notes
if ($LASTEXITCODE -ne 0) {
	Write-Error 'gh release create failed'
}

Write-Host "Done: $Tag"
