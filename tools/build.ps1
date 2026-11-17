# Builds the AirTouch Windows projects one by one.
#
# Why not `dotnet build server\AirTouch.sln`: on some SDK installations classic .sln restore fails
# inside MSBuild's _FilterRestoreGraphProjectInputItems task
# because two SDK components are missing from the installation
# (Microsoft.NET.SDK.WorkloadAutoImportPropsLocator / WorkloadManifestTargetsLocator). Building each
# project directly avoids the .sln metaproj entirely, and the .sln is still there for Visual Studio.
#
# Usage:
#   pwsh -File tools\build.ps1              # Debug build of all three projects
#   pwsh -File tools\build.ps1 -Release     # Release build
#   pwsh -File tools\build.ps1 -Test        # build, then run the end-to-end self test
#   pwsh -File tools\build.ps1 -Run         # build, then start the WPF server

param(
    [switch]$Release,
    [switch]$Test,
    [switch]$Run
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$configuration = if ($Release) { 'Release' } else { 'Debug' }

$env:DOTNET_CLI_HOME = Join-Path $root '.dotnet-home'
$env:NUGET_PACKAGES = Join-Path $root '.nuget'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_NOLOGO = '1'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'

New-Item -ItemType Directory -Force -Path $env:DOTNET_CLI_HOME, $env:NUGET_PACKAGES | Out-Null

$projects = @(
    'server\AirTouch.Core\AirTouch.Core.csproj',
    'server\AirTouch.Server\AirTouch.Server.csproj',
    'server\AirTouch.SmokeTest\AirTouch.SmokeTest.csproj'
)

foreach ($project in $projects) {
    $full = Join-Path $root $project
    Write-Host "==> dotnet build $project ($configuration)" -ForegroundColor Cyan
    & dotnet build $full -c $configuration --nologo
    if ($LASTEXITCODE -ne 0) {
        Write-Host "构建失败：$project" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

Write-Host "构建完成（$configuration）。" -ForegroundColor Green

if ($Test) {
    $smokeDir = Join-Path $root '.smoke'
    New-Item -ItemType Directory -Force -Path $smokeDir | Out-Null
    Write-Host "==> 运行端到端自检" -ForegroundColor Cyan
    & dotnet run --project (Join-Path $root 'server\AirTouch.SmokeTest\AirTouch.SmokeTest.csproj') `
        -c $configuration --no-build -- $smokeDir
    exit $LASTEXITCODE
}

if ($Run) {
    $exe = Join-Path $root "server\AirTouch.Server\bin\$configuration\net8.0-windows\AirTouch.Server.exe"
    Write-Host "==> 启动 AirTouch.Server.exe" -ForegroundColor Cyan
    & $exe
    exit $LASTEXITCODE
}
