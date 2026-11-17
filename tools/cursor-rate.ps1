# cursor-rate.ps1 — 直接测量「光标注入通路」的真实刷新率、增益与显示器刷新率。
#
# 用途：手机端报告「发了 144 帧，屏幕上看起来不到 50 帧」时，先用这个脚本把
#       「Windows 到底能不能按这个速率更新光标」量出来，避免在服务端/网络上瞎猜。
#
# 原理：
#   1. 先把自己设为 PerMonitorV2 DPI 感知 —— 与 AirTouch.Server.exe 的 app.manifest 一致，
#      否则相对位移会被 DPI 虚拟化缩放，测出来的增益不是服务端看到的那个；
#   2. 发送线程按目标频率沿圆周走，两种模式：
#        abs = SetCursorPos（`moveto` 通路）
#        rel = SendInput(MOUSEEVENTF_MOVE)（`move` 通路，会经过 Windows 指针加速）
#   3. 采样线程用 GetCursorPos 高频轮询，统计**光标位置真正发生变化的次数**与间隔分布；
#   4. 同时打印主显示器的当前显示模式（分辨率 + 刷新率）以及注入总量 vs 实际位移
#      （>=1.2 说明指针加速把位移放大了，"看起来跳"往往就是这个原因）。
#
# 用法：pwsh -File tools\cursor-rate.ps1 [-Hz 144] [-Seconds 2] [-Mode abs|rel] [-Step 4]
# 注意：会真的移动鼠标指针（约 2 秒），运行期间请不要操作鼠标。

param(
    [int]$Hz = 144,
    [int]$Seconds = 2,
    [ValidateSet('abs', 'rel')]
    [string]$Mode = 'abs',
    [int]$Step = 4
)

$ErrorActionPreference = 'Stop'

$source = @'
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class AtCursorRate
{
    [StructLayout(LayoutKind.Sequential)]
    private struct Pt { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DevMode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        public short SpecVersion, DriverVersion, Size, DriverExtra;
        public int Fields;
        public int PositionX, PositionY;
        public int DisplayOrientation, DisplayFixedOutput;
        public short Color, Duplex;
        public short YResolution, TTOption, Collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string FormName;
        public short LogPixels;
        public int BitsPerPel, PelsWidth, PelsHeight, DisplayFlags, DisplayFrequency;
        public int ICMMethod, ICMIntent, MediaType, DitherType, Reserved1, Reserved2, PanningWidth, PanningHeight;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int Dx, Dy;
        public uint MouseData, Flags, Time;
        public IntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public MouseInput Mouse;
    }

    [DllImport("user32.dll")] private static extern bool GetCursorPos(out Pt p);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern uint SendInput(uint n, Input[] inputs, int size);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplaySettings(string deviceName, int modeNum, ref DevMode devMode);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern bool SetProcessDpiAwarenessContext(IntPtr context);

    private const uint InputMouse = 0;
    private const uint MouseEventMove = 0x0001;
    private static readonly IntPtr PerMonitorAwareV2 = new IntPtr(-4);

    public static string Run(int hz, int seconds, string mode, int step)
    {
        var sb = new StringBuilder();

        bool dpiOk = false;
        try { dpiOk = SetProcessDpiAwarenessContext(PerMonitorAwareV2); } catch { }
        sb.AppendLine(string.Format("PerMonitorV2 DPI awareness: {0}", dpiOk ? "set" : "NOT set (gain will be off)"));

        var dm = new DevMode();
        dm.Size = (short)Marshal.SizeOf<DevMode>();
        if (EnumDisplaySettings(null, -1 /* ENUM_CURRENT_SETTINGS */, ref dm))
        {
            sb.AppendLine(string.Format("display: {0}x{1} @ {2} Hz (bits={3})",
                dm.PelsWidth, dm.PelsHeight, dm.DisplayFrequency, dm.BitsPerPel));
        }

        int width = GetSystemMetrics(0);
        int height = GetSystemMetrics(1);
        sb.AppendLine(string.Format("primary screen (px seen by this process): {0}x{1}", width, height));

        // ---- 采样线程：统计光标位置"真正变化"的次数 ----
        var stop = new ManualResetEventSlim(false);
        long changes = 0;
        long gapSumTicks = 0;
        long maxGapTicks = 0;
        long samples = 0;
        int lastX = int.MinValue, lastY = int.MinValue;

        var sampler = new Thread(() =>
        {
            var sw = Stopwatch.StartNew();
            long lastChange = sw.ElapsedTicks;
            Pt p;
            while (!stop.IsSet)
            {
                if (!GetCursorPos(out p)) continue;
                samples++;
                if (p.X != lastX || p.Y != lastY)
                {
                    if (lastX != int.MinValue)
                    {
                        long gap = sw.ElapsedTicks - lastChange;
                        gapSumTicks += gap;
                        if (gap > maxGapTicks) maxGapTicks = gap;
                    }
                    lastX = p.X; lastY = p.Y;
                    changes++;
                    lastChange = sw.ElapsedTicks;
                }
            }
        })
        { IsBackground = true, Priority = ThreadPriority.AboveNormal };
        sampler.Start();

        // ---- 发送线程（主线程）----
        double radius = Math.Min(width, height) / 2.0 - 8;
        double cx = width / 2.0;
        double cy = height / 2.0;
        int frames = hz * seconds;
        long spf = Stopwatch.Frequency / Math.Max(1, hz);

        Pt before; GetCursorPos(out before);
        long injected = 0;
        int relX = 0, relY = 0;   // 相对模式的累计注入量
        double pathLength = 0;    // 绝对模式的目标路径长度
        double prevX = 0, prevY = 0;

        var start = Stopwatch.StartNew();
        for (int i = 0; i < frames; i++)
        {
            double angle = 2 * Math.PI * i / Math.Max(1, hz); // 一秒一圈
            if (mode == "rel")
            {
                // 相对位移：先算圆周上的目标点，再取"与上一个目标点的差"，按 step 缩放
                double tx = radius * Math.Cos(angle);
                double ty = radius * Math.Sin(angle);
                double dx = tx - prevX;
                double dy = ty - prevY;
                prevX = tx; prevY = ty;
                double norm = Math.Sqrt(dx * dx + dy * dy);
                int ix = norm < 1e-9 ? 0 : (int)Math.Round(dx / norm * step);
                int iy = norm < 1e-9 ? 0 : (int)Math.Round(dy / norm * step);
                // 保持在屏幕内：靠近边缘时反向（只需不越界，路径形状对测量不重要）
                Pt cur; GetCursorPos(out cur);
                if (cur.X + ix < 4 || cur.X + ix > width - 5) ix = -ix;
                if (cur.Y + iy < 4 || cur.Y + iy > height - 5) iy = -iy;
                var inputs = new[]
                {
                    new Input { Type = InputMouse, Mouse = new MouseInput { Dx = ix, Dy = iy, Flags = MouseEventMove } },
                };
                SendInput(1, inputs, Marshal.SizeOf<Input>());
                relX += ix; relY += iy;
                injected += (long)Math.Sqrt((double)ix * ix + (double)iy * iy);
            }
            else
            {
                int x = (int)Math.Round(cx + radius * Math.Cos(angle));
                int y = (int)Math.Round(cy + radius * Math.Sin(angle));
                SetCursorPos(x, y);
                if (i > 0) pathLength += Math.Sqrt(Math.Pow(x - prevX, 2) + Math.Pow(y - prevY, 2));
                prevX = x; prevY = y;
            }

            long target = (i + 1L) * spf;
            while (start.ElapsedTicks < target)
            {
                Thread.SpinWait(50);
            }
        }
        long elapsedTicks = start.ElapsedTicks;

        stop.Set();
        sampler.Join(1000);
        Pt after; GetCursorPos(out after);

        double elapsedSec = (double)elapsedTicks / Stopwatch.Frequency;
        double observedHz = changes / elapsedSec;
        double sampleHz = samples / elapsedSec;
        double meanGapMs = changes > 1 ? (double)gapSumTicks / (changes - 1) / Stopwatch.Frequency * 1000.0 : 0;
        double maxGapMs = (double)maxGapTicks / Stopwatch.Frequency * 1000.0;

        sb.AppendLine(string.Format("mode                : {0} ({1})", mode,
            mode == "rel" ? "SendInput MOUSEEVENTF_MOVE, step=" + step + "px" : "SetCursorPos"));
        sb.AppendLine(string.Format("requested rate      : {0} Hz ({1} frames in {2:F3} s => {3:F1} Hz actual send)",
            hz, frames, elapsedSec, frames / elapsedSec));
        sb.AppendLine(string.Format("sampler poll rate   : {0:F0} Hz", sampleHz));
        sb.AppendLine(string.Format("cursor updates seen : {0} = {1:F1} Hz", changes, observedHz));
        sb.AppendLine(string.Format("update gap          : mean {0:F2} ms, max {1:F2} ms", meanGapMs, maxGapMs));
        sb.AppendLine(string.Format("frames shown        : {0:F1}% of sent", 100.0 * changes / frames));

        if (mode == "rel")
        {
            double actual = Math.Sqrt(Math.Pow(after.X - before.X, 2) + Math.Pow(after.Y - before.Y, 2));
            sb.AppendLine(string.Format("injected distance   : {0} px (sum of deltas)", injected));
            sb.AppendLine(string.Format("net displacement    : start ({0},{1}) -> end ({2},{3}), |d| = {4:F1} px",
                before.X, before.Y, after.X, after.Y, actual));
        }
        else
        {
            sb.AppendLine(string.Format("target path length  : {0:F0} px, end ({1},{2})", pathLength, after.X, after.Y));
        }

        return sb.ToString();
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp

Write-Output (([AtCursorRate]::Run($Hz, $Seconds, $Mode, $Step)).TrimEnd())
