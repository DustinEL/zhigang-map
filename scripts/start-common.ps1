$ErrorActionPreference = "Stop"

function Import-LocalEnvironment([string]$projectRoot) {
    $envFile = Join-Path $projectRoot ".env.local"
    if (-not (Test-Path -LiteralPath $envFile)) {
        return $false
    }

    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed.Split("=", 2)
        if ($parts.Count -ne 2 -or -not $parts[0].Trim()) {
            throw "Invalid .env.local entry: $line"
        }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
    }
    return $true
}

function Wait-ForUrl([string]$url, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                return $true
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

function Test-BackendBuildRequired([string]$backendRoot, [string]$backendJar) {
    if (-not (Test-Path -LiteralPath $backendJar)) {
        return $true
    }
    $jarTime = (Get-Item -LiteralPath $backendJar).LastWriteTimeUtc
    if ((Get-Item -LiteralPath (Join-Path $backendRoot "pom.xml")).LastWriteTimeUtc -gt $jarTime) {
        return $true
    }
    $newerSource = Get-ChildItem -LiteralPath (Join-Path $backendRoot "src/main") -File -Recurse |
        Where-Object { $_.LastWriteTimeUtc -gt $jarTime } |
        Select-Object -First 1
    return $null -ne $newerSource
}

function Assert-MySqlConfiguration([bool]$hasLocalEnvironment) {
    if (-not $hasLocalEnvironment) {
        throw "MySQL mode requires .env.local. Create it from .env.example first."
    }
    foreach ($name in @("MYSQL_URL", "MYSQL_USERNAME", "MYSQL_PASSWORD", "PROFESSIONAL_USERNAME",
            "PROFESSIONAL_PASSWORD", "REVIEWER_USERNAME", "REVIEWER_PASSWORD")) {
        $value = [Environment]::GetEnvironmentVariable($name, "Process")
        if (-not $value -or $value -eq "CHANGE_ME") {
            throw ".env.local is missing a usable $name value."
        }
    }
    if ($env:PROFESSIONAL_USERNAME -eq $env:REVIEWER_USERNAME) {
        throw "Professional and reviewer usernames must be different."
    }
    if ($env:DEV_PERMIT_ALL -ne "false") {
        throw "MySQL mode requires DEV_PERMIT_ALL=false."
    }
    if ($env:FLYWAY_ENABLED -ne "false") {
        throw "This release requires FLYWAY_ENABLED=false."
    }
}

function Write-ProcessRecord([string]$path, [int]$backendPid, [int]$frontendPid, [string]$profile) {
    [ordered]@{
        profile = $profile
        backendPid = $backendPid
        frontendPid = $frontendPid
        updatedAt = (Get-Date).ToString("o")
    } | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
}

function Start-ZhigangProject(
    [Parameter(Mandatory = $true)][ValidateSet("demo", "mysql")][string]$Profile,
    [Parameter(Mandatory = $true)][string]$ExpectedOperatingMode,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $backendRoot = Join-Path $projectRoot "backend"
    $frontendRoot = Join-Path $projectRoot "frontend"
    $logRoot = Join-Path $projectRoot "logs"
    $java = (Get-Command java.exe, java -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    $node = (Get-Command node.exe, node -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    $maven = Join-Path $backendRoot "mvnw.cmd"
    $backendJar = Join-Path $backendRoot "target/zhigang-backend-release.jar"
    $vite = Join-Path $frontendRoot "node_modules/vite/bin/vite.js"
    $processRecord = Join-Path $logRoot "zhigang-processes.json"

    $hasLocalEnvironment = Import-LocalEnvironment $projectRoot
    if ($Profile -eq "mysql") {
        Assert-MySqlConfiguration $hasLocalEnvironment
    } else {
        $env:DEV_PERMIT_ALL = "true"
        $env:FLYWAY_ENABLED = "false"
    }
    $env:SERVER_PORT = "$BackendPort"
    $env:VITE_DEV_API_TARGET = "http://127.0.0.1:$BackendPort"
    $env:SPRING_PROFILES_ACTIVE = $Profile

    foreach ($dependency in @(
            @{ Path = $java; Message = "Java 17 was not found on PATH." },
            @{ Path = $node; Message = "Node.js was not found on PATH." },
            @{ Path = $maven; Message = "Maven Wrapper was not found." },
            @{ Path = $vite; Message = "Frontend dependencies are missing. Run npm install in frontend." })) {
        if (-not (Test-Path -LiteralPath $dependency.Path)) {
            throw $dependency.Message
        }
    }

    if (Test-BackendBuildRequired $backendRoot $backendJar) {
        Write-Host "Backend sources changed. Building the release JAR..."
        & $maven -q -DskipTests package -Prelease-jar -f (Join-Path $backendRoot "pom.xml")
        if ($LASTEXITCODE -ne 0) {
            throw "Backend build failed."
        }
    }
    if ((Get-Item -LiteralPath $backendJar).Length -lt 10MB) {
        throw "The backend release JAR is incomplete: $backendJar"
    }

    New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    $backendPid = 0
    $backendListening = Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($backendListening) {
        $recordedBackendPid = 0
        if (Test-Path -LiteralPath $processRecord) {
            $recordedBackendPid = (Get-Content -Raw -Encoding UTF8 $processRecord | ConvertFrom-Json).backendPid
        }
        $listeningProcess = Get-Process -Id $backendListening.OwningProcess -ErrorAction SilentlyContinue
        $jarIsNewer = $listeningProcess -and (Get-Item -LiteralPath $backendJar).LastWriteTimeUtc -gt $listeningProcess.StartTime.ToUniversalTime()
        if ($jarIsNewer) {
            if ($recordedBackendPid -ne $backendListening.OwningProcess) {
                throw "The release JAR is newer, but port $BackendPort is owned by an untracked process. Stop it manually."
            }
            Stop-Process -Id $backendListening.OwningProcess
            $backendListening = $null
            Start-Sleep -Milliseconds 800
        }
    }
    if ($backendListening) {
        $backendPid = $backendListening.OwningProcess
    } else {
        $backendProcess = Start-Process -FilePath $java `
            -ArgumentList "-jar", $backendJar `
            -WorkingDirectory $backendRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logRoot "backend-$Profile.log") `
            -RedirectStandardError (Join-Path $logRoot "backend-$Profile-error.log") `
            -PassThru
        $backendPid = $backendProcess.Id
    }

    if (-not (Wait-ForUrl "http://127.0.0.1:$BackendPort/actuator/health" 45)) {
        throw "Backend did not become healthy. Check the backend log in $logRoot."
    }
    $backendListener = Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction Stop |
        Select-Object -First 1
    $backendPid = $backendListener.OwningProcess
    $runtime = (Invoke-WebRequest -Uri "http://127.0.0.1:$BackendPort/api/runtime-status" -UseBasicParsing -TimeoutSec 5).Content |
        ConvertFrom-Json
    if ($runtime.data.operatingMode -ne $ExpectedOperatingMode) {
        throw "Port 8080 is running a different Zhigang mode: $($runtime.data.operatingMode)."
    }

    $frontendPid = 0
    $frontendListening = Get-NetTCPConnection -LocalPort $FrontendPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($frontendListening) {
        $frontendPid = $frontendListening.OwningProcess
    } else {
        $frontendProcess = Start-Process -FilePath $node `
            -ArgumentList $vite, "--config", "vite.config.ts", "--host", "127.0.0.1", "--port", "$FrontendPort", "--strictPort" `
            -WorkingDirectory $frontendRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logRoot "frontend-$Profile.log") `
            -RedirectStandardError (Join-Path $logRoot "frontend-$Profile-error.log") `
            -PassThru
        $frontendPid = $frontendProcess.Id
    }
    if (-not (Wait-ForUrl "http://127.0.0.1:$FrontendPort" 30)) {
        throw "Frontend did not become ready. Check the frontend log in $logRoot."
    }

    Write-ProcessRecord $processRecord $backendPid $frontendPid $Profile
    Write-Host "Zhigang Map is ready in $Profile mode."
    Write-Host "Frontend: http://127.0.0.1:$FrontendPort"
    Write-Host "Backend: http://127.0.0.1:$BackendPort"
    Write-Host "Data mode: $($runtime.data.dataMode)"
    Write-Host "Process record: $processRecord"
}
