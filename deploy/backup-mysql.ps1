param(
    [string]$OutputDirectory = "$PSScriptRoot/backups",
    [string]$Database = "zhigang_map",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$UserName = "root"
)

$ErrorActionPreference = "Stop"
$mysqldump = Get-Command "mysqldump.exe" -ErrorAction Stop
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = Join-Path $OutputDirectory "$Database-$timestamp.sql"
& $mysqldump.Source "--host=$HostName" "--port=$Port" "--user=$UserName" "--password" "--single-transaction" "--routines" "--events" "--default-character-set=utf8mb4" $Database "--result-file=$output"
if ($LASTEXITCODE -ne 0) { throw "MySQL backup failed." }
Write-Host "Backup created: $output"
