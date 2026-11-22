using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace AirTouch.Core;

public enum MouseButton
{
    Left,
    Right,
}

/// <summary>
/// Which wheel a <c>scroll</c> frame feeds (docs/PROTOCOL.md §2.1): the vertical wheel
/// (<c>MOUSEEVENTF_WHEEL</c>) or the horizontal one (<c>MOUSEEVENTF_HWHEEL</c>).
/// </summary>
public enum ScrollAxis
{
    Vertical,
    Horizontal,
}

/// <summary>
/// Abstraction over cursor control so the protocol can be exercised without moving a real cursor.
/// </summary>
public interface IMouseController
{
    void Move(int dx, int dy);

    void Click(MouseButton button);

    /// <summary>
    /// Injects one mouse-button state transition (docs/PROTOCOL.md §2.1), which is what lets a stream of
    /// <c>move</c> frames drag instead of merely clicking.
    /// </summary>
    /// <remarks>
    /// One call = one transition, never a whole press/release pair: the client owns the timing and must
    /// send the matching release, exactly like <see cref="IKeyboardController.Key"/>. A duplicate press
    /// or a release of a button that is not pressed is a no-op.
    /// </remarks>
    /// <param name="button">Which button changes state.</param>
    /// <param name="down"><c>true</c> presses, <c>false</c> releases.</param>
    void Button(MouseButton button, bool down);

    /// <summary>Releases every mouse button this controller still holds down and forgets them.</summary>
    /// <remarks>
    /// Called when a session ends. Without it, a drag that was in progress when the socket died would
    /// leave the desktop with the button physically stuck down — every later mouse move would keep
    /// drag-selecting, and only a real click could break out of it.
    /// </remarks>
    void ReleaseAllButtons();

    /// <summary>
    /// Injects one wheel event (docs/PROTOCOL.md §2.1).
    /// </summary>
    /// <remarks>
    /// <paramref name="delta"/> is in <em>native wheel units</em>, not "notches": one notch is
    /// <see cref="Win32MouseController.WheelDelta"/>=120, and Windows happily accumulates values far
    /// below that (15/20), which is what makes a pixel-smooth two-finger scroll possible. The caller
    /// converts the legacy <c>amount</c> field (<c>amount * 120</c>); see
    /// <see cref="AirTouchServer"/>'s dispatch.
    /// </remarks>
    /// <param name="delta">Positive = up (vertical) / right (horizontal), matching the protocol.</param>
    /// <param name="axis">Vertical unless the frame said <c>"axis":"h"</c>.</param>
    void Scroll(int delta, ScrollAxis axis = ScrollAxis.Vertical);
    void ResetToCenter();
    (int X, int Y) GetCursorPosition();

    /// <summary>How many injected events the OS refused; non-zero means UIPI/desktop trouble.</summary>
    long SendInputFailures { get; }
}

/// <summary>
/// Real cursor control through <c>user32.dll</c>: <c>SendInput</c> for relative moves, clicks and
/// wheel, <c>SetCursorPos</c> for the explicit "reset to primary screen centre" command.
/// </summary>
public sealed class Win32MouseController : IMouseController
{
    private const int InputMouse = 0;
    private const uint MouseEventMove = 0x0001;
    private const uint MouseEventLeftDown = 0x0002;
    private const uint MouseEventLeftUp = 0x0004;
    private const uint MouseEventRightDown = 0x0008;
    private const uint MouseEventRightUp = 0x0010;
    private const uint MouseEventWheel = 0x0800;
    private const uint MouseEventHWheel = 0x01000;

    /// <summary>Protocol-level clamp; keeps a hostile client from teleporting the cursor off screen.</summary>
    public const int MaxStepPixels = 4096;

    /// <summary>One notch of a legacy <c>amount</c> field, in native wheel units (<c>WHEEL_DELTA</c>).</summary>
    public const int WheelDelta = 120;

    /// <summary>Highest legacy <c>amount</c> the protocol accepts (docs/PROTOCOL.md §2.1).</summary>
    public const int MaxScrollNotches = 120;

    /// <summary>
    /// Ceiling for one injected wheel event, in native units (120 notches). A <c>delta</c> frame is
    /// already clamped to ±1200 by the parser; this bound only backstops the legacy
    /// <c>amount * 120</c> path, which can legitimately reach ±14400.
    /// </summary>
    public const int MaxScrollUnits = MaxScrollNotches * WheelDelta;

    private readonly IAirTouchLog _log;
    private readonly object _gate = new();

    /// <summary>Which buttons this controller has pressed and not yet released.</summary>
    private bool _leftDown;
    private bool _rightDown;

    private long _moveCount;
    private long _clickCount;
    private long _buttonCount;
    private long _scrollCount;
    private long _resetCount;
    private long _sendInputFailures;

    public Win32MouseController(IAirTouchLog? log = null)
    {
        _log = log ?? NullLog.Instance;
    }

    public long MoveCount => Interlocked.Read(ref _moveCount);

    public long ClickCount => Interlocked.Read(ref _clickCount);

    /// <summary>Button state transitions actually injected (idempotent repeats are not counted).</summary>
    public long ButtonCount => Interlocked.Read(ref _buttonCount);

    public long ScrollCount => Interlocked.Read(ref _scrollCount);

    public long ResetCount => Interlocked.Read(ref _resetCount);

    /// <summary>
    /// Number of injected events the OS rejected. Non-zero means the input never reached the desktop
    /// (a higher-integrity foreground window, a locked session, …) — the one failure mode where the
    /// phone keeps sending and the cursor simply does not move.
    /// </summary>
    public long SendInputFailures => Interlocked.Read(ref _sendInputFailures);

    public void Move(int dx, int dy)
    {
        dx = Math.Clamp(dx, -MaxStepPixels, MaxStepPixels);
        dy = Math.Clamp(dy, -MaxStepPixels, MaxStepPixels);

        if (dx == 0 && dy == 0)
        {
            return;
        }

        var inputs = new[]
        {
            new Input
            {
                Type = InputMouse,
                Data = new InputUnion
                {
                    Mouse = new MouseInput { Dx = dx, Dy = dy, Flags = MouseEventMove },
                },
            },
        };

        Send(inputs, "move");
        Interlocked.Increment(ref _moveCount);
    }

    public void Click(MouseButton button)
    {
        var (down, up) = button == MouseButton.Right
            ? (MouseEventRightDown, MouseEventRightUp)
            : (MouseEventLeftDown, MouseEventLeftUp);

        // Both halves of the click go into a single SendInput call so nothing can interleave.
        var inputs = new[]
        {
            new Input { Type = InputMouse, Data = new InputUnion { Mouse = new MouseInput { Flags = down } } },
            new Input { Type = InputMouse, Data = new InputUnion { Mouse = new MouseInput { Flags = up } } },
        };

        Send(inputs, "click");
        Interlocked.Increment(ref _clickCount);
    }

    public void Button(MouseButton button, bool down)
    {
        var isRight = button == MouseButton.Right;

        lock (_gate)
        {
            var wasDown = isRight ? _rightDown : _leftDown;
            if (wasDown == down)
            {
                // 幂等：重复按下、或抬起一个没按下的键，都是空操作。多发一次 down 会让按钮再也抬不
                // 起来（客户端以为它已经按着），多发一次 up 会把别人的拖拽松开，所以两次都宁可不动。
                return;
            }

            if (isRight)
            {
                _rightDown = down;
            }
            else
            {
                _leftDown = down;
            }
        }

        var name = isRight ? "right" : "left";
        var flags = isRight
            ? (down ? MouseEventRightDown : MouseEventRightUp)
            : (down ? MouseEventLeftDown : MouseEventLeftUp);

        // One state transition per SendInput call, exactly like a key frame: the press stays down until the
        // client sends the matching release, which is what makes a stream of move frames drag.
        var inputs = new[]
        {
            new Input { Type = InputMouse, Data = new InputUnion { Mouse = new MouseInput { Flags = flags } } },
        };

        Send(inputs, $"button {name} {(down ? "down" : "up")}");
        Interlocked.Increment(ref _buttonCount);
    }

    public void ReleaseAllButtons()
    {
        var flags = new List<uint>(2);

        lock (_gate)
        {
            if (_leftDown)
            {
                _leftDown = false;
                flags.Add(MouseEventLeftUp);
            }

            if (_rightDown)
            {
                _rightDown = false;
                flags.Add(MouseEventRightUp);
            }
        }

        if (flags.Count == 0)
        {
            return;
        }

        var inputs = new Input[flags.Count];
        for (var i = 0; i < flags.Count; i++)
        {
            inputs[i] = new Input { Type = InputMouse, Data = new InputUnion { Mouse = new MouseInput { Flags = flags[i] } } };
        }

        // One call so no other injection can slip between the releases.
        Send(inputs, "button release-all");
        _log.Warn("已释放仍按下的鼠标按键（避免桌面停留在拖拽/框选状态）");
    }

    public void Scroll(int delta, ScrollAxis axis = ScrollAxis.Vertical)
    {
        delta = Math.Clamp(delta, -MaxScrollUnits, MaxScrollUnits);
        if (delta == 0)
        {
            return;
        }

        // Positive delta = wheel up / wheel right, matching the protocol's "正数上滚 / 正数右滚".
        //
        // 横向用的是 MOUSEEVENTF_HWHEEL(0x01000)：它与垂直滚轮是完全独立的两个消息，delta 一样放在
        // MouseData 里。小于 120 的量（15/20）Windows 会替应用累加，所以手机端可以按像素级细粒度发送，
        // 滚动比「一格一格跳」细腻得多。
        var inputs = new[]
        {
            new Input
            {
                Type = InputMouse,
                Data = new InputUnion
                {
                    Mouse = new MouseInput
                    {
                        Flags = axis == ScrollAxis.Horizontal ? MouseEventHWheel : MouseEventWheel,
                        MouseData = unchecked((uint)delta),
                    },
                },
            },
        };

        Send(inputs, $"scroll {(axis == ScrollAxis.Horizontal ? "h" : "v")} {delta}");
        Interlocked.Increment(ref _scrollCount);
    }

    public void ResetToCenter()
    {
        // 手机的「复位」是用户的应急按钮：它必须先把可能还按着的鼠标键放掉。否则一次中断的拖拽会
        // 把桌面留在框选状态，而用户正是因为这个才去按复位的 —— 那等于按了没有用。
        ReleaseAllButtons();

        var bounds = Screen.PrimaryScreen?.Bounds ?? Screen.GetBounds(System.Drawing.Point.Empty);
        var centerX = bounds.Left + (bounds.Width / 2);
        var centerY = bounds.Top + (bounds.Height / 2);

        if (!SetCursorPos(centerX, centerY))
        {
            _log.Error($"SetCursorPos 失败：{Marshal.GetLastWin32Error()}");
        }

        Interlocked.Increment(ref _resetCount);
    }

    public (int X, int Y) GetCursorPosition()
    {
        return GetCursorPos(out var point) ? (point.X, point.Y) : (0, 0);
    }

    private void Send(Input[] inputs, string operation)
    {
        var size = Marshal.SizeOf<Input>();
        var sent = SendInput((uint)inputs.Length, inputs, size);
        if (sent != inputs.Length)
        {
            Interlocked.Increment(ref _sendInputFailures);
            _log.Error($"SendInput({operation}) 只被接受 {sent}/{inputs.Length} 个事件，错误码 {Marshal.GetLastWin32Error()}");
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public InputUnion Data;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)]
        public MouseInput Mouse;

        [FieldOffset(0)]
        public KeyboardInput Keyboard;

        [FieldOffset(0)]
        public HardwareInput Hardware;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int Dx;
        public int Dy;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public IntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort VirtualKey;
        public ushort ScanCode;
        public uint Flags;
        public uint Time;
        public IntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HardwareInput
    {
        public uint Message;
        public ushort ParameterLow;
        public ushort ParameterHigh;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point32
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint numberOfInputs, Input[] inputs, int sizeOfInputStructure);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out Point32 point);
}

/// <summary>Test double: records every command instead of touching the real cursor.</summary>
public sealed class RecordingMouseController : IMouseController
{
    private readonly List<string> _events = new();

    /// <summary>Which buttons this controller has pressed and not yet released.</summary>
    private bool _leftDown;
    private bool _rightDown;

    private int _x;
    private int _y;

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

    public void Move(int dx, int dy)
    {
        lock (_events)
        {
            _x += dx;
            _y += dy;
            _events.Add($"move {dx} {dy}");
        }
    }

    public long SendInputFailures => 0;

    public void Click(MouseButton button)
    {
        lock (_events)
        {
            _events.Add($"click {button.ToString().ToLowerInvariant()}");
        }
    }

    /// <summary>
    /// Records one transition as <c>button left down</c> / <c>button right up</c>.
    /// </summary>
    /// <remarks>
    /// Deliberately reproduces <see cref="Win32MouseController"/>'s idempotence: a duplicate press or a
    /// release of a button that is not pressed records nothing, so a test can pin that the peer's stray
    /// frame never reaches the desktop.
    /// </remarks>
    public void Button(MouseButton button, bool down)
    {
        lock (_events)
        {
            var isRight = button == MouseButton.Right;
            var wasDown = isRight ? _rightDown : _leftDown;
            if (wasDown == down)
            {
                return;
            }

            if (isRight)
            {
                _rightDown = down;
            }
            else
            {
                _leftDown = down;
            }

            _events.Add($"button {(isRight ? "right" : "left")} {(down ? "down" : "up")}");
        }
    }

    public void ReleaseAllButtons()
    {
        lock (_events)
        {
            // 与真实控制器一致：没有按下的键时是空操作，连事件都不记 —— 否则每个 reset 都会多出
            // 一条 release-all，已有的事件序号断言就全乱了。
            if (!_leftDown && !_rightDown)
            {
                return;
            }

            _leftDown = false;
            _rightDown = false;
            _events.Add("release-all");
        }
    }

    /// <summary>
    /// Records one wheel event as <c>scroll v 15</c> / <c>scroll h -20</c> (axis letter + native units),
    /// which is exactly what the caller passed: this double deliberately does <em>not</em> clamp, so a
    /// test can still see a caller that failed to convert or clamp.
    /// </summary>
    public void Scroll(int delta, ScrollAxis axis = ScrollAxis.Vertical)
    {
        lock (_events)
        {
            _events.Add($"scroll {(axis == ScrollAxis.Horizontal ? "h" : "v")} {delta}");
        }
    }

    public void ResetToCenter()
    {
        // 与真实控制器同序：复位先松开仍按着的鼠标键，再回到屏幕中心。
        ReleaseAllButtons();

        lock (_events)
        {
            _events.Add("reset");
        }
    }

    public (int X, int Y) GetCursorPosition()
    {
        lock (_events)
        {
            return (_x, _y);
        }
    }
}
