$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "start-on-login.ps1"
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw "Missing login startup script: $scriptPath"
}

$content = Get-Content -Raw -Encoding UTF8 -LiteralPath $scriptPath
foreach ($requiredText in @(
        'docker info',
        'analysis-worker',
        'bge-m3',
        'local-llm',
        'qdrant',
        'start-mysql.ps1',
        'Start-Transcript',
        'Start-Sleep')) {
    if (-not $content.Contains($requiredText)) {
        throw "Login startup script is missing required behavior: $requiredText"
    }
}

if ($content.Contains(' backend ') -or $content.Contains(' frontend ')) {
    throw "Login startup script must not start stale Docker frontend/backend services."
}

Write-Output "Login startup script contract passed."
