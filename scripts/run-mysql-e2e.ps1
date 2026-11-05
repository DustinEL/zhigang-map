param(
    [string[]]$TestClasses = @(
        "MySqlRealSnapshotE2ETest",
        "MySqlEmergingDiscoveryE2ETest",
        "MySqlEmergingQueryRepositoryE2ETest",
        "MySqlRoleEvolutionE2ETest"
    )
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot/start-common.ps1"

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Import-LocalEnvironment $projectRoot)) {
    throw "Create .env.local before running MySQL E2E."
}
$e2eUsername = $env:MYSQL_E2E_USERNAME
$useEmptyE2ePassword = $env:MYSQL_E2E_USE_EMPTY_PASSWORD -eq "true"
if ($e2eUsername) {
    $env:MYSQL_USERNAME = $e2eUsername
}
if ($useEmptyE2ePassword) {
    $env:MYSQL_PASSWORD = ""
}
if (-not $env:MYSQL_E2E_URL) {
    if (-not $env:MYSQL_URL -or $env:MYSQL_URL -notmatch "/zhigang_map(?:[?]|$)") {
        throw ".env.local must define MYSQL_E2E_URL, or MYSQL_URL must target zhigang_map so the isolated E2E URL can be derived safely."
    }
    $env:MYSQL_E2E_URL = $env:MYSQL_URL -replace "/zhigang_map([?]|$)", '/zhigang_map_e2e$1'
}
$database = ($env:MYSQL_E2E_URL -replace "[?].*$", "") -replace ".*/", ""
if ($database -ne "zhigang_map_e2e") {
    throw "Refusing to run: MYSQL_E2E_URL must target zhigang_map_e2e, current database is $database."
}
if ($env:MYSQL_E2E_URL -match "/zhigang_map(?:[?]|$)") {
    throw "Refusing to run MySQL E2E against the formal zhigang_map database."
}

$composeMysql = $null
if (Get-Command docker -ErrorAction SilentlyContinue) {
    try {
        $composeMysql = docker compose -f (Join-Path $projectRoot "docker-compose.yml") ps --status running --services 2>$null |
            Where-Object { $_ -eq "mysql" } | Select-Object -First 1
    } catch {
        $composeMysql = $null
    }
}
$env:RUN_MYSQL_E2E = "true"
$maven = Join-Path $projectRoot "backend/mvnw.cmd"
foreach ($testClass in $TestClasses) {
    & $maven -q "-Dtest=$testClass" test -f (Join-Path $projectRoot "backend/pom.xml")
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL E2E failed: $testClass."
    }
}
Write-Host "MySQL E2E passed against zhigang_map_e2e."
