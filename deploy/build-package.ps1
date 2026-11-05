$ErrorActionPreference = "Stop"

function New-NormalizedZip([string]$sourceDirectory, [string]$destinationPath) {
    Add-Type -AssemblyName System.IO.Compression
    $sourceRoot = (Resolve-Path -LiteralPath $sourceDirectory).Path
    $archiveStream = [System.IO.File]::Open(
        $destinationPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $archiveStream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false,
            [System.Text.Encoding]::UTF8)
        try {
            foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File) {
                $relativePath = $file.FullName.Substring($sourceRoot.Length).TrimStart([char[]]"\/")
                $entry = $archive.CreateEntry(
                    $relativePath.Replace('\', '/'),
                    [System.IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $file.LastWriteTime
                $inputStream = $file.OpenRead()
                $outputStream = $entry.Open()
                try {
                    $inputStream.CopyTo($outputStream)
                } finally {
                    $outputStream.Dispose()
                    $inputStream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $archiveStream.Dispose()
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$version = Get-Date -Format "yyyyMMdd-HHmmss"
$releaseRoot = Join-Path $PSScriptRoot "releases/$version"
$frontendDist = Join-Path $projectRoot "frontend/dist"
$staticFrontendDist = Join-Path $projectRoot "frontend/dist-static"
$backendJar = Join-Path $projectRoot "backend/target/zhigang-backend-release.jar"

if (-not (Test-Path -LiteralPath $frontendDist)) { throw "Frontend dist is missing. Run npm run build first." }
if (-not (Test-Path -LiteralPath $staticFrontendDist)) { throw "Frozen static dist is missing. Run npm run build:static first." }
if (-not (Test-Path -LiteralPath $backendJar)) { throw "Backend release jar is missing. Run Maven package -Prelease-jar first." }

New-Item -ItemType Directory -Path (Join-Path $releaseRoot "frontend"), (Join-Path $releaseRoot "frozen-static"), (Join-Path $releaseRoot "backend"), (Join-Path $releaseRoot "database"), (Join-Path $releaseRoot "config"), (Join-Path $releaseRoot "collector") | Out-Null
Copy-Item -Path (Join-Path $frontendDist "*") -Destination (Join-Path $releaseRoot "frontend") -Recurse
Copy-Item -Path (Join-Path $staticFrontendDist "*") -Destination (Join-Path $releaseRoot "frozen-static") -Recurse
Copy-Item -LiteralPath $backendJar -Destination (Join-Path $releaseRoot "backend/zhigang-backend.jar")
foreach ($sql in @("01_schema.sql", "03_real_data_pipeline.sql", "04_review_triage.sql")) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "database/$sql") -Destination (Join-Path $releaseRoot "database/$sql")
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "nginx-zhigangmap.conf") -Destination (Join-Path $releaseRoot "config/nginx-zhigangmap.conf")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "nginx-zhigangmap-ssl.conf") -Destination (Join-Path $releaseRoot "config/nginx-zhigangmap-ssl.conf")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "nginx-zhigangmap-rate-limit.conf") -Destination (Join-Path $releaseRoot "config/nginx-zhigangmap-rate-limit.conf")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "zhigang-backend.service") -Destination (Join-Path $releaseRoot "config/zhigang-backend.service")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "zhigang.env.example") -Destination (Join-Path $releaseRoot "config/zhigang.env.example")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "install-production.sh") -Destination (Join-Path $releaseRoot "config/install-production.sh")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "preflight-production.sh") -Destination (Join-Path $releaseRoot "config/preflight-production.sh")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "activate-nginx.sh") -Destination (Join-Path $releaseRoot "config/activate-nginx.sh")
Copy-Item -LiteralPath (Join-Path $PSScriptRoot "README.md") -Destination (Join-Path $releaseRoot "config/README.md")
foreach ($collectorFile in @("collect-authorized-sources.mjs", "authorized-source-collector.mjs", "sources.example.json", "README.md", "package.json")) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "collector/$collectorFile") -Destination (Join-Path $releaseRoot "collector/$collectorFile")
}

$manifest = [ordered]@{
    version = $version
    builtAt = (Get-Date).ToString("o")
    backend = "Spring Boot 3.3.5 / Java 17"
    frontend = "Vue 3 / Vite 5"
    database = "MySQL / Flyway disabled"
    redisEnabled = $false
    releaseStatus = "LOCAL_CANDIDATE_NOT_PRODUCTION_APPROVED"
    productionPublishApproved = $false
    realRoles = @("AI Agent", "Java")
    currentSnapshotDocuments = 10
    candidateJdTarget = 300
    qualifiedCurrentJdTarget = 120
    companyTarget = 30
    sourceTypeTarget = 3
    dataScaleStatus = "CURRENT_SNAPSHOT_BELOW_SCALE_TARGET"
    backendTestCases = 76
    backendTestsPassed = 75
    backendTestsSkipped = 1
    backendMysqlE2eTestsPassed = 0
    backendMysqlE2EStatus = "NOT_RUN_REQUIRES_ISOLATED_TEST_DATABASE"
    unassistedStudentUsabilityStatus = "NOT_RUN_REQUIRES_6_STUDENTS"
    frontendStateTests = 2
    collectorTests = 5
    defaultE2eCases = 40
    sourceLedgerEnabled = $true
    note = "Version 10: the real snapshot contains 10 current observation samples and does not represent a market trend."
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $releaseRoot "VERSION.json") -Encoding UTF8

$checksums = Get-ChildItem -LiteralPath $releaseRoot -Recurse -File | Get-FileHash -Algorithm SHA256 | ForEach-Object {
    "{0}  {1}" -f $_.Hash.ToLowerInvariant(), $_.Path.Substring($releaseRoot.Length + 1).Replace('\', '/')
}
[System.IO.File]::WriteAllText((Join-Path $releaseRoot "SHA256SUMS.txt"), (($checksums -join "`n") + "`n"), [System.Text.UTF8Encoding]::new($false))
$releaseZip = Join-Path $PSScriptRoot "releases/zhigang-release-$version.zip"
$staticZip = Join-Path $PSScriptRoot "releases/zhigang-frozen-static-$version.zip"
New-NormalizedZip $releaseRoot $releaseZip
New-NormalizedZip (Join-Path $releaseRoot "frozen-static") $staticZip
Get-FileHash -Algorithm SHA256 $releaseZip, $staticZip | ForEach-Object {
    "{0}  {1}" -f $_.Hash.ToLowerInvariant(), (Split-Path -Leaf $_.Path)
} | Set-Content -LiteralPath (Join-Path $PSScriptRoot "releases/SHA256-$version.txt") -Encoding UTF8
Write-Host "Release directory: $releaseRoot"
Write-Host "Release archive: $releaseZip"
Write-Host "Frozen static archive: $staticZip"
