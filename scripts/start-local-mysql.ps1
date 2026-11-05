$ErrorActionPreference = "Stop"

function Start-LocalZhigangMySql([Parameter(Mandatory = $true)][string]$ProjectRoot) {
    $server = $env:LOCAL_MYSQL_SERVER
    $dataDirectory = $env:LOCAL_MYSQL_DATA_DIR
    $port = if ($env:LOCAL_MYSQL_PORT) { [int]$env:LOCAL_MYSQL_PORT } else { 3306 }
    $mysqlXPort = if ($env:LOCAL_MYSQL_X_PORT) { [int]$env:LOCAL_MYSQL_X_PORT } else { 33061 }

    if (-not (Test-Path -LiteralPath $server -PathType Leaf)) {
        throw "LOCAL_MYSQL_SERVER does not exist: $server"
    }
    if (-not (Test-Path -LiteralPath $dataDirectory -PathType Container)) {
        throw "LOCAL_MYSQL_DATA_DIR does not exist: $dataDirectory"
    }
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        return
    }

    $baseDirectory = Split-Path -Parent (Split-Path -Parent $server)
    $logDirectory = Join-Path $env:LOCALAPPDATA "ZhigangMap"
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $arguments = @(
        "--no-defaults",
        "--basedir=$baseDirectory",
        "--datadir=$dataDirectory",
        "--port=$port",
        "--bind-address=127.0.0.1",
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_0900_ai_ci",
        "--mysqlx-port=$mysqlXPort",
        "--log-error=$(Join-Path $logDirectory 'mysql-error.log')"
    )
    Start-Process -FilePath $server -ArgumentList $arguments -WindowStyle Hidden | Out-Null

    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Local MySQL did not start on port $port. Check $logDirectory/mysql-error.log."
}
