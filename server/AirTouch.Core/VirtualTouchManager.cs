using System.Runtime.InteropServices;

namespace AirTouch.Core;

/// <summary>
/// User-visible <b>preference</b> for how two-finger pinch is reproduced on this PC
/// (docs/PROTOCOL.md §2.1, <c>zoom_mode</c>).
/// </summary>
public enum ZoomMode
{
    /// <summary>Native touch injection when the machine can do it, wheel fallback otherwise.</summary>
    Auto,

    /// <summary>Prefer native touch. Still falls back to the wheel when there is no touch stack.</summary>
    NativeTouch,

    /// <summary>Force <c>Ctrl</c>+wheel even on a machine that has a touchscreen.</summary>
    WheelFallback,
}

/// <summary>Which backend is <b>actually in effect</b> right now — not what the user asked for.</summary>
public enum ZoomBackend
{
    NativeTouch,
    CtrlWheelFallback,
}

/// <summary>
/// Result of a touch-capability probe: what the OS reports, plus the conclusion and its evidence.
/// </summary>
/// <param name="Available">Whether <c>InjectTouchInput</c> may be used.</param>
/// <param name="Reason">
/// Human-readable Chinese explanation when <see cref="Available"/> is <c>false</c>, empty otherwise.
/// </param>
/// <param name="Digitizer">Raw <c>SM_DIGITIZER</c> (94).</param>
/// <param name="MaximumTouches">
/// Raw <c>SM_MAXIMUMTOUCHES</c> (95). <b>0 means this machine has no touch input stack at all</b>; in
/// that case <c>InitializeTouchInjection</c> is not even called, because it can return <c>true</c> on
/// such a machine and that "success" is misleading — every later <c>InjectTouchInput</c> fails with
/// <c>ERROR_INVALID_PARAMETER</c> (87).
/// </param>
/// <param name="InitializeError">Win32 error from <c>InitializeTouchInjection</c>, or 0.</param>
/// <param name="ContactCount">Contacts the injection engine was configured with (0 when unavailable).</param>
/// <param name="ProbedAt">When this probe ran; the window shows "last checked at".</param>
public sealed record TouchCapability(
    bool Available,
    string Reason,
    int Digitizer,
    int MaximumTouches,
    int InitializeError,
    int ContactCount,
    DateTimeOffset ProbedAt)
{
    /// <summary>An unavailable capability, for test doubles and for platforms without a probe.</summary>
    public static TouchCapability Unavailable(string reason) => new(false, reason, 0, 0, 0, 0, DateTimeOffset.Now);

    /// <summary>A ready capability, for test doubles that simulate "this machine has a touch stack now".</summary>
    public static TouchCapability Ready(int digitizer = 1, int maximumTouches = 10, int contactCount = 10) =>
        new(true, string.Empty, digitizer, maximumTouches, 0, contactCount, DateTimeOffset.Now);
}

/// <summary>Preference + effective backend + capability: exactly what the phone needs for its switch.</summary>
public sealed record ZoomStatus(ZoomMode Preference, ZoomBackend Backend, TouchCapability Capability);

/// <summary>Wire values of <see cref="ZoomMode"/>/<see cref="ZoomBackend"/>, frozen by docs/PROTOCOL.md.</summary>
public static class ZoomWire
{
    /// <summary>Preference as it appears on the wire: <c>auto</c> / <c>native</c> / <c>wheel</c>.</summary>
    public static string ToWire(this ZoomMode mode) => mode switch
    {
        ZoomMode.NativeTouch => "native",
        ZoomMode.WheelFallback => "wheel",
        _ => "auto",
    };

    /// <summary>Effective backend as it appears on the wire: <c>native_touch</c> / <c>ctrl_wheel_fallback</c>.</summary>
    public static string ToWire(this ZoomBackend backend) =>
        backend == ZoomBackend.NativeTouch ? "native_touch" : "ctrl_wheel_fallback";

    /// <summary>Chinese one-liner for logs and the window.</summary>
    public static string Describe(this ZoomMode mode) => mode switch
    {
        ZoomMode.NativeTouch => "优先原生触控",
        ZoomMode.WheelFallback => "强制滚轮（Ctrl+高精度滚轮）",
        _ => "自动（有触控栈就用原生）",
    };

    /// <summary>Chinese one-liner for logs and the window.</summary>
    public static string Describe(this ZoomBackend backend) =>
        backend == ZoomBackend.NativeTouch ? "原生触控注入（InjectTouchInput）" : "Ctrl+高精度滚轮回退";
}

/// <summary>
/// Owns everything about "can this PC do native touch, and what should we do instead": capability
/// probing plus the user's preference and the effective backend.
/// </summary>
/// <remarks>
/// <para>
/// This is deliberately separate from <see cref="IZoomGestureController"/>: injection is one consumer of
/// "there is a touch stack now", while detecting that stack is about the machine, not about gestures. The
/// manager is the single source of truth for both the phone (<c>zoom_status</c>) and the PC window, so
/// whoever writes last wins — changing the mode on the phone and in the window hits the same state.
/// </para>
/// <para>
/// AirTouch ships <b>no driver</b> and asks for no certificate or test mode: native touch only happens
/// when the PC already has a touch digitizer (a touchscreen laptop, or a USB / external touch display the
/// user plugged in). A successful probe in <see cref="Refresh"/> really is usable — the injection engine
/// is initialised right there, and an earlier "not available" is never cached, which is what makes "plug
/// in a touchscreen, press 重新检测, keep pinching" work without restarting the server or dropping the
/// phone.
/// </para>
/// </remarks>
public interface IVirtualTouchManager
{
    /// <summary>Most recent probe result (cached; refreshed by <see cref="Refresh"/>).</summary>
    TouchCapability Capability { get; }

    /// <summary>Preference + effective backend + capability: the payload of <c>zoom_status</c>.</summary>
    ZoomStatus Status { get; }

    /// <summary>
    /// Sets the preference, recomputes <see cref="Status"/> and raises <see cref="StatusChanged"/> when
    /// anything changed (a preference change alone counts, even if the backend stays the same).
    /// </summary>
    ZoomMode Mode { get; set; }

    /// <summary>Raised on every effective change and on every <see cref="Refresh"/>.</summary>
    event EventHandler<ZoomStatus>? StatusChanged;

    /// <summary>
    /// Re-probes the machine (a touchscreen plugged in — or a touch device enabled in Windows — later must
    /// be picked up without a restart), recomputes <see cref="Status"/> and raises
    /// <see cref="StatusChanged"/> <b>always</b> — even when nothing changed, because the user pressed a
    /// button and deserves feedback.
    /// </summary>
    ZoomStatus Refresh();
}

/// <summary>The real manager: a Win32 touch-capability probe, nothing else.</summary>
/// <remarks>
/// Measured on a Windows 11 laptop with no touchscreen: <c>SM_DIGITIZER=0</c>,
/// <c>SM_MAXIMUMTOUCHES=0</c>. <c>InitializeTouchInjection(10, TOUCH_FEEDBACK_DEFAULT)</c> still returns
/// <c>true</c> there, yet every <c>InjectTouchInput</c> fails with error 87 — which is why the probe
/// short-circuits on <c>SM_MAXIMUMTOUCHES &lt;= 0</c> and never reports a misleading "initialised OK".
/// </remarks>
public sealed class Win32VirtualTouchManager : IVirtualTouchManager
{
    /// <summary>Contacts requested from the injection engine (two are used by a pinch).</summary>
    public const int MaxContacts = 10;

    private const int SmDigitizer = 94;
    private const int SmMaximumTouches = 95;
    private const uint TouchFeedbackDefault = 0x00000001;

    private readonly IAirTouchLog _log;
    private readonly object _gate = new();

    private TouchCapability _capability;
    private ZoomMode _mode = ZoomMode.Auto;

    public Win32VirtualTouchManager(IAirTouchLog? log = null)
    {
        _log = log ?? NullLog.Instance;

        var capability = Probe();
        lock (_gate)
        {
            _capability = capability;
        }

        // 构造时就报一次结论（这是「启动时探测一次并缓存」的那一次）。
        if (capability.Available)
        {
            _log.Info(
                $"双指缩放：触控注入可用（SM_DIGITIZER={capability.Digitizer}，" +
                $"SM_MAXIMUMTOUCHES={capability.MaximumTouches}，接触点 {capability.ContactCount}）");
        }
        else if (capability.InitializeError != 0)
        {
            _log.Warn($"双指缩放：{capability.Reason}");
        }
        else
        {
            _log.Info($"双指缩放：{capability.Reason}");
        }
    }

    public TouchCapability Capability
    {
        get
        {
            lock (_gate)
            {
                return _capability;
            }
        }
    }

    public ZoomStatus Status
    {
        get
        {
            lock (_gate)
            {
                return ComputeStatus();
            }
        }
    }

    public ZoomMode Mode
    {
        get
        {
            lock (_gate)
            {
                return _mode;
            }
        }

        set
        {
            ZoomStatus status;
            lock (_gate)
            {
                if (_mode == value)
                {
                    return;
                }

                _mode = value;
                status = ComputeStatus();
            }

            _log.Info($"缩放偏好改为「{value.Describe()}」，实际后端：{status.Backend.Describe()}");
            StatusChanged?.Invoke(this, status);
        }
    }

    public event EventHandler<ZoomStatus>? StatusChanged;

    /// <inheritdoc />
    public ZoomStatus Refresh()
    {
        var capability = Probe();
        ZoomStatus status;
        lock (_gate)
        {
            // 刻意不保留旧结论：上次不可用不代表这次不可用（用户可能刚插上外接触控屏，或刚在系统里
            // 启用了一台触摸设备）。
            _capability = capability;
            status = ComputeStatus();
        }

        _log.Info(
            $"触控能力重新探测：SM_DIGITIZER={capability.Digitizer}，SM_MAXIMUMTOUCHES={capability.MaximumTouches}，" +
            $"可用={capability.Available}，实际后端={status.Backend.Describe()}");

        StatusChanged?.Invoke(this, status);
        return status;
    }

    /// <summary>
    /// The probe itself: metrics first, <c>InitializeTouchInjection</c> only when the machine claims to
    /// have simultaneous contacts.
    /// </summary>
    private TouchCapability Probe()
    {
        int digitizer;
        int maximumTouches;

        try
        {
            digitizer = GetSystemMetrics(SmDigitizer);
            maximumTouches = GetSystemMetrics(SmMaximumTouches);
        }
        catch (Exception ex)
        {
            return TouchCapability.Unavailable($"无法查询触摸能力（{ex.GetType().Name}），双指缩放改用 Ctrl+高精度滚轮");
        }

        if (maximumTouches <= 0)
        {
            return new TouchCapability(
                false,
                $"本机没有触摸数字化仪（SM_MAXIMUMTOUCHES={maximumTouches}，SM_DIGITIZER={digitizer}），" +
                "双指缩放改用 Ctrl+高精度滚轮",
                digitizer,
                maximumTouches,
                0,
                0,
                DateTimeOffset.Now);
        }

        if (!InitializeTouchInjection(MaxContacts, TouchFeedbackDefault))
        {
            var error = Marshal.GetLastWin32Error();
            return new TouchCapability(
                false,
                $"触控注入初始化失败（InitializeTouchInjection 错误码 {error}），双指缩放改用 Ctrl+高精度滚轮",
                digitizer,
                maximumTouches,
                error,
                0,
                DateTimeOffset.Now);
        }

        // 初始化在这里完成（唯一一处），所以 Available=true 是「真的能用」，而不是「函数返回了 true」。
        return new TouchCapability(true, string.Empty, digitizer, maximumTouches, 0, MaxContacts, DateTimeOffset.Now);
    }

    /// <summary>Preference + capability → backend. Must be called with <see cref="_gate"/> held.</summary>
    private ZoomStatus ComputeStatus() => new(
        _mode,
        _mode == ZoomMode.WheelFallback || !_capability.Available
            ? ZoomBackend.CtrlWheelFallback
            : ZoomBackend.NativeTouch,
        _capability);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool InitializeTouchInjection(uint maxCount, uint feedbackMode);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);
}

/// <summary>
/// Test double for <see cref="IVirtualTouchManager"/>: programmable capability (to simulate "this machine
/// just gained a touch stack"), with every <c>Refresh</c> and every mode change recorded.
/// </summary>
public sealed class RecordingVirtualTouchManager : IVirtualTouchManager
{
    private readonly List<string> _events = new();
    private readonly object _gate = new();

    private TouchCapability _capability;
    private ZoomMode _mode = ZoomMode.Auto;

    public RecordingVirtualTouchManager(TouchCapability? capability = null)
    {
        _capability = capability ?? TouchCapability.Unavailable("记录型虚拟触摸管理器：当前不可用");
    }

    /// <summary>Everything this double was asked to do, in order.</summary>
    public IReadOnlyList<string> Events
    {
        get
        {
            lock (_gate)
            {
                return _events.ToArray();
            }
        }
    }

    /// <summary>How many times <see cref="Refresh"/> ran.</summary>
    public int RefreshCount { get; private set; }

    public TouchCapability Capability
    {
        get
        {
            lock (_gate)
            {
                return _capability;
            }
        }
    }

    public ZoomStatus Status
    {
        get
        {
            lock (_gate)
            {
                return ComputeStatus();
            }
        }
    }

    public ZoomMode Mode
    {
        get
        {
            lock (_gate)
            {
                return _mode;
            }
        }

        set
        {
            ZoomStatus status;
            lock (_gate)
            {
                if (_mode == value)
                {
                    return;
                }

                _mode = value;
                _events.Add("mode " + value.ToWire());
                status = ComputeStatus();
            }

            StatusChanged?.Invoke(this, status);
        }
    }

    public event EventHandler<ZoomStatus>? StatusChanged;

    /// <summary>
    /// Simulates the machine gaining (or losing) a touch stack — e.g. a USB touchscreen was just plugged
    /// in, which is exactly what <see cref="Refresh"/> then picks up.
    /// </summary>
    public void SimulateCapability(TouchCapability capability)
    {
        lock (_gate)
        {
            _capability = capability;
        }
    }

    /// <inheritdoc />
    public ZoomStatus Refresh()
    {
        ZoomStatus status;
        lock (_gate)
        {
            RefreshCount++;
            _events.Add($"refresh {RefreshCount}");
            status = ComputeStatus();
        }

        StatusChanged?.Invoke(this, status);
        return status;
    }

    private ZoomStatus ComputeStatus() => new(
        _mode,
        _mode == ZoomMode.WheelFallback || !_capability.Available
            ? ZoomBackend.CtrlWheelFallback
            : ZoomBackend.NativeTouch,
        _capability);
}
