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
    string? BoundAddress,
    string? ClientAddress,
    string? ClientName,
    IReadOnlyCollection<string> BannedAddresses,
    string DownloadDirectory,
    string CertificateFingerprint,
    long MoveCommands,
    long ClickCommands,
    long ScrollCommands,
    long ResetCommands,
    long BytesReceived,
    long BytesSent);

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
/// The WSS endpoint: authenticates the phone, turns control frames into cursor movement and moves
/// files in both directions.
/// </summary>
/// <remarks>
/// Threading: Fleck raises all socket callbacks on its own worker threads, the WPF window talks to us
/// from the dispatcher thread and the watchdog runs on a timer thread. Connection state lives behind
/// <c>_gate</c>, the in-flight incoming transfer behind <c>_transferGate</c>, and the two locks are
/// never held at the same time so a slow disk can never block a mouse command.
/// </remarks>
public sealed class AirTouchServer : IDisposable
{
    public const string Version = "1.0.0";

    private readonly AirTouchOptions _options;
    private readonly IMouseController _mouse;
    private readonly IAirTouchLog _log;
    private readonly ConnectionGuard _guard;
    private readonly ServerCertificate _certificate;

    private readonly object _gate = new();
    private readonly object _transferGate = new();

    private WebSocketServer? _listener;
    private IWebSocketConnection? _client;
    private IncomingFileTransfer? _incoming;
    private System.Threading.Timer? _watchdog;
    private DateTimeOffset _lastActivityUtc;
    private DateTimeOffset _lastPingUtc;
    private string? _clientName;
    private bool _disposed;

    private long _moveCommands;
    private long _clickCommands;
    private long _scrollCommands;
    private long _resetCommands;
    private long _bytesReceived;
    private long _bytesSent;

    public AirTouchServer(AirTouchOptions options, IMouseController mouseController, IAirTouchLog? log = null)
    {
        _options = options;
        _mouse = mouseController;
        _log = log ?? NullLog.Instance;

        _guard = new ConnectionGuard(options, _log);
        _guard.Changed += (_, _) => RaiseStatusChanged();

        _certificate = CertificateManager.GetOrCreate(
            options.DataDirectory,
            LocalAddressProvider.GetCandidates().Select(a => a.Address),
            _log);

        AdvertisedAddress = options.AdvertisedAddress ?? LocalAddressProvider.GetPreferredAddress();
    }

    public event EventHandler<ServerStatus>? StatusChanged;

    public event EventHandler<FileTransferResult>? FileReceived;

    public event EventHandler<FileSendProgress>? FileSendProgressChanged;

    public AirTouchOptions Options => _options;

    /// <summary>Address printed in the QR code; may be null when the machine has no usable LAN IPv4.</summary>
    public string? AdvertisedAddress { get; private set; }

    public string CertificateFingerprint => _certificate.Fingerprint;

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

    public string BuildQrPayload() =>
        Protocol.BuildUrl(AdvertisedAddress ?? "127.0.0.1", _options.Port, _guard.Token, _options.UseTls);

    public void SetAdvertisedAddress(string address) => AdvertisedAddress = address;

    public ServerStatus GetStatus()
    {
        lock (_gate)
        {
            return new ServerStatus(
                IsRunning: _listener is not null,
                Port: _options.Port,
                AdvertisedAddress: AdvertisedAddress,
                Url: Protocol.BuildUrl(AdvertisedAddress ?? "127.0.0.1", _options.Port, _guard.Token, _options.UseTls),
                Token: _guard.Token,
                TokenConsumed: _guard.TokenConsumed,
                BoundAddress: _guard.BoundAddress,
                ClientAddress: _client?.ConnectionInfo.ClientIpAddress,
                ClientName: _clientName,
                BannedAddresses: _guard.BannedAddresses,
                DownloadDirectory: _options.DownloadDirectory,
                CertificateFingerprint: _certificate.Fingerprint,
                MoveCommands: Interlocked.Read(ref _moveCommands),
                ClickCommands: Interlocked.Read(ref _clickCommands),
                ScrollCommands: Interlocked.Read(ref _scrollCommands),
                ResetCommands: Interlocked.Read(ref _resetCommands),
                BytesReceived: Interlocked.Read(ref _bytesReceived),
                BytesSent: Interlocked.Read(ref _bytesSent));
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
                    "或删除数据目录中的 airtouch-server.pfx 后重试。\n" +
                    "仅本机排障时可设置环境变量 AIRTOUCH_INSECURE_NO_TLS=1 使用未加密的 ws://（切勿在局域网使用）。");
            }

            listener.Certificate = _certificate.Certificate;
            listener.EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 |
                                           System.Security.Authentication.SslProtocols.Tls13;
        }
        else
        {
            _log.Error("!!! 已禁用 TLS（AIRTOUCH_INSECURE_NO_TLS=1）：流量未加密，仅限本机排障使用 !!!");
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

    /// <summary>Disconnects the current phone, unbinds the IP and lifts every ban.</summary>
    public void ResetSecurity(string reason)
    {
        IWebSocketConnection? client;
        IncomingFileTransfer? transfer;

        lock (_gate)
        {
            client = _client;
            _client = null;
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

    /// <summary>Issues a fresh pairing token without touching the IP binding.</summary>
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

        // Two sockets can pass the stateless check above at the same time; the winner is whoever
        // gets here first, so re-check the single-session rule while holding the lock.
        var busy = false;
        lock (_gate)
        {
            if (_client is not null)
            {
                busy = true;
            }
            else
            {
                _client = socket;
                _clientName = null;
                _lastActivityUtc = DateTimeOffset.UtcNow;
                _lastPingUtc = DateTimeOffset.UtcNow;
            }
        }

        if (busy)
        {
            Reject(socket, new AuthOutcome(AuthResult.Busy, "已有设备处于连接状态"));
            return;
        }

        _guard.MarkConnected(address);

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

        _log.Info($"设备已连接：{address}");
        TrySend(socket, Protocol.BuildWelcome(
            Version,
            AdvertisedAddress ?? "127.0.0.1",
            _options.Port,
            _guard.BoundAddress,
            _options.MaxFileSize,
            _options.ChunkSize));

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

        lock (_gate)
        {
            if (ReferenceEquals(_client, socket))
            {
                _client = null;
                _clientName = null;
                wasActive = true;
            }
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
            _guard.MarkDisconnected(address);
            RaiseStatusChanged();
        }
    }

    private void HandleTextMessage(IWebSocketConnection socket, string message)
    {
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

            case MessageKind.FileHeader:
                BeginIncomingTransfer(socket, command);
                break;

            case MessageKind.FileEnd:
                FinishIncomingTransfer(socket, "对端声明传输结束");
                break;

            case MessageKind.Hello:
                lock (_gate)
                {
                    _clientName = string.IsNullOrWhiteSpace(command.DeviceName)
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

            default:
                _log.Warn($"忽略未知指令：{command.RawType}");
                break;
        }
    }

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

    private void WatchdogTick(object? state)
    {
        IWebSocketConnection? client;
        var shouldPing = false;
        var timedOut = false;

        lock (_gate)
        {
            client = _client;
            if (client is null)
            {
                return;
            }

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

        if (timedOut)
        {
            _log.Warn($"连接超过 {_options.IdleTimeout.TotalSeconds:0} 秒无响应，主动断开");
            TryClose(client, 1001);
            return;
        }

        if (shouldPing)
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
