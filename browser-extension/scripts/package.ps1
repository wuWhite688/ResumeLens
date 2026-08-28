[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$extensionRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$manifestPath = Join-Path $extensionRoot 'manifest.json'
$sourcePath = Join-Path $extensionRoot 'src'

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Missing manifest: $manifestPath"
}
if (-not (Test-Path -LiteralPath $sourcePath -PathType Container)) {
    throw "Missing extension source directory: $sourcePath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$distRoot = [IO.Path]::GetFullPath((Join-Path $extensionRoot 'dist'))
$distPrefix = $distRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$stagePath = [IO.Path]::GetFullPath((Join-Path $distRoot ("stage-" + [guid]::NewGuid().ToString('N'))))
if (-not $stagePath.StartsWith($distPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to stage outside extension dist: $stagePath"
}

$archivePath = Join-Path $distRoot ("resumelens-browser-extension-v{0}.zip" -f $manifest.version)
New-Item -ItemType Directory -Path $stagePath -Force | Out-Null
try {
    Copy-Item -LiteralPath $manifestPath -Destination $stagePath
    Copy-Item -LiteralPath $sourcePath -Destination $stagePath -Recurse
    Compress-Archive -Path (Join-Path $stagePath '*') -DestinationPath $archivePath -CompressionLevel Optimal -Force
} finally {
    if (Test-Path -LiteralPath $stagePath) {
        Remove-Item -LiteralPath $stagePath -Recurse -Force
    }
}

Write-Output $archivePath
