$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FrontendRoot = Join-Path $ProjectRoot "frontend"
$StaticRoot = Join-Path $FrontendRoot "dist-static"
$Version = Get-Date -Format "yyyyMMdd-HHmmss"
$PackageRoot = Join-Path $PSScriptRoot "static-releases/$Version"

Push-Location $FrontendRoot
try {
    npm run snapshot:validate
    if ($LASTEXITCODE -ne 0) { throw "Frozen snapshot validation failed." }
    npm run build:static
    if ($LASTEXITCODE -ne 0) { throw "Static frontend build failed." }
    npm run scan:static
    if ($LASTEXITCODE -ne 0) { throw "Static sensitive-information scan failed." }
} finally { Pop-Location }

New-Item -ItemType Directory -Path $PackageRoot | Out-Null
New-Item -ItemType Directory -Path (Join-Path $PackageRoot "data/snapshots") -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $StaticRoot "index.html") -Destination (Join-Path $PackageRoot "index.html")
Copy-Item -LiteralPath (Join-Path $StaticRoot "assets") -Destination (Join-Path $PackageRoot "assets") -Recurse
Copy-Item -LiteralPath (Join-Path $StaticRoot "data/current.json") -Destination (Join-Path $PackageRoot "data/current.json")
Copy-Item -LiteralPath (Join-Path $StaticRoot "data/metadata.json") -Destination (Join-Path $PackageRoot "data/metadata.json")
$SnapshotMetadata = Get-Content -LiteralPath (Join-Path $StaticRoot "data/metadata.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$SnapshotFile = Join-Path $StaticRoot "data/snapshots/$($SnapshotMetadata.code).json"
if (-not (Test-Path -LiteralPath $SnapshotFile -PathType Leaf)) { throw "Current versioned snapshot is missing: $SnapshotFile" }
Copy-Item -LiteralPath $SnapshotFile -Destination (Join-Path $PackageRoot "data/snapshots/$($SnapshotMetadata.code).json")
Get-ChildItem -LiteralPath (Join-Path $StaticRoot "data/snapshots") -Filter "$($SnapshotMetadata.code)-*.json" -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $PackageRoot "data/snapshots/$($_.Name)")
}
foreach ($PublicFile in @("favicon.svg", "logo-mark.svg")) {
    $PublicPath = Join-Path $StaticRoot $PublicFile
    if (Test-Path -LiteralPath $PublicPath -PathType Leaf) {
        Copy-Item -LiteralPath $PublicPath -Destination (Join-Path $PackageRoot $PublicFile)
    }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "install-static.sh") -Destination (Join-Path $PackageRoot "install-static.sh")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "nginx-zhigangmap-static-ssl.conf") -Destination (Join-Path $PackageRoot "nginx-zhigangmap-static-ssl.conf")
$Hashes = Get-ChildItem -LiteralPath $PackageRoot -Recurse -File | Get-FileHash -Algorithm SHA256 | ForEach-Object {
    "{0}  {1}" -f $_.Hash.ToLowerInvariant(), $_.Path.Substring($PackageRoot.Length + 1).Replace('\', '/')
}
[System.IO.File]::WriteAllText((Join-Path $PackageRoot "SHA256SUMS.txt"), (($Hashes -join "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))
$ZipPath = Join-Path $PSScriptRoot "static-releases/zhigang-static-$Version.zip"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Archive = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -LiteralPath $PackageRoot -Recurse -File | ForEach-Object {
        $EntryName = $_.FullName.Substring($PackageRoot.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $_.FullName, $EntryName, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $Archive.Dispose()
}
Write-Host "Static package: $ZipPath"
