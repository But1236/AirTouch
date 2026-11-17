#requires -Version 7.0
<#
.SYNOPSIS
    量化「Nagle + 延迟 ACK」对小包定时流的伤害，并验证 TCP_NODELAY 是否解决它。

.DESCRIPTION
    背景：空中鼠标每帧约 40 字节、每秒 144~500 帧。若发送方开着 Nagle（Linux/Android 默认开，
    而 OkHttp 4.12 从不设置 TCP_NODELAY），发送方会「有未确认数据时把小包攒着」，Windows 的
    延迟 ACK 最多等 200ms 才回 ACK —— 于是出现「平均几毫秒很流畅、每隔一会儿冻结 200+ms」的
    典型症状（与手机端实测 平均 6ms / 最大 236ms 完全一致）。

    本脚本在同一台机器上开一对 TCP socket（默认走回环），按给定频率发小包，另一端逐包打时间戳，
    分别统计 NoDelay = false / true 两种设置的到达间隔分布。

    **注意**：回环不一定复现（Windows 对回环的 ACK 行为与真实网卡可能不同）。本脚本的价值在于
    给出可复现的数字；真机验证要看手机端诊断页的「最大到达间隔」。

.EXAMPLE
    pwsh -File tools\nagle-probe.ps1
    pwsh -File tools\nagle-probe.ps1 -Hz 144 -Seconds 3 -Payload 40
    pwsh -File tools\nagle-probe.ps1 -BindAddress 192.168.1.7   # 走真实网卡而不是回环
#>
param(
    [int]$Hz = 144,
    [int]$Seconds = 3,
    [int]$Payload = 40,
    [string]$BindAddress = '127.0.0.1'
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Threading;

public static class NagleProbe
{
    public sealed class Result
    {
        public bool NoDelay;
        public int Sent;
        public int Received;
        public double MeanGapMs;
        public double MaxGapMs;
        public int StallsOver50Ms;
        public int StallsOver100Ms;
        public double P99GapMs;
    }

    public static Result Run(IPAddress bind, int hz, int seconds, int payload, bool noDelay)
    {
        var listener = new TcpListener(bind, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;

        var accepted = new ManualResetEventSlim(false);
        Socket serverSocket = null;
        var serverThread = new Thread(() =>
        {
            serverSocket = listener.AcceptSocket();
            accepted.Set();
        });
        serverThread.IsBackground = true;
        serverThread.Start();

        using var client = new Socket(bind.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
        client.NoDelay = noDelay;
        client.Connect(new IPEndPoint(bind, port));
        accepted.Wait(TimeSpan.FromSeconds(5));

        var result = new Result { NoDelay = noDelay };
        var gaps = new List<double>();
        var receiverReady = new ManualResetEventSlim(false);

        // 接收线程：逐次读取打时间戳，统计到达间隔
        var receiver = new Thread(() =>
        {
            var buffer = new byte[Math.Max(256, payload)];
            serverSocket.ReceiveTimeout = 400;
            var watch = Stopwatch.StartNew();
            double last = -1;
            receiverReady.Set();
            while (true)
            {
                int read;
                try { read = serverSocket.Receive(buffer); }
                catch (SocketException) { break; }   // 超时或关闭 → 结束
                catch (Exception) { break; }
                if (read <= 0) break;

                var now = watch.Elapsed.TotalMilliseconds;
                if (last >= 0)
                {
                    var gap = now - last;
                    gaps.Add(gap);
                }
                last = now;
                result.Received++;
            }
        });
        receiver.IsBackground = true;
        receiver.Start();
        receiverReady.Wait(TimeSpan.FromSeconds(5));

        var frame = new byte[payload];
        for (var i = 0; i < payload; i++) frame[i] = (byte)('a' + (i % 26));

        var senderWatch = Stopwatch.StartNew();
        var next = 0.0;
        var intervalMs = 1000.0 / hz;
        while (senderWatch.Elapsed.TotalSeconds < seconds)
        {
            var due = next;
            var elapsed = senderWatch.Elapsed.TotalMilliseconds;
            if (elapsed < due)
            {
                var wait = due - elapsed;
                if (wait > 1.5) Thread.Sleep((int)(wait - 1));
                else Thread.SpinWait(200);
                continue;
            }

            try { client.Send(frame); }
            catch (SocketException) { break; }
            result.Sent++;
            next += intervalMs;
        }

        // 给最后的帧一点时间落地
        Thread.Sleep(400);
        try { client.Shutdown(SocketShutdown.Both); } catch { }
        receiver.Join(500);
        try { serverSocket?.Close(); } catch { }
        try { listener.Stop(); } catch { }

        if (gaps.Count > 0)
        {
            gaps.Sort();
            double sum = 0;
            foreach (var g in gaps) sum += g;
            result.MeanGapMs = sum / gaps.Count;
            result.MaxGapMs = gaps[gaps.Count - 1];
            result.P99GapMs = gaps[(int)Math.Min(gaps.Count - 1, Math.Floor(gaps.Count * 0.99))];
            foreach (var g in gaps)
            {
                if (g > 50) result.StallsOver50Ms++;
                if (g > 100) result.StallsOver100Ms++;
            }
        }

        return result;
    }
}
'@

function Show-Result($r) {
    $label = if ($r.NoDelay) { 'NoDelay = true  (TCP_NODELAY 开)' } else { 'NoDelay = false (Nagle 开，= Android 默认)' }
    Write-Host ""
    Write-Host "--- $label ---" -ForegroundColor Cyan
    '{0,-14}: {1} 帧发送 / {2} 帧到达' -f 'sent/received', $r.Sent, $r.Received
    '{0,-14}: {1:N2} ms' -f 'mean gap', $r.MeanGapMs
    '{0,-14}: {1:N2} ms' -f 'p99 gap', $r.P99GapMs
    '{0,-14}: {1:N2} ms' -f 'max gap', $r.MaxGapMs
    '{0,-14}: {1} (>50ms) / {2} (>100ms)' -f 'stalls', $r.StallsOver50Ms, $r.StallsOver100Ms
}

$addr = [System.Net.IPAddress]::Parse($BindAddress)
Write-Host "目标 $BindAddress，频率 $Hz Hz，时长 $Seconds s，每帧 $Payload 字节" -ForegroundColor Yellow

$nagleOn = [NagleProbe]::Run($addr, $Hz, $Seconds, $Payload, $false)
Show-Result $nagleOn

$nagleOff = [NagleProbe]::Run($addr, $Hz, $Seconds, $Payload, $true)
Show-Result $nagleOff

Write-Host ""
if ($nagleOn.MaxGapMs -gt 50 -and $nagleOff.MaxGapMs -lt $nagleOn.MaxGapMs / 2) {
    Write-Host "结论：Nagle 确实制造了 $([int]$nagleOn.MaxGapMs) ms 级停顿，TCP_NODELAY 把它压到 $([int]$nagleOff.MaxGapMs) ms。" -ForegroundColor Green
} elseif ($nagleOn.MaxGapMs -lt 50) {
    Write-Host "结论：本机($BindAddress)未复现 Nagle 停顿（回环常常立即回 ACK）。这不能证明真实 Wi-Fi 链路上不存在该问题。" -ForegroundColor Yellow
} else {
    Write-Host "结论：两种情况都有停顿，瓶颈不在这里（看手机端最大到达间隔与 Wi-Fi 环境）。" -ForegroundColor Yellow
}
