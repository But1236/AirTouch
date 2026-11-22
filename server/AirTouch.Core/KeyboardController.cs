using System.Runtime.InteropServices;

namespace AirTouch.Core;

/// <summary>
/// Abstraction over keyboard injection so the <c>key</c> frame (docs/PROTOCOL.md §2.6) can be exercised
/// without typing into whatever window happens to have focus.
/// </summary>
/// <remarks>
/// One call = one key <em>transition</em>, never a whole combination. Modifier combinations
/// (<c>Ctrl+C</c>) arrive as an ordered run of transitions, exactly like a real keyboard produces them;
/// see docs/PROTOCOL.md §2.6 for why the client owns that sequencing.
/// </remarks>
public interface IKeyboardController
{
    /// <summary>
    /// Injects one key transition.
    /// </summary>
    /// <param name="vk">Win32 virtual-key code (1..255).</param>
    /// <param name="down"><c>true</c> presses, <c>false</c> releases.</param>
    /// <param name="extended">
    /// <c>true</c>/<c>false</c> forces the <c>KEYEVENTF_EXTENDEDKEY</c> flag; <c>null</c> (the normal
    /// case) lets the controller decide from <paramref name="vk"/>. It is only needed for keys that a
    /// virtual-key code cannot disambiguate — the numeric keypad's Enter shares <c>VK_RETURN</c> with
    /// the main Enter.
    /// </param>
    void Key(int vk, bool down, bool? extended = null);

    /// <summary>
    /// Injects <paramref name="text"/> as Unicode characters (docs/PROTOCOL.md §2.7), independent of the
    /// keyboard layout.
    /// </summary>
    /// <remarks>
    /// One call = one whole string, pressed and released per character in a single OS call. Unlike
    /// <see cref="Key"/> it carries no key state, so it takes no part in the stuck-key bookkeeping and
    /// <see cref="ReleaseAll"/> has nothing to release for it. Must never throw: the caller is a socket
    /// worker thread.
    /// </remarks>
    void Text(string text);

    /// <summary>
    /// Releases every key this controller still holds down and forgets them. Returns how many were
    /// released.
    /// </summary>
    /// <remarks>
    /// Called when a session ends. Without it, a modifier that was latched on the phone (the sticky
    /// Ctrl/Shift/Alt/Win keys) would stay physically down on the PC after the socket dies — Windows
    /// would then treat every subsequent keystroke on the real keyboard as a shortcut.
    /// </remarks>
    int ReleaseAll();

    /// <summary>How many injected keyboard events the OS refused (UIPI / locked session / …).</summary>
    long SendInputFailures { get; }
}

/// <summary>Win32 virtual-key facts that a virtual-key code alone cannot express.</summary>
public static class Win32KeyInfo
{
    /// <summary>
    /// Keys that must be injected with <c>KEYEVENTF_EXTENDEDKEY</c>.
    /// </summary>
    /// <remarks>
    /// The flag exists because the PC/AT keyboard encodes these keys by prefixing the scan code with
    /// <c>0xE0</c>. Without it Windows resolves the scan code to the <em>numeric keypad</em> twin, so a
    /// "Left arrow" lands as numpad-4 whenever NumLock is on, and Delete lands as the decimal point.
    /// The set below is the canonical extended list.
    /// </remarks>
    private static readonly HashSet<int> ExtendedKeys = new()
    {
        0x21, // VK_PRIOR    Page Up
        0x22, // VK_NEXT     Page Down
        0x23, // VK_END
        0x24, // VK_HOME
        0x25, // VK_LEFT
        0x26, // VK_UP
        0x27, // VK_RIGHT
        0x28, // VK_DOWN
        0x2C, // VK_SNAPSHOT Print Screen
        0x2D, // VK_INSERT
        0x2E, // VK_DELETE
        0x5B, // VK_LWIN
        0x5C, // VK_RWIN
        0x5D, // VK_APPS     context menu
        0x6F, // VK_DIVIDE   numeric keypad /
        0x90, // VK_NUMLOCK
        0xA3, // VK_RCONTROL
        0xA5, // VK_RMENU    right Alt
    };

    /// <summary>True when <paramref name="vk"/> needs <c>KEYEVENTF_EXTENDEDKEY</c>.</summary>
    public static bool IsExtended(int vk) => ExtendedKeys.Contains(vk);
}

/// <summary>
/// Real keyboard injection through <c>user32.dll</c> <c>SendInput</c>.
/// </summary>
/// <remarks>
/// <para>
/// Deliberately the same shape as <see cref="Win32MouseController"/>: one <c>SendInput</c> call per
/// protocol frame, failures counted per call, and an internal lock so the down-key bookkeeping stays
/// consistent. It never touches the server's own locks, so a slow keyboard injection cannot block the
/// cursor path.
/// </para>
/// <para>
/// The controller remembers which keys <em>it</em> pressed so <see cref="ReleaseAll"/> can let go of
/// exactly those and nothing else. Keys the user pressed on the physical keyboard are invisible here
/// and are never released.
/// </para>
/// </remarks>
public sealed class Win32KeyboardController : IKeyboardController
{
    private const int InputKeyboard = 1;

    /// <summary>KEYEVENTF_EXTENDEDKEY</summary>
    private const uint KeyEventExtendedKey = 0x0001;

    /// <summary>KEYEVENTF_KEYUP</summary>
    private const uint KeyEventKeyUp = 0x0002;

    /// <summary>KEYEVENTF_UNICODE</summary>
    private const uint KeyEventUnicode = 0x0004;

    private readonly IAirTouchLog _log;
    private readonly object _gate = new();

    /// <summary>vk → whether it was injected as an extended key, for the matching key-up.</summary>
    private readonly Dictionary<int, bool> _pressed = new();

    private long _keyCount;
    private long _sendInputFailures;

    public Win32KeyboardController(IAirTouchLog? log = null)
    {
        _log = log ?? NullLog.Instance;
    }

    public long KeyCount => Interlocked.Read(ref _keyCount);

    /// <summary>How many keys are currently held down by this controller (0 in steady state).</summary>
    public int HeldKeyCount
    {
        get
        {
            lock (_gate)
            {
                return _pressed.Count;
            }
        }
    }

    public long SendInputFailures => Interlocked.Read(ref _sendInputFailures);

    public void Key(int vk, bool down, bool? extended = null)
    {
        // A virtual-key code of 0 means "no key"; anything above 255 is not a virtual key. Ignore rather
        // than throw: this path runs on a Fleck worker thread whose exceptions are reported as socket
        // errors and would tear the session down.
        if (vk is <= 0 or > 255)
        {
            _log.Warn($"忽略非法虚拟键码 vk={vk}");
            return;
        }

        bool isExtended;
        if (extended.HasValue)
        {
            isExtended = extended.Value;
        }
        else
        {
            lock (_gate)
            {
                // A release must repeat exactly the flags used on the press, or Windows resolves the
                // scan code to the other twin of the key and releases something that was never pressed.
                isExtended = _pressed.TryGetValue(vk, out var remembered)
                    ? remembered
                    : Win32KeyInfo.IsExtended(vk);
            }
        }

        var flags = (down ? 0u : KeyEventKeyUp) | (isExtended ? KeyEventExtendedKey : 0u);

        lock (_gate)
        {
            if (down)
            {
                _pressed[vk] = isExtended;
            }
            else
            {
                _pressed.Remove(vk);
            }
        }

        var inputs = new[]
        {
            new Input
            {
                Type = InputKeyboard,
                Data = new InputUnion
                {
                    Keyboard = new KeyboardInput
                    {
                        VirtualKey = (ushort)vk,
                        Flags = flags,
                    },
                },
            },
        };

        Send(inputs, down ? $"key down vk={vk}" : $"key up vk={vk}");
        Interlocked.Increment(ref _keyCount);
    }

    public void Text(string text)
    {
        // 空串没有可注入的字符。注意一个空格是**真实字符**，必须照注入，不能按「空白」跳过：
        // 用户在手机上敲的就是那个空格。
        if (string.IsNullOrEmpty(text))
        {
            return;
        }

        var inputs = new Input[text.Length * 2];
        for (var i = 0; i < text.Length; i++)
        {
            // wVk 必须为 0、UTF-16 码元放进 wScan：带 KEYEVENTF_UNICODE 时 Windows 直接把这个码元
            // 当作字符投给前台窗口，不查键盘布局，所以中文、emoji 与只有 AltGr 才打得出的字符都能
            // 原样落下。一个字符两个事件（按下 + 抬起），一个补充平面字符因此是两个码元、四个事件。
            inputs[i * 2] = UnicodeInput(text[i], up: false);
            inputs[(i * 2) + 1] = UnicodeInput(text[i], up: true);
        }

        // 整串一次发完：中间被别的注入插进来，字符顺序就乱了。
        Send(inputs, $"text {text.Length} 码元");
    }

    private static Input UnicodeInput(char unit, bool up) => new()
    {
        Type = InputKeyboard,
        Data = new InputUnion
        {
            Keyboard = new KeyboardInput
            {
                VirtualKey = 0,
                ScanCode = unit,
                Flags = KeyEventUnicode | (up ? KeyEventKeyUp : 0u),
            },
        },
    };

    public int ReleaseAll()
    {
        KeyValuePair<int, bool>[] pending;
        lock (_gate)
        {
            if (_pressed.Count == 0)
            {
                return 0;
            }

            pending = _pressed.ToArray();
            _pressed.Clear();
        }

        var inputs = new Input[pending.Length];
        for (var i = 0; i < pending.Length; i++)
        {
            inputs[i] = new Input
            {
                Type = InputKeyboard,
                Data = new InputUnion
                {
                    Keyboard = new KeyboardInput
                    {
                        VirtualKey = (ushort)pending[i].Key,
                        Flags = KeyEventKeyUp | (pending[i].Value ? KeyEventExtendedKey : 0u),
                    },
                },
            };
        }

        // One call so no other injection can slip between the releases.
        Send(inputs, "key release-all");
        _log.Warn($"会话结束，已释放 {pending.Length} 个仍按下的按键（避免电脑端留下卡住的 Ctrl/Shift/Alt/Win）");
        return pending.Length;
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

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint numberOfInputs, Input[] inputs, int sizeOfInputStructure);
}

/// <summary>Test double: records every key transition instead of typing into the desktop.</summary>
/// <remarks>
/// It deliberately reproduces <see cref="Win32KeyboardController"/>'s <em>press-time</em> extended-key
/// bookkeeping rather than just calling <see cref="Win32KeyInfo.IsExtended"/> on every event. A test
/// double that resolves the flag differently from the real controller cannot catch the exact bug that
/// matters here — a release that resolves to the other twin of the key.
/// </remarks>
public sealed class RecordingKeyboardController : IKeyboardController
{
    private readonly List<string> _events = new();

    /// <summary>vk → whether it was injected as an extended key, for the matching key-up.</summary>
    private readonly Dictionary<int, bool> _pressed = new();

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

    public int HeldKeyCount
    {
        get
        {
            lock (_events)
            {
                return _pressed.Count;
            }
        }
    }

    public long SendInputFailures => 0;

    public void Key(int vk, bool down, bool? extended = null)
    {
        if (vk is <= 0 or > 255)
        {
            return;
        }

        lock (_events)
        {
            bool isExtended;
            if (extended.HasValue)
            {
                isExtended = extended.Value;
            }
            else
            {
                // 抬起必须沿用按下时的标志，否则松开的可能是同一个虚拟键码的另一个「孪生键」
                isExtended = _pressed.TryGetValue(vk, out var remembered)
                    ? remembered
                    : Win32KeyInfo.IsExtended(vk);
            }

            if (down)
            {
                _pressed[vk] = isExtended;
            }
            else
            {
                _pressed.Remove(vk);
            }

            _events.Add($"key {vk} {(down ? "down" : "up")}{(isExtended ? " ext" : string.Empty)}");
        }
    }

    public void Text(string text)
    {
        // 与真实控制器保持一致：空串什么都不记录，其余整串记成一条事件（text 帧没有按键状态，
        // 所以不进 _pressed，ReleaseAll 也不会为它产生任何事件）。
        if (string.IsNullOrEmpty(text))
        {
            return;
        }

        lock (_events)
        {
            _events.Add("text " + text);
        }
    }

    public int ReleaseAll()
    {
        lock (_events)
        {
            if (_pressed.Count == 0)
            {
                return 0;
            }

            var count = _pressed.Count;
            foreach (var pair in _pressed.OrderBy(p => p.Key))
            {
                _events.Add($"key {pair.Key} up release-all{(pair.Value ? " ext" : string.Empty)}");
            }

            _pressed.Clear();
            return count;
        }
    }
}
