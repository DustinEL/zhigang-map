$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $projectRoot "logs"
$logPath = Join-Path $logDirectory "startup-login.log"
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

Start-Transcript -LiteralPath $logPath -Append
try {
    Set-Location -LiteralPath $projectRoot

    $dockerDeadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $dockerDeadline) {
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            break
        }
        Start-Sleep -Seconds 5
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop did not become ready within 3 minutes."
    }

    & docker compose up -d qdrant bge-m3-model bge-m3 local-llm-model local-llm analysis-worker
    if ($LASTEXITCODE -ne 0) {
        throw "Required Docker dependencies failed to start."
    }

    $lastError = $null
    foreach ($attempt in 1..3) {
        try {
            & (Join-Path $projectRoot "start-mysql.ps1")
            $lastError = $null
            break
        } catch {
            $lastError = $_
            Write-Warning "Zhigang startup attempt $attempt failed: $($_.Exception.Message)"
            Start-Sleep -Seconds 15
        }
    }
    if ($null -ne $lastError) {
        throw $lastError
    }
} finally {
    Stop-Transcript
}
