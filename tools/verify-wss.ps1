# Verifies the production wss:// endpoint with a non-Schannel TLS client (Node + OpenSSL).
#
# Why: on some locked-down machines .NET cannot acquire Schannel client credentials
# (SEC_E_NO_CREDENTIALS), which makes a C# client useless for verifying TLS. Node brings its own
# OpenSSL stack - the same TLS family Android uses - and tools/wss-probe.mjs speaks raw RFC 6455, so
# this checks TLS, the upgrade handshake, token auth, control frames, ping/pong and file upload
# independently of the .NET client.
#
# Requires: Node.js on PATH, and a build (`pwsh -File tools\build.ps1`).
# Note: the server needs to write the certificate's private key into the user key container, so if
# you are running inside a restricted sandbox this script needs the same wider access the app does.
#
# Usage: pwsh -File tools\verify-wss.ps1 [-Port 28443] [-WorkDir <dir>]

param(
    [int]$Port = 28443,
    [string]$WorkDir
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $WorkDir) { $WorkDir = Join-Path $root '.probe-server' }

$exe = Join-Path $root 'server\AirTouch.SmokeTest\bin\Debug\net8.0-windows\AirTouch.SmokeTest.exe'
if (-not (Test-Path $exe)) {
    Write-Host "未找到 $exe，请先运行 pwsh -File tools\build.ps1" -ForegroundColor Red
    exit 1
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "未找到 node，请安装 Node.js 后重试" -ForegroundColor Red
    exit 1
}

$env:DOTNET_CLI_HOME = Join-Path $root '.dotnet-home'
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# Start from a clean download directory so the assertions about the saved file name are deterministic.
$downloadDir = Join-Path $WorkDir 'downloads'
if (Test-Path $downloadDir) { Remove-Item -Recurse -Force $downloadDir -ErrorAction SilentlyContinue }

$stdout = Join-Path $WorkDir 'server-stdout.log'
if (Test-Path $stdout) { Remove-Item $stdout -Force }

Write-Host "==> 启动 wss 服务端（127.0.0.1:$Port）" -ForegroundColor Cyan
$process = Start-Process -FilePath $exe `
    -ArgumentList @('--serve', $WorkDir, "$Port", '150') `
    -RedirectStandardOutput $stdout -PassThru -WindowStyle Hidden

try {
    $url = $null
    $fingerprint = $null

    for ($attempt = 0; $attempt -lt 80 -and -not $url; $attempt++) {
        Start-Sleep -Milliseconds 250
        if (-not (Test-Path $stdout)) { continue }

        $lines = Get-Content $stdout -ErrorAction SilentlyContinue
        $urlLine = $lines | Where-Object { $_ -like 'AIRTOUCH_URL=*' } | Select-Object -Last 1
        $fingerprintLine = $lines | Where-Object { $_ -like 'AIRTOUCH_FINGERPRINT=*' } | Select-Object -Last 1

        if ($urlLine) { $url = $urlLine.Replace('AIRTOUCH_URL=', '') }
        if ($fingerprintLine) { $fingerprint = $fingerprintLine.Replace('AIRTOUCH_FINGERPRINT=', '') }
    }

    if (-not $url -or -not $fingerprint) {
        Write-Host "服务端未在 20 秒内输出配对信息，日志：" -ForegroundColor Red
        Get-Content $stdout -ErrorAction SilentlyContinue | Select-Object -Last 20
        exit 1
    }

    Write-Host "==> 运行 Node/OpenSSL 独立探测" -ForegroundColor Cyan
    $env:AIRTOUCH_PROBE_REPORT = Join-Path $WorkDir 'probe-report.json'
    & node (Join-Path $PSScriptRoot 'wss-probe.mjs') `
        --url $url --fingerprint $fingerprint --download (Join-Path $WorkDir 'downloads')

    $code = $LASTEXITCODE
}
finally {
    if ($process -and -not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit(3000) | Out-Null
    }
}

exit $code
