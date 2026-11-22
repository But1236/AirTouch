using System.Globalization;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace AirTouch.Core;

/// <summary>
/// Abstraction over the two-finger pinch gesture (docs/PROTOCOL.md §2.1, <c>touch</c> frames) so the
/// protocol can be exercised without injecting real touch contacts.
/// </summary>
/// <remarks>
/// <para>
/// The client only ever sends <em>geometry</em> — "two fingers, this much wider than when the gesture
/// started, moved this far" — and the server decides how to reproduce it on Windows: real touch
/// injection when the machine has a touch stack, Ctrl + high-resolution wheel when it does not. That
/// choice is invisible on the wire, so a phone never has to know which desktop it is driving; it can
/// only express a <em>preference</em> (<c>zoom_mode</c>) and read back what is in effect
/// (<c>zoom_status</c>).
/// </para>
/// <para>
/// <see cref="TouchMove"/>'s scale is relative (current span ÷ the span at <c>down</c>), so nothing here
/// depends on the phone's screen size or density.
/// </para>
/// </remarks>
public interface IZoomGestureController
{
    /// <summary><c>true</c> when this machine can inject real touch contacts.</summary>
    bool Available { get; }

    /// <summary>
    /// Short Chinese sentence naming the fallback in use, empty when <see cref="Available"/> is
    /// <c>true</c>. Shown in the server window and in <c>zoom_status.reason</c>.
    /// </summary>
    string UnavailableReason { get; }

    /// <summary>Gesture start: places the two virtual contacts at the current cursor position.</summary>
    void TouchBegin();

    /// <summary>
    /// One geometry update. <paramref name="scale"/> is relative to the gesture start;
    /// <paramref name="dx"/>/<paramref name="dy"/> are the gesture centre's movement since the previous
    /// frame, in screen pixels.
    /// </summary>
    /// <remarks>
    /// The wheel fallback uses <paramref name="scale"/> only: a pinch must not drag the user's cursor
    /// around, and the phone already has <c>scroll</c> frames for two-finger panning.
    /// </remarks>
    void TouchMove(double scale, int dx, int dy);

    /// <summary>Gesture end: lifts both contacts.</summary>
    void TouchEnd();

    /// <summary>
    /// Drops whatever this controller is still holding: virtual contacts for the touch backend, a
    /// latched Ctrl for the wheel fallback.
    /// </summary>
    /// <remarks>
    /// Called when a session ends (disconnect, <c>reset</c> of the security state, server stop, socket
    /// replacement) <b>and</b> before a backend switch: a stuck contact or a stuck Ctrl leaves the
    /// desktop unusable, exactly like the stuck mouse buttons and keys released by their own controllers.
    /// </remarks>
    void ReleaseAll();
}

/// <summary>One virtual contact, in physical screen pixels.</summary>
public readonly record struct TouchContact(int X, int Y);

/// <summary>What a frame of touch contacts means; maps onto the <c>POINTER_FLAG_*</c> state machine.</summary>
public enum TouchContactAction
{
    /// <summary>Both fingers touch down (<c>INRANGE|INCONTACT|DOWN</c>).</summary>
    Down,

    /// <summary>Both fingers move (<c>INRANGE|INCONTACT|UPDATE</c>).</summary>
    Move,

    /// <summary>Both fingers lift (<c>INRANGE|UP</c>) — coordinates must equal the last frame's.</summary>
    Up,

    /// <summary>Abort the contact regardless of position (<c>CANCELED|UPDATE</c>).</summary>
    Cancel,
}

/// <summary>
/// The raw "hand this frame of contacts to Windows" layer, split out of the gesture state machine so the
/// self-test can drive the real geometry/state code on a machine that has no touch stack (which this one,
/// like most desktops, does not).
/// </summary>
public interface ITouchContactInjector
{
    /// <summary>
    /// Sends one frame. Returns <c>false</c> when the OS refused it, with the Win32 error in
    /// <paramref name="error"/> (0 on success).
    /// </summary>
    bool Inject(TouchContactAction action, IReadOnlyList<TouchContact> contacts, out int error);
}

/// <summary>Real <c>InjectTouchInput</c> calls.</summary>
/// <remarks>
/// <para>
/// The struct layouts are size-checked against the C headers by <see cref="MarshalledStructSizes"/>
/// (96 / 144 / 152), which the self-test asserts — a marshalling typo is the one error class that cannot
/// be discovered on a machine without a touch stack.
/// </para>
/// <para>
/// The state machine is unforgiving: a <c>DOWN</c> frame is <c>INRANGE|INCONTACT|DOWN</c>, every later
/// frame <c>INRANGE|INCONTACT|UPDATE</c>, and the closing frame <c>INRANGE|UP</c> with
/// <c>ptPixelLocation</c> <b>identical to the last UPDATE</b> — any difference makes
/// <c>InjectTouchInput</c> return <c>ERROR_INVALID_PARAMETER</c> and cancel every contact.
/// <c>dwTime</c>/<c>PerformanceCount</c> are left at 0 so the system assigns its own timestamps (a custom
/// timestamp must increase strictly and can trip <c>ERROR_NOT_READY</c>).
/// </para>
/// <para>
/// <c>InitializeTouchInjection</c> is deliberately <b>not</b> called here: <see cref="IVirtualTouchManager"/>
/// owns it, so "initialised" and "reported available" can never disagree.
/// </para>
/// </remarks>
public sealed class Win32TouchContactInjector : ITouchContactInjector
{
    private const int PointerTypeTouch = 2;
    private const int ContactHalfSizePx = 4;
    private const uint TouchMaskContactArea = 0x00000001;
    private const uint TouchMaskOrientation = 0x00000002;
    private const uint TouchMaskPressure = 0x00000004;
    private const uint PointerFlagInRange = 0x00000002;
    private const uint PointerFlagInContact = 0x00000004;
    private const uint PointerFlagCanceled = 0x00000800;
    private const uint PointerFlagDown = 0x00010000;
    private const uint PointerFlagUpdate = 0x00020000;
    private const uint PointerFlagUp = 0x00040000;

    private uint _frameId;

    public bool Inject(TouchContactAction action, IReadOnlyList<TouchContact> contacts, out int error)
    {
        // frameId 同一帧内所有接触点相同、跨帧递增；只增不减，取消/抬起帧之后也不会倒回去。
        _frameId++;

        var frame = new POINTER_TYPE_INFO[contacts.Count];
        for (var index = 0; index < contacts.Count; index++)
        {
            frame[index] = MakeContact((uint)index, contacts[index].X, contacts[index].Y, FlagsFor(action));
        }

        if (frame.Length > 0 && InjectTouchInput((uint)frame.Length, frame))
        {
            error = 0;
            return true;
        }

        error = Marshal.GetLastWin32Error();
        return false;
    }

    /// <summary>
    /// Marshalled sizes of the injection structs, asserted by the self-test against the C headers.
    /// </summary>
    public static (int PointerInfo, int TouchInfo, int TypeInfo) MarshalledStructSizes() =>
        (Marshal.SizeOf<POINTER_INFO>(), Marshal.SizeOf<POINTER_TOUCH_INFO>(), Marshal.SizeOf<POINTER_TYPE_INFO>());

    private static uint FlagsFor(TouchContactAction action) => action switch
    {
        TouchContactAction.Down => PointerFlagInContact | PointerFlagDown,
        TouchContactAction.Move => PointerFlagInContact | PointerFlagUpdate,
        TouchContactAction.Up => PointerFlagUp,
        _ => PointerFlagInContact | PointerFlagCanceled | PointerFlagUpdate,
    };

    private POINTER_TYPE_INFO MakeContact(uint pointerId, int x, int y, uint contactFlags)
    {
        var location = new POINT { x = x, y = y };
        var contact = new RECT
        {
            Left = x - ContactHalfSizePx,
            Top = y - ContactHalfSizePx,
            Right = x + ContactHalfSizePx,
            Bottom = y + ContactHalfSizePx,
        };

        return new POINTER_TYPE_INFO
        {
            type = PointerTypeTouch,
            touchInfo = new POINTER_TOUCH_INFO
            {
                pointerInfo = new POINTER_INFO
                {
                    pointerType = PointerTypeTouch,
                    pointerId = pointerId,
                    frameId = _frameId,
                    pointerFlags = PointerFlagInRange | contactFlags,
                    ptPixelLocation = location,
                    // dwTime / PerformanceCount 保持 0：让系统自己分配时间戳，见类型注释。
                },
                touchFlags = 0,
                touchMask = TouchMaskContactArea | TouchMaskOrientation | TouchMaskPressure,
                rcContact = contact,
                rcContactRaw = contact,
                orientation = 90,
                pressure = 32000,
            },
        };
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool InjectTouchInput(uint count, [In] POINTER_TYPE_INFO[] contacts);

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int x;
        public int y;
    }

    /// <summary>96 bytes; layout copied from <c>winuser.h</c> and size-checked by the self-test.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct POINTER_INFO
    {
        public int pointerType;
        public uint pointerId;
        public uint frameId;
        public uint pointerFlags;
        public IntPtr sourceDevice;
        public IntPtr hwndTarget;
        public POINT ptPixelLocation;
        public POINT ptHimetricLocation;
        public POINT ptPixelLocationRaw;
        public POINT ptHimetricLocationRaw;
        public uint dwTime;
        public uint historyCount;
        public int InputData;
        public uint dwKeyStates;
        public ulong PerformanceCount;
        public int ButtonChangeType;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    /// <summary>144 bytes: <c>POINTER_INFO</c> + the touch contact description.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct POINTER_TOUCH_INFO
    {
        public POINTER_INFO pointerInfo;
        public uint touchFlags;
        public uint touchMask;
        public RECT rcContact;
        public RECT rcContactRaw;
        public uint orientation;
        public uint pressure;
    }

    /// <summary>152 bytes: the tagged union <c>InjectTouchInput</c> expects.</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct POINTER_TYPE_INFO
    {
        public int type;
        public POINTER_TOUCH_INFO touchInfo;
    }
}

/// <summary>
/// Test double for <see cref="ITouchContactInjector"/>: records each frame (action + both contact
/// positions) instead of injecting it.
/// </summary>
public sealed class RecordingTouchInjector : ITouchContactInjector
{
    private readonly List<string> _events = new();

    public IReadOnlyList<string> Events
    {
        get
        {
            lock (_events)
            {
                return _events.ToArray();
            }
        }
    }

    /// <summary>When non-zero, every frame fails with this Win32 error instead of succeeding.</summary>
    public int FailWithError { get; set; }

    public long InjectionCount { get; private set; }

    public bool Inject(TouchContactAction action, IReadOnlyList<TouchContact> contacts, out int error)
    {
        lock (_events)
        {
            InjectionCount++;

            if (FailWithError != 0)
            {
                error = FailWithError;
                _events.Add($"{Verb(action)} failed {FailWithError}");
                return false;
            }

            error = 0;
            _events.Add(string.Format(
                CultureInfo.InvariantCulture,
                "{0} {1}",
                Verb(action),
                string.Join("-", contacts.Select(c => $"({c.X},{c.Y})"))));
            return true;
        }
    }

    private static string Verb(TouchContactAction action) => action switch
    {
        TouchContactAction.Down => "touch down",
        TouchContactAction.Move => "touch move",
        TouchContactAction.Up => "touch up",
        _ => "touch cancel",
    };
}

/// <summary>
/// Two-finger pinch on Windows: real touch injection when this machine can do it, Ctrl + high-resolution
/// wheel when it cannot — with the backend re-evaluated on every gesture, so a touchscreen plugged in
/// while the server is running switches it live.
/// </summary>
/// <remarks>
/// <para>
/// <b>Why the fallback exists.</b> Measured on a Windows 11 laptop with no touchscreen:
/// <c>InitializeTouchInjection(10, TOUCH_FEEDBACK_DEFAULT)</c> returns <c>true</c>, but every
/// <c>InjectTouchInput</c> then fails with <c>GetLastError() = 87</c> (<c>ERROR_INVALID_PARAMETER</c>),
/// and <c>GetSystemMetrics(SM_MAXIMUMTOUCHES) = 0</c> says why: there is no touch input stack to inject
/// into. Probing lives in <see cref="IVirtualTouchManager"/>, and AirTouch ships no driver of its own —
/// it only ever uses touch hardware the PC already has.
/// </para>
/// <para>
/// <b>Backend switching.</b> The controller subscribes to
/// <see cref="IVirtualTouchManager.StatusChanged"/>; when the <em>effective backend</em> changes it first
/// releases everything the old backend holds (contacts, Ctrl) and only then adopts the new one. A
/// gesture that was in flight is deliberately <em>not</em> migrated: the following <c>move</c> frames are
/// ignored until the phone sends a new <c>down</c>, because silently continuing a pinch on a different
/// API would leave a contact — or a Ctrl — that nothing will ever lift.
/// </para>
/// </remarks>
public sealed class Win32ZoomGestureController : IZoomGestureController
{
    /// <summary>Distance between the two virtual contacts at <c>down</c>, in pixels.</summary>
    public const int BaseSpanPx = 240;

    private readonly IAirTouchLog _log;
    private readonly IVirtualTouchManager _touchManager;
    private readonly TouchGestureBackend _touch;
    private readonly WheelFallbackBackend _wheel;
    private readonly object _gate = new();

    private ZoomBackend _backend;
    private bool _gestureAborted;
    private int _wheelAnnounced;
    private int _touchAnnounced;

    /// <param name="mouseController">Cursor position (touch backend) and wheel (fallback).</param>
    /// <param name="keyboardController">The temporary Ctrl of the fallback backend.</param>
    /// <param name="log">Sink for probe conclusions, backend switches and injection failures.</param>
    /// <param name="touchManager">
    /// Capability/preference source. Defaults to a real <see cref="Win32VirtualTouchManager"/>, which
    /// probes once here; the server passes the same instance it exposes in its status.
    /// </param>
    /// <param name="touchInjector">
    /// Raw contact injection; defaults to <see cref="Win32TouchContactInjector"/>. The self-test passes a
    /// recording double so the real gesture state machine can run on a machine without a touch stack.
    /// </param>
    public Win32ZoomGestureController(
        IMouseController mouseController,
        IKeyboardController keyboardController,
        IAirTouchLog? log = null,
        IVirtualTouchManager? touchManager = null,
        ITouchContactInjector? touchInjector = null)
    {
        _log = log ?? NullLog.Instance;
        _touchManager = touchManager ?? new Win32VirtualTouchManager(_log);
        _touch = new TouchGestureBackend(mouseController, touchInjector ?? new Win32TouchContactInjector(), _log);
        _wheel = new WheelFallbackBackend(mouseController, keyboardController, _log);
        _backend = _touchManager.Status.Backend;

        _log.Info($"双指缩放当前后端：{_backend.Describe()}（偏好「{_touchManager.Mode.Describe()}」）");

        // 先订阅（本类在服务端之前构造），保证切换时「先收手势、再换后端」早于对外的状态推送。
        _touchManager.StatusChanged += OnTouchManagerStatusChanged;
    }

    /// <summary>Preference + effective backend + capability, forwarded from the manager.</summary>
    public ZoomStatus Status => _touchManager.Status;

    public bool Available => _touchManager.Status.Capability.Available;

    public string UnavailableReason => _touchManager.Status.Capability.Reason;

    /// <summary>Contact frames the OS refused; always 0 while the fallback backend is in use.</summary>
    public long TouchInjectionFailures => _touch.InjectionFailures;

    /// <summary>Which backend the next gesture will use (the one currently in effect).</summary>
    public ZoomBackend Backend
    {
        get
        {
            lock (_gate)
            {
                return _backend;
            }
        }
    }

    public void TouchBegin()
    {
        // 每次手势都按**当前** Status 选路，而不是构造时的快照：热切换靠的就是这一行。
        var backend = _touchManager.Status.Backend;
        lock (_gate)
        {
            _backend = backend;
            _gestureAborted = false;
        }

        Announce(backend);

        if (backend == ZoomBackend.NativeTouch)
        {
            _touch.Begin();
        }
        else
        {
            _wheel.Begin();
        }
    }

    public void TouchMove(double scale, int dx, int dy)
    {
        ZoomBackend backend;
        lock (_gate)
        {
            // 后端切换时进行中的手势被主动收尾了，后续 move 一律忽略，直到客户端重新发 down。
            if (_gestureAborted)
            {
                return;
            }

            backend = _backend;
        }

        if (backend == ZoomBackend.NativeTouch)
        {
            _touch.Move(scale, dx, dy);
        }
        else
        {
            _wheel.Move(scale, dx, dy);
        }
    }

    public void TouchEnd()
    {
        ZoomBackend backend;
        lock (_gate)
        {
            backend = _backend;
        }

        if (backend == ZoomBackend.NativeTouch)
        {
            _touch.End();
        }
        else
        {
            _wheel.End();
        }
    }

    public void ReleaseAll()
    {
        // 两条后端都收：这个控制器可能刚切换过，谁手里还留着东西就收谁。
        _touch.ReleaseAll();
        _wheel.ReleaseAll();

        lock (_gate)
        {
            _gestureAborted = false;
        }
    }

    /// <summary>
    /// Backend switch: release first, adopt second, and mark the in-flight gesture as aborted.
    /// </summary>
    private void OnTouchManagerStatusChanged(object? sender, ZoomStatus status)
    {
        ZoomBackend previous;
        bool wasActive;

        lock (_gate)
        {
            if (status.Backend == _backend)
            {
                // 只是偏好变了（例如 auto → native 但本机没有触控栈）：不必打扰正在进行的手势。
                return;
            }

            previous = _backend;
            wasActive = _touch.IsActive || _wheel.IsActive;
            _backend = status.Backend;
            _gestureAborted = wasActive;
        }

        // 顺序不能反：先把旧后端收干净（抬触点 / 抬 Ctrl），再让后续帧走新后端。
        _touch.ReleaseAll();
        _wheel.ReleaseAll();

        _log.Info(
            $"双指缩放后端热切换：{previous.Describe()} → {status.Backend.Describe()}" +
            (wasActive ? "（进行中的手势已收尾，客户端需要重新发 down）" : string.Empty));
    }

    private void Announce(ZoomBackend backend)
    {
        if (backend == ZoomBackend.NativeTouch)
        {
            if (Interlocked.Exchange(ref _touchAnnounced, 1) == 0)
            {
                _log.Info("双指缩放使用原生触控注入（InjectTouchInput）");
            }

            return;
        }

        if (Interlocked.Exchange(ref _wheelAnnounced, 1) != 0)
        {
            return;
        }

        var capability = _touchManager.Status.Capability;
        if (capability.Available)
        {
            // 有触控栈却选了滚轮 = 用户偏好，不是异常。
            _log.Info("双指缩放按偏好使用 Ctrl+高精度滚轮（本机触控注入可用，但偏好为「强制滚轮」）");
        }
        else
        {
            // 每帧都回退会让日志变成噪音，所以只提醒第一次 —— 但一定要提醒：用户看到「捏合只有
            // Ctrl+滚轮的效果」时，得能在窗口日志里找到原因。
            _log.Warn($"双指缩放回退到 Ctrl+高精度滚轮：{capability.Reason}");
        }
    }

    /// <summary>
    /// The touch path: a gesture state machine (centre, span, screen clamping) over
    /// <see cref="ITouchContactInjector"/>.
    /// </summary>
    private sealed class TouchGestureBackend
    {
        private readonly IMouseController _mouse;
        private readonly ITouchContactInjector _injector;
        private readonly IAirTouchLog _log;
        private readonly object _gate = new();

        private bool _active;
        private int _centerX;
        private int _centerY;
        private int _leftX;
        private int _leftY;
        private int _rightX;
        private int _rightY;
        private long _failures;

        public TouchGestureBackend(IMouseController mouse, ITouchContactInjector injector, IAirTouchLog log)
        {
            _mouse = mouse;
            _injector = injector;
            _log = log;
        }

        public bool IsActive
        {
            get
            {
                lock (_gate)
                {
                    return _active;
                }
            }
        }

        public long InjectionFailures => Interlocked.Read(ref _failures);

        public void Begin()
        {
            var (cursorX, cursorY) = _mouse.GetCursorPosition();

            lock (_gate)
            {
                // 上一轮手势的 up 丢了（客户端被杀、手势帧被丢弃）又来了一个 down：必须先把已经按着的
                // 触点收掉，否则同一个 pointerId 会被第二次 DOWN —— 那正是 ERROR_INVALID_PARAMETER。
                if (_active)
                {
                    _log.Warn("收到新的捏合起始帧，但上一轮手势没有结束：先收起旧触点再开始新手势");
                    Send(TouchContactAction.Up);
                }

                _active = true;
                _centerX = cursorX;
                _centerY = cursorY;
                PlaceContacts(BaseSpanPx / 2.0);
                Send(TouchContactAction.Down);
            }
        }

        public void Move(double scale, int dx, int dy)
        {
            lock (_gate)
            {
                // down 才是手势的开始（docs/PROTOCOL.md §2.1）：没有起始帧的 move 直接忽略，免得凭空
                // 造出一对没人按下的触点。
                if (!_active)
                {
                    return;
                }

                _centerX += Math.Clamp(dx, -Win32MouseController.MaxStepPixels, Win32MouseController.MaxStepPixels);
                _centerY += Math.Clamp(dy, -Win32MouseController.MaxStepPixels, Win32MouseController.MaxStepPixels);

                var halfSpan = BaseSpanPx * Math.Clamp(scale, 0.05, 20.0) / 2.0;
                PlaceContacts(halfSpan);
                Send(TouchContactAction.Move);
            }
        }

        public void End()
        {
            lock (_gate)
            {
                if (!_active)
                {
                    return;
                }

                // 结束帧的坐标必须与最后一帧完全相同 —— 这里刻意不重算，直接用上次的坐标。
                Send(TouchContactAction.Up);
                _active = false;
            }
        }

        public void ReleaseAll()
        {
            lock (_gate)
            {
                if (!_active)
                {
                    return;
                }

                _log.Warn("会话结束，正在收起仍按着的虚拟触点（避免桌面停留在双指按住状态）");
                if (!Send(TouchContactAction.Up))
                {
                    // 兜底：抬不起来就直接取消这两个接触点。
                    Send(TouchContactAction.Cancel);
                }

                _active = false;
            }
        }

        /// <summary>
        /// Recomputes both contacts from the centre and the half span, clamped to the primary screen —
        /// an off-screen contact is rejected with <c>ERROR_INVALID_PARAMETER</c>.
        /// </summary>
        private void PlaceContacts(double halfSpan)
        {
            var bounds = Screen.PrimaryScreen?.Bounds ?? new System.Drawing.Rectangle(0, 0, 1024, 768);
            var minX = bounds.Left + 1;
            var maxX = bounds.Right - 2;
            var minY = bounds.Top + 1;
            var maxY = bounds.Bottom - 2;

            if (maxX < minX)
            {
                maxX = minX;
            }

            if (maxY < minY)
            {
                maxY = minY;
            }

            var centerX = Math.Clamp(_centerX, minX, maxX);
            var centerY = Math.Clamp(_centerY, minY, maxY);

            _leftX = Math.Clamp((int)Math.Round(centerX - halfSpan), minX, maxX);
            _rightX = Math.Clamp((int)Math.Round(centerX + halfSpan), minX, maxX);
            _leftY = centerY;
            _rightY = centerY;
        }

        private bool Send(TouchContactAction action)
        {
            var contacts = new[] { new TouchContact(_leftX, _leftY), new TouchContact(_rightX, _rightY) };
            if (_injector.Inject(action, contacts, out var error))
            {
                return true;
            }

            Interlocked.Increment(ref _failures);
            _log.Error($"触控注入失败（{action}，错误码 {error}）");
            return false;
        }
    }

    /// <summary>
    /// Fallback for machines without a touch stack: the relative change of the finger span is turned
    /// into high-resolution wheel units, injected as one <c>Ctrl down → wheel → Ctrl up</c> group.
    /// </summary>
    /// <remarks>
    /// <para>
    /// <b>Conversion.</b> A span change of <see cref="SpanRatioPerNotch"/> (12%) is one notch, i.e. 120
    /// units — a constant <em>relative</em> change, so a pinch feels the same at any zoom level:
    /// <c>units = ln(scale / previousScale) / ln(1.12) * 120</c>. The logarithm is used instead of a
    /// linear approximation because the input is a ratio: a linear formula would make identical gestures
    /// produce wildly different amounts at 0.2× and at 5×.
    /// </para>
    /// <para>
    /// <b>Rate limit.</b> One frame emits at most <see cref="MaxUnitsPerFrame"/> units (one notch); the
    /// remainder is carried in units space until later frames, so a fast pinch still arrives completely
    /// instead of jumping.
    /// </para>
    /// <para>
    /// <b>Ctrl is never held.</b> Every injection is a self-contained group: Ctrl down, one wheel event,
    /// Ctrl up, with the release in a <c>finally</c> so no interruption — least of all a dead socket or a
    /// backend switch — can leave a physically stuck Ctrl on the user's desktop.
    /// </para>
    /// </remarks>
    private sealed class WheelFallbackBackend
    {
        /// <summary>Span ratio that equals one wheel notch (12% wider or narrower).</summary>
        public const double SpanRatioPerNotch = 1.12;

        /// <summary>One notch per frame at most, so a fast pinch does not jump.</summary>
        public const int MaxUnitsPerFrame = Win32MouseController.WheelDelta;

        /// <summary>Ceiling for the carried remainder, in units (120 notches).</summary>
        public const int MaxResidualUnits = Win32MouseController.MaxScrollUnits;

        /// <summary>VK_LCONTROL.</summary>
        private const int LeftControlVk = 0xA2;

        private readonly IMouseController _mouse;
        private readonly IKeyboardController _keyboard;
        private readonly IAirTouchLog _log;
        private readonly object _gate = new();

        private bool _active;
        private bool _ctrlDown;
        private double _emittedScale = 1.0;
        private double _residualUnits;

        public WheelFallbackBackend(IMouseController mouse, IKeyboardController keyboard, IAirTouchLog log)
        {
            _mouse = mouse;
            _keyboard = keyboard;
            _log = log;
        }

        public bool IsActive
        {
            get
            {
                lock (_gate)
                {
                    return _active;
                }
            }
        }

        /// <summary>
        /// Exact wheel units for a span change, before the per-frame rate limit:
        /// <c>ln(scale / previousScale) / ln(1.12) * 120</c>.
        /// </summary>
        public static double UnitsForScaleStep(double scale, double previousScale)
        {
            if (!double.IsFinite(scale) || !double.IsFinite(previousScale) || scale <= 0 || previousScale <= 0)
            {
                return 0;
            }

            return Math.Log(scale / previousScale) / Math.Log(SpanRatioPerNotch) * Win32MouseController.WheelDelta;
        }

        public void Begin()
        {
            lock (_gate)
            {
                _active = true;
                _emittedScale = 1.0;
                _residualUnits = 0;
            }
        }

        public void Move(double scale, int dx, int dy)
        {
            if (!double.IsFinite(scale) || scale <= 0)
            {
                return;
            }

            int units;
            lock (_gate)
            {
                if (!_active)
                {
                    // 没有 down 就直接 move 的帧：把当前 scale 当作新的基准，别凭空滚一大段。
                    _active = true;
                    _emittedScale = scale;
                    _residualUnits = 0;
                    return;
                }

                _residualUnits = Math.Clamp(
                    _residualUnits + UnitsForScaleStep(scale, _emittedScale),
                    -MaxResidualUnits,
                    MaxResidualUnits);
                _emittedScale = scale;

                units = (int)Math.Round(
                    Math.Clamp(_residualUnits, -MaxUnitsPerFrame, MaxUnitsPerFrame),
                    MidpointRounding.AwayFromZero);
                _residualUnits -= units;
            }

            if (units != 0)
            {
                InjectCtrlWheel(units);
            }
        }

        public void End()
        {
            lock (_gate)
            {
                _active = false;
                _emittedScale = 1.0;
                _residualUnits = 0;
            }
        }

        public void ReleaseAll()
        {
            lock (_gate)
            {
                _active = false;
                _emittedScale = 1.0;
                _residualUnits = 0;

                if (_ctrlDown)
                {
                    _keyboard.Key(LeftControlVk, down: false);
                    _ctrlDown = false;
                    _log.Warn("会话结束，已抬起缩放缓存的 Ctrl（避免电脑端留下卡住的 Ctrl）");
                }
            }
        }

        private void InjectCtrlWheel(int units)
        {
            lock (_gate)
            {
                _ctrlDown = true;
            }

            try
            {
                // 一组自成一体的注入：Ctrl 按下、滚一下、立刻抬起。绝不能长时间按住 —— 手机侧任何中断
                // 都不该在电脑上留下卡住的 Ctrl，否则用户之后每次输入都变成快捷键。
                _keyboard.Key(LeftControlVk, down: true);
                _mouse.Scroll(units, ScrollAxis.Vertical);
            }
            finally
            {
                _keyboard.Key(LeftControlVk, down: false);
                lock (_gate)
                {
                    _ctrlDown = false;
                }
            }
        }
    }
}

/// <summary>Test double: records the gesture geometry instead of injecting anything.</summary>
/// <remarks>
/// It does <em>not</em> reimplement either backend on purpose: both are covered by driving the real
/// <see cref="Win32ZoomGestureController"/> with a recording manager and recording controllers, so the
/// code under test is the shipped code.
/// </remarks>
public sealed class RecordingZoomGestureController : IZoomGestureController
{
    private readonly List<string> _events = new();
    private bool _active;

    public RecordingZoomGestureController(bool available = false, string? unavailableReason = null)
    {
        Available = available;
        UnavailableReason = available ? string.Empty : unavailableReason ?? "记录型控制器（不注入）";
    }

    public IReadOnlyList<string> Events
    {
        get
        {
            lock (_events)
            {
                return _events.ToArray();
            }
        }
    }

    public bool Available { get; }

    public string UnavailableReason { get; }

    public void TouchBegin()
    {
        lock (_events)
        {
            _active = true;
            _events.Add("touch down");
        }
    }

    public void TouchMove(double scale, int dx, int dy)
    {
        lock (_events)
        {
            // 固定小数点格式：断言里的字符串不能随当前区域设置变化（逗号小数点会让断言在部分机器上挂掉）。
            _events.Add(string.Format(
                CultureInfo.InvariantCulture,
                "touch move scale={0:0.###} dx={1} dy={2}",
                scale,
                dx,
                dy));
        }
    }

    public void TouchEnd()
    {
        lock (_events)
        {
            _active = false;
            _events.Add("touch up");
        }
    }

    public void ReleaseAll()
    {
        lock (_events)
        {
            // 与真实控制器一致：没有进行中的手势就是空操作，连事件都不记 —— 否则每条断线路径都会多出
            // 一条 release-all，事件下标断言就全乱了。
            if (!_active)
            {
                return;
            }

            _active = false;
            _events.Add("release-all");
        }
    }
}
