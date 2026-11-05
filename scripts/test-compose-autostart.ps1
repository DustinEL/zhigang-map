$ErrorActionPreference = "Stop"

$composePath = Join-Path (Split-Path -Parent $PSScriptRoot) "docker-compose.yml"
$config = & docker compose -f $composePath config --format json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose configuration is invalid."
}

$longRunningServices = @("mysql", "qdrant", "bge-m3", "local-llm", "analysis-worker", "backend", "frontend")
foreach ($serviceName in $longRunningServices) {
    $service = $config.services.$serviceName
    if ($null -eq $service) {
        throw "Missing service: $serviceName"
    }
    if ($service.restart -ne "always") {
        throw "$serviceName must use restart: always, actual: $($service.restart)"
    }
}

$frontendPorts = @($config.services.frontend.ports | ForEach-Object { "$(if ($_.published) { $_.published } else { $_ })" })
if ($frontendPorts -notcontains "5173") {
    throw "Frontend must remain available on port 5173."
}

Write-Output "Docker Compose autostart contract passed."
