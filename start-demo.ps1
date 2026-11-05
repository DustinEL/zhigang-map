param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

. "$PSScriptRoot/scripts/start-common.ps1"

Start-ZhigangProject -Profile "demo" -ExpectedOperatingMode "DEMO_OFFLINE" -BackendPort $BackendPort -FrontendPort $FrontendPort
