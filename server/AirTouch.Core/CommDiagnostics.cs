namespace AirTouch.Core;

/// <summary>What the PC measured while a client was streaming a communication test.</summary>
public sealed record ServerDiagReport(
    string TestKind,
    string Mode,
    int Planned,
    int Received,
    int FirstSeq,
    int LastSeq,
    int Lost,
    int Duplicated,
    int OutOfOrder,
    int MaxGapMs,
    int MeanGapMs,
    long DurationMs,
    long ClientElapsedMs,
    int ClientSent,
    int ClientMaxSendGapMs,
    int TargetHz,
    long SendInputFailures,
    int StartX,
    int StartY,
    int EndX,
    int EndY,
    int CursorUpdates,
    int CursorPollHz,
    int CursorMaxGapMs,
    int DisplayHz)
{
    /// <summary>Frames the phone says it sent but that never arrived (network or server-side loss).</summary>
    public int Missing => Math.Max(0, ClientSent - Received);

    /// <summary>
    /// How many times per second the cursor position actually changed while the test ran, measured by a
    /// dedicated sampler thread inside this process (<c>GetCursorPos</c> polling).
    /// </summary>
    /// <remarks>
    /// This is the number that separates "Windows never applied the frames" from "the frames arrived but
    /// each one was sub-pixel slow". A real mouse spends 1 physical pixel per update at most, so a slow
    /// stream moves the cursor only every few frames no matter how fast the client sends — if this number
    /// matches the requested rate, the OS side is not the bottleneck.
    /// </remarks>
    public int CursorHz => DurationMs <= 0 ? 0 : (int)Math.Round(CursorUpdates * 1000.0 / DurationMs);

    /// <summary>True when the sampler was fast enough for <see cref="CursorHz"/> to be meaningful.</summary>
    public bool CursorSampleValid => CursorPollHz >= 200;
}

/// <summary>
/// Measures one communication test run on the receiving side (docs/PROTOCOL.md §2.3).
/// </summary>
/// <remarks>
/// <para>
/// This exists to answer a question the cursor alone cannot: when the mouse "sticks" while the phone
/// keeps moving, is the phone failing to send, is the network dropping frames, or is Windows refusing
/// the injected input? The three culprits have three different fingerprints:
/// </para>
/// <list type="bullet">
///   <item>phone-side stall → <see cref="ClientSent"/> is low and the client's own
///     <see cref="ClientMaxSendGapMs"/> is large;</item>
///   <item>network loss → sequence gaps and duplicated/out-of-order frames, or a large
///     <see cref="MaxGapMs"/> with the client reporting a healthy send rate;</item>
///   <item>injection failure → <see cref="SendInputFailures"/> is non-zero (UIPI/locked desktop) while
///     every frame arrived.</item>
/// </list>
/// <para>
/// Only frames that carry a <c>seq</c> field are accounted for, and only between <c>diag_begin</c> and
/// <c>diag_report</c>, so normal control traffic pays nothing for this.
/// </para>
/// </remarks>
public sealed class CommTestSession
{
    private const int MaxTrackedSequences = 200_000;

    private readonly object _gate = new();
    private readonly HashSet<int> _seen = new();

    private int _firstSeq = -1;
    private int _lastSeq = -1;
    private int _duplicated;
    private int _outOfOrder;
    private long _gapSumMs;
    private int _gapCount;
    private int _maxGapMs;
    private long _lastArrivalTicks;
    private DateTimeOffset _startedUtc;
    private int _received;

    private CursorSampler? _cursorSampler;

    public CommTestSession(string testKind, string mode, int planned, int targetHz, int circleMs, DateTimeOffset now)
    {
        TestKind = string.IsNullOrEmpty(testKind) ? "circle" : testKind;
        Mode = mode == "rel" ? "rel" : "abs";
        Planned = Math.Max(0, planned);
        TargetHz = Math.Max(0, targetHz);
        CircleMs = Math.Max(0, circleMs);
        _startedUtc = now;
    }

    public string TestKind { get; }

    public string Mode { get; }

    /// <summary>Frames the client announced it would send.</summary>
    public int Planned { get; }

    public int TargetHz { get; }

    public int CircleMs { get; }

    /// <summary>
    /// Starts sampling the real cursor position on a background thread. Call right after the session is
    /// created (i.e. when <c>diag_begin</c> arrives) and stop it in <see cref="Finish"/>.
    /// </summary>
    /// <param name="readCursor">Usually <c>IMouseController.GetCursorPosition</c>.</param>
    public void StartCursorSampling(Func<(int X, int Y)> readCursor)
    {
        _cursorSampler?.Dispose();
        _cursorSampler = new CursorSampler(readCursor);
        _cursorSampler.Start();
    }

    /// <summary>Counts one received frame; <paramref name="seq"/> may be null for unlabelled frames.</summary>
    public void NoteFrame(int? seq, DateTimeOffset now)    {
        lock (_gate)
        {
            _received++;

            var ticks = now.UtcTicks;
            if (_lastArrivalTicks != 0)
            {
                var gapMs = (int)Math.Min(int.MaxValue, (ticks - _lastArrivalTicks) / TimeSpan.TicksPerMillisecond);
                _gapSumMs += gapMs;
                _gapCount++;
                if (gapMs > _maxGapMs)
                {
                    _maxGapMs = gapMs;
                }
            }

            _lastArrivalTicks = ticks;

            if (seq is null)
            {
                return;
            }

            var value = seq.Value;
            if (_firstSeq < 0)
            {
                _firstSeq = value;
            }

            if (value < _lastSeq)
            {
                _outOfOrder++;
            }

            if (_seen.Count < MaxTrackedSequences && !_seen.Add(value))
            {
                _duplicated++;
            }

            if (value > _lastSeq)
            {
                _lastSeq = value;
            }
        }
    }

    /// <summary>Finalizes the run and produces the report sent back to the phone and shown in the UI.</summary>
    public ServerDiagReport Finish(
        ClientDiagReport client,
        (int X, int Y) start,
        (int X, int Y) end,
        long sendInputFailures,
        DateTimeOffset now)
    {
        lock (_gate)
        {
            var distinct = _seen.Count;
            var span = _firstSeq < 0 ? 0 : _lastSeq - _firstSeq + 1;
            var lost = Math.Max(0, span - distinct);
            var meanGap = _gapCount == 0 ? 0 : (int)(_gapSumMs / _gapCount);
            var cursor = _cursorSampler?.Stop() ?? CursorStats.None;
            _cursorSampler = null;

            return new ServerDiagReport(
                TestKind: TestKind,
                Mode: Mode,
                Planned: Planned > 0 ? Planned : client.Planned,
                Received: _received,
                FirstSeq: Math.Max(0, _firstSeq),
                LastSeq: Math.Max(0, _lastSeq),
                Lost: lost,
                Duplicated: _duplicated,
                OutOfOrder: _outOfOrder,
                MaxGapMs: _maxGapMs,
                MeanGapMs: meanGap,
                DurationMs: (long)(now - _startedUtc).TotalMilliseconds,
                ClientElapsedMs: client.ElapsedMs,
                ClientSent: client.Sent,
                ClientMaxSendGapMs: client.MaxSendGapMs,
                TargetHz: TargetHz,
                SendInputFailures: sendInputFailures,
                StartX: start.X,
                StartY: start.Y,
                EndX: end.X,
                EndY: end.Y,
                CursorUpdates: cursor.Updates,
                CursorPollHz: cursor.PollHz,
                CursorMaxGapMs: cursor.MaxGapMs,
                DisplayHz: ScreenInfo.PrimaryScreenRefreshHz());
        }
    }
}

/// <summary>What the cursor sampler measured during one test.</summary>
public readonly record struct CursorStats(int Updates, int PollHz, int MaxGapMs)
{
    public static CursorStats None => new(0, 0, 0);
}

/// <summary>
/// Polls <c>GetCursorPos</c> as fast as it can and counts how often the cursor actually moved.
/// </summary>
/// <remarks>
/// <para>
/// Why this exists: the client's own report says how many frames it <em>sent</em>, and the arrival
/// statistics say how many <em>reached</em> this process, but neither says how often the cursor on screen
/// really changed. Those are three different numbers, and only the third one is what the user sees.
/// </para>
/// <para>
/// The very first reading is only a baseline and is deliberately **not** counted as an update, so
/// <see cref="CursorStats.Updates"/> stays comparable with the number of frames that arrived. (It can
/// still exceed it — a human moving the physical mouse during a test also moves the cursor.)
/// </para>
/// <para>
/// A dedicated thread is required because the answer is "how many distinct positions per second", which
/// is exactly what the eye perceives as smoothness. If the sampler polls at 1000+ Hz and counts the same
/// number of updates as the client sent, then Windows applied every frame and the OS is not the
/// bottleneck — the perceived stepping must then come from the steps being sub-pixel small (slow motion)
/// or from frames arriving in bursts.
/// </para>
/// </remarks>
public sealed class CursorSampler : IDisposable
{
    private readonly Func<(int X, int Y)> _read;
    private readonly ManualResetEventSlim _stop = new(false);
    private readonly Thread _thread;

    private long _updates;
    private long _samples;
    private long _elapsedTicks;
    private long _maxGapTicks;

    public CursorSampler(Func<(int X, int Y)> read)
    {
        _read = read;
        _thread = new Thread(Loop)
        {
            IsBackground = true,
            Name = "airtouch-cursor-sampler",
            // 刻意用 BelowNormal：这个线程是**测量工具**，绝不允许抢在接收线程（Fleck）与
            // SendInput 前面。用高优先级 + 十万赫兹自旋去量延迟，本身就是给被测对象制造停顿 ——
            // 实测过一次「最大到达间隔 236ms」里就可能有它一份。BelowNormal 下任何真实工作
            // 都能立刻抢占它，代价只是采样率从百万级掉到十万级（对 1000Hz 的光标绰绰有余）。
            Priority = ThreadPriority.BelowNormal,
        };
    }

    public void Start() => _thread.Start();

    public CursorStats Stop()
    {
        _stop.Set();
        _thread.Join(500);

        var ticksPerSecond = (double)System.Diagnostics.Stopwatch.Frequency;
        var updates = Interlocked.Read(ref _updates);
        var samples = Interlocked.Read(ref _samples);
        var elapsedSec = Math.Max(1e-6, Interlocked.Read(ref _elapsedTicks) / ticksPerSecond);

        return new CursorStats(
            Updates: (int)Math.Min(int.MaxValue, updates),
            PollHz: (int)Math.Min(int.MaxValue, samples / elapsedSec),
            MaxGapMs: (int)Math.Min(int.MaxValue, Interlocked.Read(ref _maxGapTicks) / (ticksPerSecond / 1000.0)));
    }

    private void Loop()
    {
        var watch = System.Diagnostics.Stopwatch.StartNew();
        Interlocked.Exchange(ref _elapsedTicks, 0);
        var lastChange = watch.ElapsedTicks;
        var lastX = -1;
        var lastY = -1;
        var first = true;

        while (!_stop.IsSet)
        {
            var (x, y) = _read();
            Interlocked.Increment(ref _samples);

            if (first)
            {
                // 第一次读到的位置只是基线，不算「变化」——否则每次测试都会多算 1 次，
                // 让"更新次数"看起来比收到的帧数还多。
                first = false;
                lastX = x;
                lastY = y;
                lastChange = watch.ElapsedTicks;
                continue;
            }

            if (x == lastX && y == lastY)
            {
                // 光标没动：把 CPU 让出去（BelowNormal + Sleep(0)，被抢占也立即让路）。
                // 采样率仍然在十万赫兹量级，量 1000Hz 的光标位置变化完全够用。
                Thread.Sleep(0);
                Thread.SpinWait(20);
                continue;
            }

            var gap = watch.ElapsedTicks - lastChange;
            if (gap > Interlocked.Read(ref _maxGapTicks))
            {
                Interlocked.Exchange(ref _maxGapTicks, gap);
            }

            lastX = x;
            lastY = y;
            lastChange = watch.ElapsedTicks;
            Interlocked.Increment(ref _updates);
        }

        Interlocked.Exchange(ref _elapsedTicks, watch.ElapsedTicks);
    }

    public void Dispose() => _stop.Dispose();
}
