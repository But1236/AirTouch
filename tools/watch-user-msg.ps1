<#
  盯住共享云盘目录（默认 E:\wenjian\1）：每分钟扫一次，一旦出现新文件、或已有文件的内容/时间变了，
  就把文件名打出来并退出。

  约定（用户定的）：在里面新建一个「空文件」，**文件名就是发给我的消息**。

  为什么是「退出」而不是一直跑：后台任务退出时我会收到通知，于是就能读到文件名并处理，
  处理完再把脚本重新挂起来（重新挂的时候会重新取基线，所以已经处理过的文件不会重复报）。
#>
param(
    [string]$Dir = 'E:\wenjian\1',
    [int]$IntervalSec = 60,
    [int]$TimeoutMin = 45
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 我自己拷进去的 APK 不算消息
$ignore = '^airtouch-.*\.apk$'

function Get-Snapshot {
    $h = @{}
    # 注意：不带 -File —— 用户是「新建一个文件夹、把消息写在文件夹名里」，
    # 只扫文件会完全漏掉（第一版就栽在这儿）。
    Get-ChildItem -LiteralPath $Dir -Force -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Name -match $ignore) { return }
        $kind = if ($_.PSIsContainer) { 'D' } else { 'F' }
        $h[$_.Name] = "$kind|$($_.Length)|$($_.LastWriteTimeUtc.Ticks)"
    }
    return $h
}

if (-not (Test-Path -LiteralPath $Dir)) {
    Write-Output "WATCH_ERROR 目录不存在：$Dir"
    exit 1
}

$base = Get-Snapshot
Write-Output ("WATCH_START {0}  基线 {1} 个文件（不含 apk）" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $base.Count)

$deadline = (Get-Date).AddMinutes($TimeoutMin)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $IntervalSec
    $now = Get-Snapshot
    $hit = @()
    foreach ($k in $now.Keys) {
        if (-not $base.ContainsKey($k)) { $hit += $k }
        elseif ($base[$k] -ne $now[$k]) { $hit += "$k（内容有变动）" }
    }
    if ($hit.Count -gt 0) {
        Write-Output ("NEW_MSG {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
        $hit | Sort-Object | ForEach-Object { Write-Output "  消息文件 -> $_" }
        Write-Output "DIR_LIST:"
        Get-ChildItem -LiteralPath $Dir -Force -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime |
            ForEach-Object {
                $kind = if ($_.PSIsContainer) { '[目录]' } else { '[文件]' }
                Write-Output ("  {0} {1}  ({2} bytes, {3})" -f $kind, $_.Name, $_.Length, $_.LastWriteTime.ToString('MM-dd HH:mm:ss'))
            }
        exit 0
    }
}

Write-Output ("WATCH_TIMEOUT {0}  这 {1} 分钟里没有新文件" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $TimeoutMin)
exit 0
