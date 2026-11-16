using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace AirTouch.Core;

public enum MouseButton
{
    Left,
    Right,
}

/// <summary>
/// Abstraction over cursor control so the protocol can be exercised without moving a real cursor.
/// </summary>
public interface IMouseController
{
    void Move(int dx, int dy);
    void Click(MouseButton button);
    void Scroll(int amount);
    void ResetToCenter();
    (int X, int Y) GetCursorPosition();
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
    private const int WheelDelta = 120;

    /// <summary>Protocol-level clamp; keeps a hostile client from teleporting the cursor off screen.</summary>
    public const int MaxStepPixels = 4096;

    public const int MaxScrollNotches = 120;

    private readonly IAirTouchLog _log;
    private long _moveCount;
    private long _clickCount;
    private long _scrollCount;
    private long _resetCount;

    public Win32MouseController(IAirTouchLog? log = null)
    {
        _log = log ?? NullLog.Instance;
    }

    public long MoveCount => Interlocked.Read(ref _moveCount);

    public long ClickCount => Interlocked.Read(ref _clickCount);

    public long ScrollCount => Interlocked.Read(ref _scrollCount);

    public long ResetCount => Interlocked.Read(ref _resetCount);

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

    public void Scroll(int amount)
    {
        amount = Math.Clamp(amount, -MaxScrollNotches, MaxScrollNotches);
        if (amount == 0)
        {
            return;
        }

        // Positive amount = wheel up (away from the user), matching the protocol's "正数上滚".
        var inputs = new[]
        {
            new Input
            {
                Type = InputMouse,
                Data = new InputUnion
                {
                    Mouse = new MouseInput
                    {
                        Flags = MouseEventWheel,
                        MouseData = unchecked((uint)(amount * WheelDelta)),
                    },
                },
            },
        };

        Send(inputs, "scroll");
        Interlocked.Increment(ref _scrollCount);
    }

    public void ResetToCenter()
    {
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

    public void Click(MouseButton button)
    {
        lock (_events)
        {
            _events.Add($"click {button.ToString().ToLowerInvariant()}");
        }
    }

    public void Scroll(int amount)
    {
        lock (_events)
        {
            _events.Add($"scroll {amount}");
        }
    }

    public void ResetToCenter()
    {
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
