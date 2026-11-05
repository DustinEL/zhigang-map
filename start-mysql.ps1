. "$PSScriptRoot/scripts/start-common.ps1"

$hasLocalEnvironment = Import-LocalEnvironment $PSScriptRoot
if ($hasLocalEnvironment -and $env:LOCAL_MYSQL_MANAGED -eq "true") {
    . "$PSScriptRoot/scripts/start-local-mysql.ps1"
    Start-LocalZhigangMySql -ProjectRoot $PSScriptRoot
}

Start-ZhigangProject -Profile "mysql" -ExpectedOperatingMode "MYSQL_FACT_SOURCE"
