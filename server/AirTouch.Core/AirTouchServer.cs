using System.Diagnostics;
using Fleck;

namespace AirTouch.Core;

public sealed record ServerStatus(
    bool IsRunning,
    int Port,
    string? AdvertisedAddress,
    string Url,
    string Token,
    bool TokenConsumed,
    string Sid,
    string? ClientAddress,
    string? ClientName,
    string? ClientKeyId,
    string? ClientAuthMode,
    int PendingCount,
    IReadOnlyList<TrustedDevice> TrustedDevices,
    IReadOnlyCollection<string> BannedAddresses,
    string DownloadDirectory,
    string CertificateFingerprint,
    string CertificatePin,
    long MoveCommands,
    long ClickCommands,
    long ScrollCommands,
    long ResetCommands,
    long KeyCommands,
    long TextCommands,
    long BytesReceived,
    long BytesSent,
    long SendInputFailures,
    long KeyInputFailures);

public sealed record FileSendProgress(string FileName, long SentBytes, long TotalBytes)
{
    public double Percent => TotalBytes <= 0 ? 0 : (double)SentBytes / TotalBytes * 100.0;
}

public sealed record FileSendResult(bool Success, string FileName, long Bytes, string Message);

public sealed class AirTouchServerException : Exception
{
    public AirTouchServerException(string message, Exception? inner = null)
        : base(message, inner)
    {
    }
}

/// <summary>
/// The WSS endpoint: authenticates the phone with a device key, turns control frames into cursor
/// movement and moves files in both directions.
/// </summary>
/// <remarks>
/// <para>
/// Authentication (docs/PROTOCOL.md §1.2) is a three-step exchange that costs exactly one signature per
/// session and nothing at all per touch frame:
/// </para>
/// <list type="number">
///   <item>the socket opens and is admitted as <em>pending</em>, bounded by a deadline and a small
///     concurrency budget in <see cref="ConnectionGuard"/>; the server sends <c>auth_challenge</c> with a
///     single-use nonce;</item>
///   <item>the phone answers with <c>auth_req</c> — with the one-time token plus its public key when it is
///     pairing, or with just its key id when it is resuming;</item>
///   <item>the server verifies the ECDSA P-256 signature over the nonce and only then sends
///     <c>welcome</c>. Resuming needs no QR code, no token and no address binding.</item>
/// </list>
/// <para>
/// Threading: Fleck raises all socket callbacks on its own worker threads, the WPF window talks to us
/// from the dispatcher thread and the watchdog runs on a timer thread. Connection state lives behind
/// <c>_gate</c>, the in-flight incoming transfer behind <c>_transferGate</c>, and the two locks are never
/// held at the same time so a slow disk can never block a mouse command.
/// </para>
/// </remarks>
public sealed class AirTouchServer : IDisposable
{
    public const string Version = "2.0.2";

    /// <summary>One socket that has been admitted but has not proven its device key yet.</summary>
    private sealed class PendingAuth
    {
        public required IWebSocketConnection Socket { get; init; }

        public required string Address { get; init; }

        public required AuthMode Mode { get; init; }

        public required string Nonce { get; init; }

        public required DateTimeOffset ExpiresUtc { get; init; }
    }

    private readonly AirTouchOptions _options;
    private readonly IMouseController _mouse;
    private readonly IKeyboardController _keyboard;
    private readonly IAirTouchLog _log;
    private readonly ConnectionGuard _guard;
    private readonly ServerCertificate _certificate;
    private readonly ServerIdentity _identity;
    private readonly DeviceKeyStore _devices;

    private readonly object _gate = new();
    private readonly object _transferGate = new();
    private readonly Dictionary<IWebSocketConnection, PendingAuth> _pending = new();

    private WebSocketServer? _listener;
    private IWebSocketConnection? _client;
    private string? _clientKeyId;
    private string? _clientDeviceName;
    private string? _clientAuthMode;
    private IncomingFileTransfer? _incoming;
    private System.Threading.Timer? _watchdog;
    private DateTimeOffset _lastActivityUtc;
    private DateTimeOffset _lastPingUtc;
    private bool _disposed;

    private long _moveCommands;
    private long _clickCommands;
    private long _scrollCommands;
    private long _resetCommands;
    private long _keyCommands;
    private long _textCommands;
    private long _bytesReceived;
    private long _bytesSent;

    public AirTouchServer(
        AirTouchOptions options,
        IMouseController mouseController,
        IAirTouchLog? log = null,
        IKeyboardController? keyboardController = null)
    {
        _options = options;
        _mouse = mouseController;
        _keyboard = keyboardController ?? new Win32KeyboardController(log);
        _log = log ?? NullLog.Instance;

        _guard = new ConnectionGuard(options, _log);
        _guard.Changed += (_, _) => RaiseStatusChanged();

        _certificate = CertificateManager.GetOrCreate(
            options.DataDirectory,
            LocalAddressProvider.GetCandidates().Select(a => a.Address),
            _log);

        CertificatePin = AuthCrypto.PinOfCertificate(_certificate.Certificate);

        _identity = ServerIdentity.GetOrCreate(options.DataDirectory, _log);
        _devices = DeviceKeyStore.Load(options.DataDirectory, _log);
        _devices.Changed += (_, _) => RaiseStatusChanged();

        AdvertisedAddress = options.AdvertisedAddress ?? LocalAddressProvider.GetPreferredAddress();
    }

    public event EventHandler<ServerStatus>? StatusChanged;

    public event EventHandler<FileTransferResult>? FileReceived;

    public event EventHandler<FileSendProgress>? FileSendProgressChanged;

    public AirTouchOptions Options => _options;

    /// <summary>Address printed in the QR code; may be null when the machine has no usable LAN IPv4.</summary>
    public string? AdvertisedAddress { get; private set; }

    public string CertificateFingerprint => _certificate.Fingerprint;

    /// <summary>Certificate pin (SPKI SHA-256, base64url) echoed into the QR code.</summary>
    public string CertificatePin { get; }

    /// <summary>Stable identity of this installation; the phone keys its certificate pin to it.</summary>
    public string Sid => _identity.Id;

    public IReadOnlyList<TrustedDevice> TrustedDevices => _devices.All;

    public bool IsRunning
    {
        get
        {
            lock (_gate)
            {
                return _listener is not null;
            }
        }
    }

    public string BuildQrPayload() => BuildUrl(AdvertisedAddress ?? "127.0.0.1");

    private string BuildUrl(string address) =>
        Protocol.BuildUrl(address, _options.Port, _guard.Token, _identity.Id, CertificatePin, _options.UseTls);

    public void SetAdvertisedAddress(string address) => AdvertisedAddress = address;

    public ServerStatus GetStatus()
    {
        lock (_gate)
        {
            return new ServerStatus(
                IsRunning: _listener is not null,
                Port: _options.Port,
                AdvertisedAddress: AdvertisedAddress,
                Url: BuildUrl(AdvertisedAddress ?? "127.0.0.1"),
                Token: _guard.Token,
                TokenConsumed: _guard.TokenConsumed,
                Sid: _identity.Id,
                ClientAddress: _client?.ConnectionInfo.ClientIpAddress,
                ClientName: _clientDeviceName,
                ClientKeyId: _clientKeyId,
                ClientAuthMode: _clientAuthMode,
                PendingCount: _guard.PendingCount,
                TrustedDevices: _devices.All,
                BannedAddresses: _guard.BannedAddresses,
                DownloadDirectory: _options.DownloadDirectory,
                CertificateFingerprint: _certificate.Fingerprint,
                CertificatePin: CertificatePin,
                MoveCommands: Interlocked.Read(ref _moveCommands),
                ClickCommands: Interlocked.Read(ref _clickCommands),
                ScrollCommands: Interlocked.Read(ref _scrollCommands),
                ResetCommands: Interlocked.Read(ref _resetCommands),
                KeyCommands: Interlocked.Read(ref _keyCommands),
                TextCommands: Interlocked.Read(ref _textCommands),
                BytesReceived: Interlocked.Read(ref _bytesReceived),
                BytesSent: Interlocked.Read(ref _bytesSent),
                SendInputFailures: _mouse.SendInputFailures,
                KeyInputFailures: _keyboard.SendInputFailures);
        }
    }

    public void Start()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        lock (_gate)
        {
            if (_listener is not null)
            {
                return;
            }
        }

        var scheme = _options.UseTls ? "wss" : "ws";
        var listener = new WebSocketServer($"{scheme}://0.0.0.0:{_options.Port}")
        {
            RestartAfterListenError = false,
        };

        if (_options.UseTls)
        {
            if (_certificate.IsEphemeralKey)
            {
                // Serving TLS with an in-memory key is impossible on Windows: Schannel reports
                // "the platform does not support ephemeral keys". Failing loudly beats opening a
                // port that can never complete a handshake (and beats quietly dropping to plaintext).
                throw new AirTouchServerException(
                    "无法启用 wss：证书私钥只能以内存临时密钥加载，而 Windows Schannel 不支持临时密钥。\n" +
                    "请用对 %APPDATA%\\Microsoft\\Crypto 有写权限的账户运行本程序（管理员或普通桌面会话均可），" +
                    "或删除数据目录中的 airtouch-server.pfx 后重试。");
            }

            listener.Certificate = _certificate.Certificate;
            listener.EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 |
                                           System.Security.Authentication.SslProtocols.Tls13;
        }
        else
        {
            // Reachable only from the test harness (see AirTouchOptions.UseTls), never from the UI
            // or an environment variable. Plaintext is acceptable on a loopback test socket only.
            _log.Error("!!! 已禁用 TLS：流量未加密，仅供回环测试使用 !!!");
        }

        try
        {
            listener.Start(OnConnectionOpened);
        }
        catch (Exception ex)
        {
            listener.Dispose();
            throw new AirTouchServerException(
                $"无法在端口 {_options.Port} 上启动 WSS 服务：{ex.Message}（端口可能已被占用）",
                ex);
        }

        lock (_gate)
        {
            _listener = listener;
            _lastActivityUtc = DateTimeOffset.UtcNow;
            _lastPingUtc = DateTimeOffset.UtcNow;
        }

        _watchdog = new System.Threading.Timer(WatchdogTick, null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));

        _log.Info($"服务已启动：wss://{AdvertisedAddress ?? "127.0.0.1"}:{_options.Port}{Protocol.WebSocketPath}");
        _log.Info($"服务器身份 {_identity.Id}，证书指纹固定值 {CertificatePin}");
        _log.Info($"接收文件将保存到：{_options.DownloadDirectory}（单文件上限 {_options.MaxFileSize / 1024 / 1024} MiB）");
        RaiseStatusChanged();
    }

    public void Stop()
    {
        WebSocketServer? listener;
        IWebSocketConnection? client;
        IncomingFileTransfer? transfer;

        lock (_gate)
        {
            listener = _listener;
            _listener = null;
            client = _client;
            _client = null;
            _clientKeyId = null;
            _clientDeviceName = null;
            _clientAuthMode = null;
            _pending.Clear();
        }

        lock (_transferGate)
        {
            transfer = _incoming;
            _incoming = null;
        }

        transfer?.Abort("服务停止");

        _watchdog?.Dispose();
        _watchdog = null;

        try
        {
            client?.Close();
        }
        catch (Exception)
        {
            // ignored
        }

        try
        {
            listener?.Dispose();
        }
        catch (Exception ex)
        {
            _log.Warn($"关闭监听器时出错：{ex.Message}");
        }

        _log.Info("服务已停止");
        RaiseStatusChanged();
    }

    /// <summary>Disconnects the current phone, lifts every ban and issues a fresh token.</summary>
    public void ResetSecurity(string reason)
    {
        IWebSocketConnection? client;
        IncomingFileTransfer? transfer;

        lock (_gate)
        {
            client = _client;
            _client = null;
            _clientKeyId = null;
            _clientDeviceName = null;
            _clientAuthMode = null;
        }

        lock (_transferGate)
        {
            transfer = _incoming;
            _incoming = null;
        }

        transfer?.Abort("安全状态重置");

        try
        {
            client?.Close(1000);
        }
        catch (Exception)
        {
            // ignored
        }

        _guard.Reset(reason);
        RaiseStatusChanged();
    }

    /// <summary>
    /// Forgets one trusted phone. If that phone is connected right now it is disconnected with a
    /// <c>trust_revoked</c> error, so revoking never leaves a live session running on a key the PC no
    /// longer trusts.
    /// </summary>
    public bool RevokeDevice(string keyId)
    {
        if (!_devices.Revoke(keyId))
        {
            return false;
        }

        DisconnectIfActive(keyId, "该设备的信任已被电脑端撤销");
        _log.Info($"已撤销设备信任：{keyId}");
        RaiseStatusChanged();
        return true;
    }

    /// <summary>Forgets every trusted phone (each phone must scan a new QR code afterwards).</summary>
    public int RevokeAllDevices()
    {
        var count = _devices.RevokeAll();
        if (count > 0)
        {
            DisconnectIfActive(null, "电脑端已清除全部信任设备");
            _log.Info($"已清除全部信任设备（{count} 台）");
            RaiseStatusChanged();
        }

        return count;
    }

    private void DisconnectIfActive(string? keyId, string message)
    {
        IWebSocketConnection? client = null;

        lock (_gate)
        {
            if (_client is not null &&
                (keyId is null || string.Equals(_clientKeyId, keyId, StringComparison.Ordinal)))
            {
                client = _client;
                _client = null;
                _clientKeyId = null;
                _clientDeviceName = null;
                _clientAuthMode = null;
            }
        }

        if (client is null)
        {
            return;
        }

        TrySend(client, Protocol.BuildError("trust_revoked", message));
        _ = Task.Delay(200).ContinueWith(_ => TryClose(client, 1008), TaskScheduler.Default);
        _guard.MarkDisconnected(null);
    }

    /// <summary>Issues a fresh pairing token without touching the trust list.</summary>
    public string RotateToken() => _guard.RotateToken();

    public bool Unban(string address) => _guard.Unban(address);

    /// <summary>Pushes a file to the connected phone, chunk by chunk, with progress reporting.</summary>
    public async Task<FileSendResult> SendFileAsync(string path, CancellationToken cancellationToken = default)
    {
        IWebSocketConnection? client;

        lock (_gate)
        {
            client = _client;
        }

        if (client is null)
        {
            return new FileSendResult(false, Path.GetFileName(path), 0, "当前没有已连接的设备");
        }

        var outgoing = OutgoingFileTransfer.TryOpen(path, _options.MaxFileSize, out var openError);
        if (outgoing is null)
        {
            _log.Error($"发送文件失败：{openError}");
            return new FileSendResult(false, Path.GetFileName(path), 0, openError ?? "无法打开文件");
        }

        using (outgoing)
        {
            var fileId = Guid.NewGuid().ToString("N");
            _log.Info($"开始发送文件 {outgoing.FileName}（{outgoing.Length} 字节）到手机");
            var stopwatch = Stopwatch.StartNew();

            try
            {
                await client.Send(Protocol.BuildFileHeader(outgoing.FileName, outgoing.Length, fileId))
                    .ConfigureAwait(false);

                var buffer = new byte[_options.ChunkSize];
                long sent = 0;
                var lastReport = 0L;

                while (sent < outgoing.Length)
                {
                    cancellationToken.ThrowIfCancellationRequested();

                    if (!client.IsAvailable)
                    {
                        throw new IOException("连接已断开");
                    }

                    var read = await outgoing.Stream.ReadAsync(
                        buffer.AsMemory(0, buffer.Length),
                        cancellationToken).ConfigureAwait(false);

                    if (read <= 0)
                    {
                        break;
                    }

                    // Fleck queues the array reference, so each chunk needs its own buffer.
                    var chunk = new byte[read];
                    Buffer.BlockCopy(buffer, 0, chunk, 0, read);

                    await client.Send(chunk).ConfigureAwait(false);

                    sent += read;
                    Interlocked.Add(ref _bytesSent, read);

                    if (sent - lastReport >= 1024 * 1024 || sent == outgoing.Length)
                    {
                        lastReport = sent;
                        FileSendProgressChanged?.Invoke(this, new FileSendProgress(outgoing.FileName, sent, outgoing.Length));
                    }
                }

                await client.Send(Protocol.BuildFileEnd(fileId)).ConfigureAwait(false);

                if (sent != outgoing.Length)
                {
                    var message = $"发送不完整（{sent}/{outgoing.Length} 字节）";
                    _log.Error(message);
                    return new FileSendResult(false, outgoing.FileName, sent, message);
                }

                stopwatch.Stop();
                _log.Info($"文件发送完成：{outgoing.FileName}，{sent} 字节，用时 {stopwatch.ElapsedMilliseconds} ms");
                return new FileSendResult(true, outgoing.FileName, sent, "发送完成");
            }
            catch (Exception ex)
            {
                _log.Error($"发送文件出错：{outgoing.FileName}", ex);
                return new FileSendResult(false, outgoing.FileName, 0, ex.Message);
            }
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        Stop();
        _certificate.Certificate.Dispose();
    }

    private void OnConnectionOpened(IWebSocketConnection socket)
    {
        string address;
        string rawPath;
        IEnumerable<KeyValuePair<string, string>>? headers;

        try
        {
            var info = socket.ConnectionInfo;
            address = info.ClientIpAddress ?? "unknown";
            rawPath = info.Path ?? string.Empty;
            headers = info.Headers;
        }
        catch (Exception ex)
        {
            _log.Error("读取连接信息失败", ex);
            TryClose(socket, 1011);
            return;
        }

        socket.OnError = exception =>
        {
            _log.Error($"连接出错（{address}）", exception);
            HandleDisconnected(socket, address);
        };

        socket.OnClose = () => HandleDisconnected(socket, address);

        socket.OnOpen = () => HandleClientReady(socket, address, rawPath, headers);
    }

    private void HandleClientReady(
        IWebSocketConnection socket,
        string address,
        string rawPath,
        IEnumerable<KeyValuePair<string, string>>? headers)
    {
        var outcome = _guard.TryAuthorize(address, rawPath, headers);

        if (!outcome.IsAllowed)
        {
            Reject(socket, outcome);
            return;
        }

        var pending = new PendingAuth
        {
            Socket = socket,
            Address = address,
            Mode = outcome.Mode,
            Nonce = AuthCrypto.NewNonce(),
            ExpiresUtc = DateTimeOffset.UtcNow + _options.AuthTimeout,
        };

        lock (_gate)
        {
            _pending[socket] = pending;
        }

        socket.OnMessage = message => HandleTextMessage(socket, message);
        socket.OnBinary = data => HandleBinaryFrame(socket, data);
        socket.OnPing = payload =>
        {
            Touch();
            try
            {
                _ = socket.SendPong(payload);
            }
            catch (Exception)
            {
                // The socket is already gone; OnClose will clean up.
            }
        };
        socket.OnPong = _ => Touch();

        _log.Info($"连接已进入待认证状态（{address}，模式 {(pending.Mode == AuthMode.Pairing ? "配对" : "重连")}）");

        // The challenge is the whole point of the exchange: the client signs this nonce, so a captured
        // auth_req can never be replayed and a stale signature can never be reused.
        TrySend(socket, Protocol.BuildAuthChallenge(
            _identity.Id,
            pending.Nonce,
            AuthCrypto.UnixNow(),
            (int)_options.AuthTimeout.TotalSeconds,
            pending.Mode == AuthMode.Pairing ? "pair" : "resume"));

        RaiseStatusChanged();
    }

    private void Reject(IWebSocketConnection socket, AuthOutcome outcome)
    {
        _log.Warn($"拒绝连接：{outcome.ErrorCode}（{outcome.Message}）");
        TrySend(socket, Protocol.BuildError(outcome.ErrorCode, outcome.Message));

        // Give the error frame a moment to reach the peer before the socket disappears.
        var closeCode = outcome.CloseCode;
        _ = Task.Delay(250).ContinueWith(
            _ => TryClose(socket, closeCode),
            TaskScheduler.Default);
    }

    private void HandleDisconnected(IWebSocketConnection socket, string address)
    {
        var wasActive = false;
        var wasPending = false;

        lock (_gate)
        {
            if (_pending.Remove(socket))
            {
                wasPending = true;
            }

            if (ReferenceEquals(_client, socket))
            {
                _client = null;
                _clientKeyId = null;
                _clientDeviceName = null;
                _clientAuthMode = null;
                wasActive = true;
            }
        }

        if (wasPending)
        {
            _guard.EndPending();
        }

        IncomingFileTransfer? transfer = null;
        lock (_transferGate)
        {
            if (_incoming is not null)
            {
                transfer = _incoming;
                _incoming = null;
            }
        }

        if (transfer is not null)
        {
            transfer.Abort("连接中断");
            _log.Warn($"传输中断，已删除临时文件：{transfer.FileName}");
        }

        if (wasActive)
        {
            // The phone latches Ctrl/Shift/Alt/Win for one-handed shortcuts (the sticky keys in
            // docs/PROTOCOL.md §2.6). If the socket dies while one of them is down and nobody lets go,
            // the PC is left with a physically stuck modifier and every later real keystroke becomes a
            // shortcut. Releasing here is the only place that cannot be skipped.
            var released = _keyboard.ReleaseAll();
            if (released > 0)
            {
                _log.Warn($"连接结束，已释放 {released} 个仍按下的按键");
            }

            _guard.MarkDisconnected(address);
            RaiseStatusChanged();
        }
    }

    private void HandleTextMessage(IWebSocketConnection socket, string message)
    {
        PendingAuth? pending;

        lock (_gate)
        {
            _pending.TryGetValue(socket, out pending);
        }

        if (pending is not null)
        {
            HandlePendingMessage(socket, pending, message);
            return;
        }

        if (!IsActiveClient(socket))
        {
            return;
        }

        Touch();

        var command = Protocol.Parse(message);
        if (command is null)
        {
            _log.Warn($"收到无法解析的文本帧：{Truncate(message)}");
            return;
        }

        switch (command.Kind)
        {
            case MessageKind.Move:
                _mouse.Move(command.Dx, command.Dy);
                Interlocked.Increment(ref _moveCommands);
                break;

            case MessageKind.Click:
                _mouse.Click(command.Button);
                Interlocked.Increment(ref _clickCommands);
                break;

            case MessageKind.Scroll:
                _mouse.Scroll(command.Amount);
                Interlocked.Increment(ref _scrollCommands);
                break;

            case MessageKind.Reset:
                _mouse.ResetToCenter();
                Interlocked.Increment(ref _resetCommands);
                break;

            case MessageKind.Key:
                _keyboard.Key(command.VirtualKey, command.KeyDown, command.KeyExtended);
                Interlocked.Increment(ref _keyCommands);
                break;

            case MessageKind.Text:
                // 不经过 _pressed 记账：text 帧没有按键状态，每个字符都是当场按下并立刻抬起。
                _keyboard.Text(command.Text);
                Interlocked.Increment(ref _textCommands);
                break;

            case MessageKind.FileHeader:
                BeginIncomingTransfer(socket, command);
                break;

            case MessageKind.FileEnd:
                FinishIncomingTransfer(socket, "对端声明传输结束");
                break;

            case MessageKind.Hello:
                lock (_gate)
                {
                    _clientDeviceName = string.IsNullOrWhiteSpace(command.DeviceName)
                        ? command.AppVersion
                        : command.DeviceName;
                }

                _log.Info($"设备信息：{command.DeviceName ?? "未知设备"}（App {command.AppVersion ?? "未知"}）");
                RaiseStatusChanged();
                break;

            case MessageKind.Ping:
                TrySend(socket, Protocol.BuildPong());
                break;

            case MessageKind.Pong:
                break;

            case MessageKind.AuthReq:
                // Already authenticated on this socket; a second auth_req is a protocol error, not a
                // reason to drop a working session.
                _log.Warn("已认证的连接上收到多余的 auth_req，已忽略");
                break;

            default:
                _log.Warn($"忽略未知指令：{command.RawType}");
                break;
        }
    }

    // ---------------- 认证 ----------------

    private void HandlePendingMessage(IWebSocketConnection socket, PendingAuth pending, string message)
    {
        var command = Protocol.Parse(message);
        if (command is null)
        {
            _log.Warn($"待认证连接上收到无法解析的文本帧：{Truncate(message)}");
            return;
        }

        if (command.Kind != MessageKind.AuthReq)
        {
            // Nothing else is accepted before the key proof succeeds — in particular a control frame
            // must never be able to register itself as a trusted device.
            _log.Warn($"待认证连接上忽略了 {command.RawType} 帧（必须先完成 auth_req）");
            return;
        }

        HandleAuthRequest(socket, pending, command);
    }

    private void HandleAuthRequest(IWebSocketConnection socket, PendingAuth pending, ControlMessage command)
    {
        if (DateTimeOffset.UtcNow > pending.ExpiresUtc)
        {
            FinishPending(socket, "auth_required", "认证超时，请重新连接", 1008);
            return;
        }

        if (command.AuthVersion != AuthCrypto.AuthVersion)
        {
            _log.Warn($"auth_req 的协议版本不受支持：{command.AuthVersion}");
            FinishPending(socket, "auth_unsupported", "认证协议版本不受支持，请更新 App", 1008);
            return;
        }

        if (string.IsNullOrEmpty(command.KeyId) ||
            !AuthCrypto.TryFromBase64Url(command.KeyId, out var keyIdBytes) ||
            keyIdBytes.Length != AuthCrypto.FingerprintBytes)
        {
            FailAuth(socket, pending, "bad_key", "keyId 缺失或格式错误", "keyId 格式错误");
            return;
        }

        var clockSkew = Math.Abs(AuthCrypto.UnixNow() - command.Timestamp);
        if (command.Timestamp <= 0 || clockSkew > (long)_options.AuthClockSkew.TotalSeconds)
        {
            // Soft failure: the phone's clock is off, which is fixable (the challenge carries
            // serverTime), so this must not burn the ban budget.
            _log.Warn($"auth_req 时间戳超出允许范围（偏差 {clockSkew} 秒），已拒绝但不计入封禁");
            FinishPending(socket, "stale_auth", $"时间戳偏差 {clockSkew} 秒，请检查手机时间", 1008);
            return;
        }

        if (string.IsNullOrEmpty(command.Signature) ||
            !AuthCrypto.TryFromBase64Url(command.Signature, out var signature))
        {
            FailAuth(socket, pending, "bad_signature", "签名字段缺失或格式错误", "签名字段格式错误");
            return;
        }

        string publicKey;
        var deviceName = command.DeviceName;
        var wasPairing = pending.Mode == AuthMode.Pairing;

        if (wasPairing)
        {
            // 1) the token must still be the live one — another phone may have consumed it between this
            //    socket opening and its auth_req arriving.
            var tokenOutcome = _guard.CheckPairingToken(pending.Address, command.Token);
            if (!tokenOutcome.IsAllowed)
            {
                _log.Warn($"配对请求的 token 校验失败：{tokenOutcome.ErrorCode}");
                FinishPending(socket, tokenOutcome.ErrorCode, tokenOutcome.Message, tokenOutcome.CloseCode);
                return;
            }

            // 2) the public key must be well formed, P-256, and match the claimed key id.
            if (string.IsNullOrEmpty(command.PublicKey) ||
                !AuthCrypto.TryFromBase64Url(command.PublicKey, out var spki) ||
                !AuthCrypto.TryImportPublicKey(spki, out var imported) ||
                imported is null)
            {
                FailAuth(socket, pending, "bad_key", "公钥缺失、格式错误或不是 P-256", "公钥格式错误");
                return;
            }

            imported.Dispose();

            var computed = AuthCrypto.KeyIdOfPublicKey(spki);
            if (!string.Equals(computed, command.KeyId, StringComparison.Ordinal))
            {
                FailAuth(socket, pending, "bad_key", "keyId 与公钥不匹配", "keyId 与公钥不匹配");
                return;
            }

            publicKey = command.PublicKey;
        }
        else
        {
            var known = _devices.Find(command.KeyId);
            if (known is null)
            {
                // Not a brute-force attempt: the PC may simply have forgotten this phone (data
                // directory wiped, "clear trusted devices"), and the fix is to scan a QR code again.
                _log.Warn($"重连的设备密钥不在信任列表中：{command.KeyId}");
                FinishPending(socket, "key_unknown", "这台设备未被信任，请重新扫码配对", 1008);
                return;
            }

            publicKey = known.PublicKey;
            deviceName ??= known.Name;
        }

        var canonical = AuthCrypto.Canonical(_identity.Id, pending.Nonce, command.KeyId, command.Timestamp);

        if (!AuthCrypto.TryFromBase64Url(publicKey, out var keyBytes) ||
            !AuthCrypto.VerifySignature(keyBytes, canonical, signature))
        {
            FailAuth(socket, pending, "sig_invalid", "签名校验失败", "签名校验失败");
            return;
        }

        // Past this point the peer has proven possession of the private key.
        if (wasPairing)
        {
            _guard.MarkPaired(pending.Address);
            _devices.Upsert(command.KeyId, publicKey, deviceName ?? "Android 设备", pending.Address);
        }
        else
        {
            _devices.Touch(command.KeyId, pending.Address);
        }

        CompleteAuthentication(socket, pending, command.KeyId, deviceName, wasPairing);
    }

    private void CompleteAuthentication(
        IWebSocketConnection socket,
        PendingAuth pending,
        string keyId,
        string? deviceName,
        bool wasPairing)
    {
        IWebSocketConnection? replaced = null;
        AuthOutcome? busy = null;

        lock (_gate)
        {
            if (_client is not null && !ReferenceEquals(_client, socket))
            {
                if (string.Equals(_clientKeyId, keyId, StringComparison.Ordinal))
                {
                    // Same phone reconnecting (typically right after a Wi-Fi drop) while the old socket
                    // has not been reaped yet: the new socket wins and the stale one is closed, so a
                    // dropped link never locks the owner out with "busy".
                    replaced = _client;
                }
                else
                {
                    // A different trusted phone: keep the current owner, politely refuse this one.
                    busy = new AuthOutcome(AuthResult.Busy, "已有其他设备处于连接状态");
                }
            }

            if (busy is null)
            {
                _client = socket;
                _clientKeyId = keyId;
                _clientDeviceName = deviceName;
                _clientAuthMode = wasPairing ? "pair" : "resume";
                _pending.Remove(socket);
                _lastActivityUtc = DateTimeOffset.UtcNow;
                _lastPingUtc = DateTimeOffset.UtcNow;
            }
        }

        if (busy is not null)
        {
            _log.Warn($"拒绝连接：{busy.ErrorCode}（已有 {_clientKeyId} 在连接）");
            FinishPending(socket, busy.ErrorCode, busy.Message, busy.CloseCode);
            return;
        }

        _guard.EndPending();
        _guard.MarkAuthenticated(keyId, deviceName, pending.Address);

        if (replaced is not null)
        {
            _log.Info("同一台设备建立了新会话，正在关闭旧连接");
            TrySend(replaced, Protocol.BuildError("replaced", "同一设备已在新的连接上恢复会话"));
            _ = Task.Delay(150).ContinueWith(_ => TryClose(replaced, 1001), TaskScheduler.Default);
        }

        _log.Info($"设备已连接：{pending.Address}（{deviceName ?? "未知设备"}，{(wasPairing ? "首次配对" : "已信任设备重连")}）");
        TrySend(socket, Protocol.BuildWelcome(
            Version,
            AdvertisedAddress ?? "127.0.0.1",
            _identity.Id,
            ScreenInfo.PrimaryScreenRefreshHz(),
            keyId,
            wasPairing ? "pair" : "resume"));

        RaiseStatusChanged();
    }

    private void FailAuth(IWebSocketConnection socket, PendingAuth pending, string code, string message, string reason)
    {
        _guard.RegisterAuthFailure(pending.Address, reason);
        FinishPending(socket, code, message, 1008);
    }

    /// <summary>Ends a pending exchange: sends the error, releases the slot and closes the socket.</summary>
    private void FinishPending(IWebSocketConnection socket, string code, string message, int closeCode)
    {
        var wasPending = false;

        lock (_gate)
        {
            wasPending = _pending.Remove(socket);
        }

        if (wasPending)
        {
            _guard.EndPending();
        }

        _log.Warn($"认证未通过：{code}（{message}）");
        TrySend(socket, Protocol.BuildError(code, message));
        _ = Task.Delay(150).ContinueWith(_ => TryClose(socket, closeCode), TaskScheduler.Default);
        RaiseStatusChanged();
    }

    // ---------------- 文件 ----------------

    private void BeginIncomingTransfer(IWebSocketConnection socket, ControlMessage command)
    {
        IncomingFileTransfer? transfer;
        string? error;

        lock (_transferGate)
        {
            _incoming?.Abort("被新的传输中断");
            _incoming = null;

            transfer = IncomingFileTransfer.TryStart(
                _options.DownloadDirectory,
                command.FileName,
                command.FileSize,
                _options.MaxFileSize,
                out error);

            _incoming = transfer;
        }

        if (transfer is null)
        {
            _log.Warn($"拒绝接收文件（{command.FileName}）：{error}");
            TrySend(socket, Protocol.BuildFileAck(
                SafeFileName.Sanitize(command.FileName),
                ok: false,
                bytes: 0,
                savedPath: null,
                message: error ?? "无法开始接收"));
            return;
        }

        _log.Info($"开始接收文件：{command.FileName} → {transfer.FileName}（{command.FileSize} 字节）");
    }

    private void HandleBinaryFrame(IWebSocketConnection socket, byte[] data)
    {
        if (!IsActiveClient(socket))
        {
            return;
        }

        Touch();

        IncomingFileTransfer? completed = null;
        IncomingFileTransfer? failed = null;
        string? error = null;

        lock (_transferGate)
        {
            var transfer = _incoming;
            if (transfer is null)
            {
                _log.Warn("收到没有 file_header 的二进制帧，已忽略");
                return;
            }

            Interlocked.Add(ref _bytesReceived, data.Length);

            if (!transfer.Append(data, 0, data.Length, out error))
            {
                failed = transfer;
                _incoming = null;
            }
            else if (transfer.IsComplete)
            {
                completed = transfer;
                _incoming = null;
            }
        }

        if (failed is not null)
        {
            var name = failed.FileName;
            failed.Abort(error ?? "接收失败");
            _log.Error($"接收文件失败：{name}（{error}）");
            TrySend(socket, Protocol.BuildFileAck(name, ok: false, bytes: failed.ReceivedBytes, savedPath: null, message: error ?? "接收失败"));
            return;
        }

        if (completed is not null)
        {
            CompleteIncomingTransfer(socket, completed);
        }
    }

    private void FinishIncomingTransfer(IWebSocketConnection socket, string reason)
    {
        IncomingFileTransfer? transfer;

        lock (_transferGate)
        {
            transfer = _incoming;
            _incoming = null;
        }

        if (transfer is null)
        {
            return;
        }

        CompleteIncomingTransfer(socket, transfer, reason);
    }

    private void CompleteIncomingTransfer(IWebSocketConnection socket, IncomingFileTransfer transfer, string reason = "字节数校验通过")
    {
        var result = transfer.Complete();
        transfer.Dispose();

        if (result.Success)
        {
            _log.Info($"文件接收完成：{result.SavedPath}（{result.Bytes} 字节，{reason}）");
        }
        else
        {
            _log.Error($"文件接收失败：{result.FileName} - {result.Message}");
        }

        TrySend(socket, Protocol.BuildFileAck(
            result.FileName,
            result.Success,
            result.Bytes,
            result.SavedPath,
            result.Message));

        FileReceived?.Invoke(this, result);
        RaiseStatusChanged();
    }

    // ---------------- 看门狗 ----------------

    private void WatchdogTick(object? state)
    {
        List<PendingAuth> expired = new();
        IWebSocketConnection? client;
        var shouldPing = false;
        var timedOut = false;

        lock (_gate)
        {
            if (_pending.Count > 0)
            {
                var now = DateTimeOffset.UtcNow;
                foreach (var pair in _pending.Where(p => now > p.Value.ExpiresUtc).ToArray())
                {
                    _pending.Remove(pair.Key);
                    expired.Add(pair.Value);
                }
            }

            client = _client;

            if (client is not null)
            {
                var now = DateTimeOffset.UtcNow;

                if (now - _lastActivityUtc > _options.IdleTimeout)
                {
                    timedOut = true;
                }
                else if (now - _lastPingUtc >= _options.PingInterval)
                {
                    _lastPingUtc = now;
                    shouldPing = true;
                }
            }
        }

        foreach (var pending in expired)
        {
            _guard.EndPending();
            _log.Warn($"待认证连接超过 {_options.AuthTimeout.TotalSeconds:0} 秒未提交 auth_req，已关闭（{pending.Address}）");
            TrySend(pending.Socket, Protocol.BuildError("auth_required", "认证超时，请重新连接"));
            TryClose(pending.Socket, 1008);
        }

        if (timedOut)
        {
            _log.Warn($"连接超过 {_options.IdleTimeout.TotalSeconds:0} 秒无响应，主动断开");
            if (client is not null)
            {
                TryClose(client, 1001);
            }

            return;
        }

        if (shouldPing && client is not null)
        {
            try
            {
                _ = client.SendPing(Array.Empty<byte>());
            }
            catch (Exception ex)
            {
                _log.Warn($"发送心跳失败：{ex.Message}");
            }
        }
    }

    private void Touch()
    {
        lock (_gate)
        {
            _lastActivityUtc = DateTimeOffset.UtcNow;
        }
    }

    private bool IsActiveClient(IWebSocketConnection socket)
    {
        lock (_gate)
        {
            return ReferenceEquals(_client, socket);
        }
    }

    private void TrySend(IWebSocketConnection socket, string message)
    {
        try
        {
            if (socket.IsAvailable)
            {
                _ = socket.Send(message);
            }
        }
        catch (Exception ex)
        {
            _log.Warn($"发送消息失败：{ex.Message}");
        }
    }

    private static void TryClose(IWebSocketConnection socket, int closeCode)
    {
        try
        {
            socket.Close(closeCode);
        }
        catch (Exception)
        {
            // ignored
        }
    }

    private void RaiseStatusChanged()
    {
        try
        {
            StatusChanged?.Invoke(this, GetStatus());
        }
        catch (Exception ex)
        {
            _log.Error("状态通知回调出错", ex);
        }
    }

    private static string Truncate(string value) =>
        value.Length <= 120 ? value : value[..120] + "…";
}
