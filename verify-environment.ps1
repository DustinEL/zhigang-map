param(
    [switch]$SkipBrowser
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Invoke-Step([string]$Name, [scriptblock]$Action) {
    Write-Host "`n== $Name ==" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

foreach ($command in @("java", "node", "npm")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command is required but was not found on PATH."
    }
}

Invoke-Step "Backend verification" {
    Push-Location (Join-Path $root "backend")
    try { & ".\mvnw.cmd" -q -Pcoverage verify } finally { Pop-Location }
}
Invoke-Step "Frontend type check and build" {
    Push-Location (Join-Path $root "frontend")
    try { npm run type-check; if ($LASTEXITCODE -eq 0) { npm run build } } finally { Pop-Location }
}
Invoke-Step "Frozen snapshot build and security scan" {
    Push-Location (Join-Path $root "frontend")
    try { npm run build:static; if ($LASTEXITCODE -eq 0) { npm run scan:static } } finally { Pop-Location }
}
Invoke-Step "Frontend unit tests" {
    Push-Location (Join-Path $root "frontend")
    try { npm test } finally { Pop-Location }
}
Invoke-Step "Frontend coverage" {
    Push-Location (Join-Path $root "frontend")
    try { npm run test:coverage } finally { Pop-Location }
}
Invoke-Step "Authorized collector tests" {
    Push-Location (Join-Path $root "collector")
    try { npm test } finally { Pop-Location }
}

if (-not $SkipBrowser) {
    Invoke-Step "Desktop and mobile browser flows" {
        Push-Location (Join-Path $root "frontend")
        try { npm run test:e2e } finally { Pop-Location }
    }
}

Write-Host "`nZhigang environment verification passed." -ForegroundColor Green
