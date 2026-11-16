# Build helper: runs the .NET SDK with its per-user state redirected inside this checkout.
#
# The sandbox used to scaffold this project denies writes to C:\Users\<user>\.dotnet, which the
# SDK needs for its first-run sentinel. DOTNET_CLI_HOME moves that state into .dotnet-home/.
#
# Usage:  pwsh -File tools\dotnet.ps1 build server\AirTouch.sln
#         pwsh -File tools\dotnet.ps1 run --project server\AirTouch.SmokeTest\AirTouch.SmokeTest.csproj

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$env:DOTNET_CLI_HOME = Join-Path $root '.dotnet-home'
$env:NUGET_PACKAGES = Join-Path $root '.nuget'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_NOLOGO = '1'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'

New-Item -ItemType Directory -Force -Path $env:DOTNET_CLI_HOME, $env:NUGET_PACKAGES | Out-Null

& dotnet @args
exit $LASTEXITCODE
