# AirTouch Android 一键构建 / 安装 / 看日志 —— 完全不需要 Android Studio。
#
# 用法：
#   pwsh -File tools\android.ps1               # 编译 → 装到手机 → 自动启动
#   pwsh -File tools\android.ps1 -BuildOnly    # 只编译，不碰手机
#   pwsh -File tools\android.ps1 -Devices      # 检查手机连上了没
#   pwsh -File tools\android.ps1 -Log          # 实时看手机上 AirTouch 的日志（Ctrl+C 退出）
#
# 依赖仓库内已有的两套工具链（都在 .gitignore 里，不会被提交）：
#   .jdk\jdk-17*\              便携版 JDK 17（Gradle 8.7 最高只支持 Java 21，所以必须是 17）
#   .android-sdk\platform-tools\adb.exe

param(
    [switch]$BuildOnly,
    [switch]$Devices,
    [switch]$Log
)

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$appId = 'com.airtouch.app'

function Fail($msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

# ---- JDK：Gradle 8.7 只接受 Java 8~21，这里固定用仓库内的 17 ----
$jdk = Get-ChildItem (Join-Path $root '.jdk') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
       Select-Object -First 1
if (-not $jdk) { Fail "找不到 $root\.jdk\jdk-17* —— JDK 被删了？" }
$env:JAVA_HOME = $jdk.FullName

# ---- adb ----
$adb = Join-Path $root '.android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $adb = $found.Source } else { Fail "找不到 adb，请确认 .android-sdk\platform-tools 存在" }
}

# ---- 只看日志 ----
if ($Log) {
    $appPid = (& $adb shell pidof $appId 2>$null | Out-String).Trim()
    if (-not $appPid) { Fail "AirTouch 没在手机上运行 —— 先在手机上打开它" }
    Write-Host "正在显示 AirTouch 的日志（Ctrl+C 退出）..." -ForegroundColor Cyan
    & $adb logcat --pid=$appPid
    exit 0
}

# ---- 检查手机 ----
$deviceList = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice\s*$' })

if ($Devices) {
    if ($deviceList.Count -gt 0) {
        Write-Host "已连接的设备：" -ForegroundColor Green
        $deviceList | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host "没有检测到手机。挨个检查：" -ForegroundColor Yellow
        Write-Host "  1) 数据线是不是只能充电的线？换一根"
        Write-Host "  2) 手机 设置 → 开发者选项 → USB 调试 打开了吗"
        Write-Host "  3) 插上后手机上有没有弹「允许 USB 调试吗」→ 要允许"
        Write-Host "  4) 手机上把 USB 模式从「仅充电」改成「文件传输 / MTP」"
    }
    exit 0
}

if (-not $BuildOnly -and $deviceList.Count -eq 0) {
    Fail "没有检测到手机。先跑：pwsh -File tools\android.ps1 -Devices   （或加 -BuildOnly 只编译）"
}

# ---- 编译 ----
Write-Host "==> 编译中（JDK: $($jdk.Name)）" -ForegroundColor Cyan
Push-Location (Join-Path $root 'android')
try {
    & .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { Fail "编译失败 —— 往上看具体报错" }
} finally {
    Pop-Location
}

$apk = Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { Fail "编译声称成功，但没找到 APK：$apk" }
Write-Host ("==> APK 就绪：{0:N2} MB" -f ((Get-Item $apk).Length / 1MB)) -ForegroundColor Green

if ($BuildOnly) { exit 0 }

# ---- 安装 ----
Write-Host "==> 安装到手机" -ForegroundColor Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { Fail "安装失败（如果是签名冲突，先在手机上卸载旧的 AirTouch）" }

# ---- 启动 ----
Write-Host "==> 启动 AirTouch" -ForegroundColor Cyan
& $adb shell monkey -p $appId -c android.intent.category.LAUNCHER 1 | Out-Null

Write-Host "完成 —— 手机上应该已经打开 AirTouch 了。" -ForegroundColor Green
