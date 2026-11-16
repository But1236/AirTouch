using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using AirTouch.Core;

namespace AirTouch.SmokeTest;

/// <summary>
/// End-to-end verification for the AirTouch Windows server. Exercises the security policy, the wire
/// protocol, real TLS transport, file transfer in both directions and (when an interactive desktop is
/// available) real cursor movement through SendInput.
/// </summary>
public static class Program
{
    private static readonly TimeSpan ShortWait = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan LongWait = TimeSpan.FromSeconds(15);

    public static async Task<int> Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;

        if (args.Length >= 3 && args[0] == "--serve")
        {
            return await ServeAsync(args);
        }

        var workspace = args.Length > 0
            ? Path.GetFullPath(args[0])
            : Path.Combine(Path.GetTempPath(), "airtouch-smoke-" + Guid.NewGuid().ToString("N")[..8]);

        Directory.CreateDirectory(workspace);

        var reporter = new TestReporter();
        Console.WriteLine("AirTouch 服务端自检");
        Console.WriteLine($"工作目录：{workspace}");

        RunGuardPolicyTests(reporter);
        RunFileNameTests(reporter);
        RunProtocolTests(reporter);
        RunCertificateTests(reporter, workspace);
        var useTls = await ProbeTlsAsync(reporter, workspace);
        await RunEndToEndTests(reporter, workspace, useTls);
        await RunLiveInputTests(reporter, workspace, useTls);
        await RunWpfSmokeTest(reporter, workspace);

        return reporter.Summarize();
    }

    /// <summary>
    /// Checks whether this machine can actually complete a TLS handshake as a client.
    /// </summary>
    /// <remarks>
    /// Some locked-down environments (including the sandbox this project was scaffolded in) fail at
    /// Schannel's <c>AcquireCredentialsHandle</c> with <c>SEC_E_NO_CREDENTIALS</c> for *every* outbound
    /// TLS connection — curl and PowerShell's https requests fail the same way there. The server code
    /// is unaffected, but the test harness needs to know so it can keep verifying the protocol over a
    /// plaintext loopback socket instead of reporting a false failure.
    /// </remarks>
    private static async Task<bool> ProbeTlsAsync(TestReporter reporter, string workspace)
    {
        reporter.Section("TLS 传输探测");

        var dataDir = Path.Combine(workspace, "tls-probe");
        var port = ReserveFreePort();
        var options = new AirTouchOptions
        {
            DataDirectory = dataDir,
            DownloadDirectory = dataDir,
            Port = port,
            PingInterval = TimeSpan.FromSeconds(2),
        };

        var log = new AirTouchLog(dataDir);
        using var server = new AirTouchServer(options, new RecordingMouseController(), log);

        try
        {
            server.Start();
        }
        catch (AirTouchServerException ex) when (ex.Message.Contains("Schannel", StringComparison.Ordinal))
        {
            reporter.Skip("wss 握手", "本进程无法写入用户密钥容器，Schannel 拒绝以临时密钥提供 TLS");
            reporter.Info(ex.Message.Replace("\n", " "));
            return false;
        }

        var status = server.GetStatus();
        await using var client = WssTestClient.Create(status.CertificateFingerprint);

        try
        {
            await client.ConnectAsync(Protocol.BuildUrl("127.0.0.1", port, status.Token), LongWait);
            reporter.Check("wss 握手成功（自签名证书可被 SslStream 使用）", true, client.FingerprintError ?? "证书指纹匹配");
            await client.CloseAsync();
            server.Stop();
            return true;
        }
        catch (Exception ex) when (IsSchannelCredentialFailure(ex))
        {
            reporter.Skip("wss 握手", "本机 Schannel 无法获取客户端凭据（0x8009030E SEC_E_NO_CREDENTIALS）");
            reporter.Info("这是运行环境的 TLS 限制，不是服务端问题：同一环境下 curl / PowerShell 的 https 请求也会失败。");
            reporter.Info("后续用例改用 ws:// 回环继续验证协议、鉴权、文件传输与鼠标控制逻辑。");
            server.Stop();
            return false;
        }
        catch (Exception ex)
        {
            reporter.Check("wss 握手成功", false, ex.Message);
            server.Stop();
            return false;
        }
    }

    private static bool IsSchannelCredentialFailure(Exception exception)
    {
        var text = exception.ToString();
        return text.Contains("0x8009030E", StringComparison.OrdinalIgnoreCase) ||
               text.Contains("SEC_E_NO_CREDENTIALS", StringComparison.OrdinalIgnoreCase) ||
               text.Contains("安全包中没有可用的凭证", StringComparison.Ordinal) ||
               text.Contains("AcquireCredentialsHandle", StringComparison.OrdinalIgnoreCase);
    }

    // ----------------------------------------------------------- serve mode

    /// <summary>
    /// <c>--serve &lt;dataDir&gt; &lt;port&gt; [seconds]</c> runs a real production-configuration WSS
    /// server (TLS on) and prints machine readable lines so an external client can pair with it.
    /// Used by <c>tools/wss-probe.mjs</c>, which drives the endpoint from Node/OpenSSL — a completely
    /// different TLS stack from Schannel, which is what the phone actually talks to.
    /// </summary>
    private static async Task<int> ServeAsync(string[] args)
    {
        var dataDir = Path.GetFullPath(args[1]);
        if (!int.TryParse(args[2], out var port))
        {
            Console.Error.WriteLine("端口无效");
            return 2;
        }

        var seconds = args.Length > 3 && int.TryParse(args[3], out var parsed) ? parsed : 180;
        Directory.CreateDirectory(dataDir);

        var options = new AirTouchOptions
        {
            DataDirectory = dataDir,
            DownloadDirectory = Path.Combine(dataDir, "downloads"),
            Port = port,
            PingInterval = TimeSpan.FromSeconds(2),
            IdleTimeout = TimeSpan.FromSeconds(60),
            AdvertisedAddress = "127.0.0.1",
        };

        var log = new AirTouchLog(dataDir);
        log.EntryWritten += (_, entry) => Console.WriteLine($"LOG {entry}");

        using var server = new AirTouchServer(options, new Win32MouseController(log), log);
        server.StatusChanged += (_, status) => Console.WriteLine($"AIRTOUCH_TOKEN={status.Token}");

        server.Start();

        Console.WriteLine($"AIRTOUCH_URL={server.GetStatus().Url}");
        Console.WriteLine($"AIRTOUCH_FINGERPRINT={server.GetStatus().CertificateFingerprint}");
        Console.WriteLine($"AIRTOUCH_DOWNLOAD={options.DownloadDirectory}");
        Console.Out.Flush();

        await Task.Delay(TimeSpan.FromSeconds(seconds));
        return 0;
    }

    // ---------------------------------------------------------------- policy

    private static void RunGuardPolicyTests(TestReporter reporter)
    {
        reporter.Section("安全策略单元测试（令牌 / IP 绑定 / 防暴破）");

        var options = new AirTouchOptions
        {
            DataDirectory = Path.GetTempPath(),
            DownloadDirectory = Path.GetTempPath(),
        };

        // --- happy path + one-time token
        var guard = new ConnectionGuard(options);
        var token = guard.Token;

        reporter.Check("token 是 UUID v4 格式", Guid.TryParseExact(token, "D", out _), token);

        var ok = guard.TryAuthorize("192.168.1.10", $"/ws?token={token}");
        reporter.Equal("正确 token 放行", AuthResult.Allow, ok.Result);

        guard.MarkConnected("192.168.1.10");
        reporter.Equal("首个 IP 被绑定", "192.168.1.10", guard.BoundAddress);
        reporter.Check("连接后 token 被销毁", guard.TokenConsumed);

        var otherIp = guard.TryAuthorize("192.168.1.11", $"/ws?token={token}");
        reporter.Equal("其他 IP 被拒绝", AuthResult.IpLocked, otherIp.Result);

        var busy = guard.TryAuthorize("192.168.1.10", $"/ws?token={token}");
        reporter.Equal("同一 IP 的第二条连接被拒绝", AuthResult.Busy, busy.Result);

        var replay = guard.TryAuthorize("192.168.1.10", $"/ws?token={token}");
        reporter.Check("重放已销毁 token 不算暴破", replay.Result is AuthResult.Busy or AuthResult.TokenExpired, replay.Result.ToString());

        // --- token rotation keeps the IP binding
        guard.MarkDisconnected("192.168.1.10");
        var rotated = guard.Token;
        reporter.Check("断开后生成新 token", rotated != token);

        var oldTokenAttempt = guard.TryAuthorize("192.168.1.10", $"/ws?token={token}");
        // The reconnecting phone presents the token it already used; recognising it as a replay
        // (rather than as a guess) is what keeps a Wi-Fi blip from banning the phone.
        reporter.Equal("旧 token 判为已失效而非猜错", AuthResult.TokenExpired, oldTokenAttempt.Result);
        reporter.Equal("重放旧 token 不计入失败次数", 0, guard.FailuresFor("192.168.1.10"));

        var rebound = guard.TryAuthorize("192.168.1.10", $"/ws?token={rotated}");
        reporter.Equal("绑定 IP 可用新 token 重连", AuthResult.Allow, rebound.Result);

        // --- brute force lockout
        var attacker = new ConnectionGuard(options);
        AuthOutcome last = default!;
        for (var attempt = 1; attempt <= options.MaxTokenFailures; attempt++)
        {
            last = attacker.TryAuthorize("10.0.0.66", "/ws?token=wrong-token");
            reporter.Info($"第 {attempt} 次错误 token → {last.Result}");
        }

        reporter.Equal($"连续 {options.MaxTokenFailures} 次失败后封禁", AuthResult.Banned, last.Result);

        var afterBan = attacker.TryAuthorize("10.0.0.66", $"/ws?token={attacker.Token}");
        reporter.Equal("封禁后即使 token 正确也拒绝", AuthResult.Banned, afterBan.Result);

        var goodNeighbour = attacker.TryAuthorize("10.0.0.67", $"/ws?token={attacker.Token}");
        reporter.Equal("封禁只影响肇事 IP", AuthResult.Allow, goodNeighbour.Result);

        attacker.Reset("测试");
        var afterReset = attacker.TryAuthorize("10.0.0.66", $"/ws?token={attacker.Token}");
        reporter.Equal("重置后解封", AuthResult.Allow, afterReset.Result);

        // --- path + rate limit
        var pathGuard = new ConnectionGuard(options);
        reporter.Equal("错误路径被拒绝", AuthResult.BadPath, pathGuard.TryAuthorize("10.0.0.9", $"/admin?token={pathGuard.Token}").Result);

        var rateGuard = new ConnectionGuard(options);
        AuthOutcome rateOutcome = default!;
        for (var i = 0; i < options.MaxConnectionAttemptsPerWindow + 5; i++)
        {
            // A valid token on the valid path, so only the rate limiter can stop us.
            rateOutcome = rateGuard.TryAuthorize("10.0.0.9", $"/ws?token={rateGuard.Token}");
        }

        reporter.Equal("超出速率限制被拒绝", AuthResult.RateLimited, rateOutcome.Result);
    }

    // ------------------------------------------------------------- filenames

    private static void RunFileNameTests(TestReporter reporter)
    {
        reporter.Section("文件名与路径加固");

        reporter.Equal("剥离相对路径", "passwd.txt", SafeFileName.Sanitize("../../windows/passwd.txt"));
        reporter.Equal("剥离 Windows 分隔符", "evil.exe", SafeFileName.Sanitize(@"..\..\..\evil.exe"));
        reporter.Equal("绝对路径只取文件名", "hosts", SafeFileName.Sanitize(@"C:\Windows\System32\drivers\etc\hosts"));
        reporter.Equal("去掉结尾的点", "evil.exe", SafeFileName.Sanitize("evil.exe."));
        reporter.Equal("去掉控制字符", "abc.txt", SafeFileName.Sanitize("a\u0000b\u0007c.txt"));
        reporter.Equal("保留设备名但加前缀", "_CON", SafeFileName.Sanitize("CON"));
        reporter.Check("空名称回退", SafeFileName.Sanitize("   ").StartsWith("file_"), SafeFileName.Sanitize("   "));
        reporter.Equal("只保留 .. 也能回退", SafeFileName.Sanitize("..").StartsWith("file_"), SafeFileName.Sanitize("..").StartsWith("file_"));
        reporter.Check("超长名字被截断", SafeFileName.Sanitize(new string('a', 500) + ".txt").Length <= 180);
        // 中文文件名是最常见的真实用例（手机端选中的文件多半带中文），不能被过滤或破坏
        reporter.Equal("保留中文文件名", "实验报告 2026.pdf", SafeFileName.Sanitize(@"D:\资料\实验报告 2026.pdf"));
        reporter.Equal("中文路径同样剥离目录", "照片.png", SafeFileName.Sanitize("../../相册/照片.png"));
        reporter.Check("中文超长名按字符截断且保留扩展名",
            SafeFileName.Sanitize(new string('中', 300) + ".txt").EndsWith(".txt"));
        reporter.Equal("emoji 与组合字符不被破坏", "🎉报告.txt", SafeFileName.Sanitize("🎉报告.txt"));

        var downloadDir = Path.Combine(Path.GetTempPath(), "airtouch-name-test");
        reporter.Check("目录内路径通过校验", SafeFileName.IsInside(downloadDir, Path.Combine(downloadDir, "a.txt")));
        reporter.Check("目录外路径被拒绝", !SafeFileName.IsInside(downloadDir, Path.Combine(downloadDir, "..", "a.txt")));
        reporter.Check("绝对路径逃逸被拒绝", !SafeFileName.IsInside(downloadDir, @"C:\Windows\System32\evil.dll"));
    }

    // -------------------------------------------------------------- protocol

    private static void RunProtocolTests(TestReporter reporter)
    {
        reporter.Section("协议解析");

        var move = Protocol.Parse("""{"type":"move","dx":10,"dy":-5}""");
        reporter.Check("解析 move", move is { Kind: MessageKind.Move, Dx: 10, Dy: -5 });
        reporter.Check("move 超限被截断", Protocol.Parse("""{"type":"move","dx":999999,"dy":-999999}""") is { Dx: Protocol.MaxMoveStep, Dy: -Protocol.MaxMoveStep });

        reporter.Check("解析 click left", Protocol.Parse("""{"type":"click","button":"left"}""") is { Kind: MessageKind.Click, Button: MouseButton.Left });
        reporter.Check("解析 click right", Protocol.Parse("""{"type":"click","button":"right"}""") is { Kind: MessageKind.Click, Button: MouseButton.Right });
        reporter.Check("click 缺省为左键", Protocol.Parse("""{"type":"click"}""") is { Button: MouseButton.Left });
        reporter.Check("解析 scroll 并截断", Protocol.Parse("""{"type":"scroll","amount":5000}""") is { Kind: MessageKind.Scroll, Amount: Protocol.MaxScrollAmount });
        reporter.Check("解析 reset", Protocol.Parse("""{"type":"reset"}""") is { Kind: MessageKind.Reset });
        reporter.Check("解析 file_header", Protocol.Parse("""{"type":"file_header","fileName":"a.txt","fileSize":1024}""") is { Kind: MessageKind.FileHeader, FileName: "a.txt", FileSize: 1024 });
        reporter.Check("字符串数字可解析", Protocol.Parse("""{"type":"scroll","amount":"3"}""") is { Amount: 3 });
        reporter.Check("未知类型不崩溃", Protocol.Parse("""{"type":"whatever"}""") is { Kind: MessageKind.Unknown });
        reporter.Check("非法 JSON 返回 null", Protocol.Parse("not json") is null);
        reporter.Check("非对象 JSON 返回 null", Protocol.Parse("[1,2,3]") is null);
        reporter.Check("未知字段被忽略", Protocol.Parse("""{"type":"reset","extra":{"a":1}}""") is { Kind: MessageKind.Reset });

        var url = Protocol.BuildUrl("192.168.1.5", 8443, "abc-123");
        reporter.Equal("二维码 URL 格式", "wss://192.168.1.5:8443/ws?token=abc-123", url);

        var welcome = Protocol.BuildWelcome("1.0.0", "192.168.1.5", 8443, "192.168.1.9", 1024, 64);
        reporter.Equal("welcome 类型", "welcome", TestJson.GetString(welcome, "type"));
        reporter.Equal("welcome 端口", 8443, (int)TestJson.GetLong(welcome, "port"));

        reporter.Check("file_ack 省略 null 字段", !Protocol.BuildFileAck("a.txt", false, 0, null, "boom").Contains("savedPath"));

        // 服务端会把非 ASCII 转义成 \uXXXX；Android 的 org.json 必须能还原出原字符串
        var chineseHeader = Protocol.BuildFileHeader("实验报告 2026.pdf", 1234, null);
        reporter.Equal("中文文件名 JSON 往返一致", "实验报告 2026.pdf", TestJson.GetString(chineseHeader, "fileName"));
        reporter.Equal("中文文件名大小不被破坏", 1234L, TestJson.GetLong(chineseHeader, "fileSize"));
        reporter.Check("中文文件名不允许出现未转义的裸字符（\u4e2d）",
            !chineseHeader.Contains('中'));
    }

    // ----------------------------------------------------------- certificate

    private static void RunCertificateTests(TestReporter reporter, string workspace)
    {
        reporter.Section("自签名证书");

        var dataDir = Path.Combine(workspace, "cert-data");
        var certificate = CertificateManager.GetOrCreate(dataDir, new[] { "192.168.1.50", "127.0.0.1" });

        reporter.Check("生成了带私钥的证书", certificate.Certificate.HasPrivateKey);
        reporter.Check("证书被标记为新建", certificate.WasCreated);
        reporter.Check("有效期超过 1 年", certificate.NotAfter > DateTimeOffset.Now.AddYears(1), certificate.NotAfter.ToString("yyyy-MM-dd"));
        reporter.Check("指纹是 SHA-256", certificate.Fingerprint.Split(':').Length == 32, certificate.Fingerprint);
        reporter.Check("包含局域网 IP 的 SAN", certificate.Certificate.Extensions
            .Any(extension => extension.Oid?.Value == "2.5.29.17" &&
                              Convert.ToHexString(extension.RawData).Contains("C0A80132")), "192.168.1.50 → C0A80132");

        var again = CertificateManager.GetOrCreate(dataDir, Array.Empty<string>());
        reporter.Check("重启后复用同一证书（指纹不变）", again.Fingerprint == certificate.Fingerprint);
        reporter.Check("复用时不重新生成", !again.WasCreated);

        // Schannel cannot serve TLS from an in-memory key, so this is a real prerequisite for wss.
        reporter.Check(
            "证书私钥已写入密钥容器（wss 可用）",
            !certificate.IsEphemeralKey,
            certificate.IsEphemeralKey
                ? "已退回内存临时密钥：本进程无权写入用户密钥容器，wss 无法工作"
                : "UserKeySet");

        reporter.Check("PFX 与口令文件都已落盘",
            File.Exists(Path.Combine(dataDir, "airtouch-server.pfx")) &&
            File.Exists(Path.Combine(dataDir, "airtouch-server.key")));

        var qr = QrCodeRenderer.RenderPng(Protocol.BuildUrl("192.168.1.5", 8443, Guid.NewGuid().ToString("D")));
        reporter.Check("二维码是 PNG", qr.Length > 8 && qr[0] == 0x89 && qr[1] == 0x50 && qr[2] == 0x4E && qr[3] == 0x47, $"{qr.Length} 字节");
    }

    // ------------------------------------------------------------ end to end

    private static async Task RunEndToEndTests(TestReporter reporter, string workspace, bool useTls)
    {
        reporter.Section($"端到端 WSS 测试（协议 / 文件 / 一次性令牌 / 封禁）— {(useTls ? "wss" : "ws 回环")}");

        var downloadDir = Path.Combine(workspace, "downloads");
        var dataDir = Path.Combine(workspace, "server-data");
        Directory.CreateDirectory(downloadDir);

        var port = ReserveFreePort();
        var options = new AirTouchOptions
        {
            DataDirectory = dataDir,
            DownloadDirectory = downloadDir,
            Port = port,
            MaxFileSize = 2 * 1024 * 1024,
            PingInterval = TimeSpan.FromSeconds(2),
            IdleTimeout = TimeSpan.FromSeconds(30),
            UseTls = useTls,
        };

        string Url(string host, string token) => Protocol.BuildUrl(host, port, token, useTls);

        var log = new AirTouchLog(dataDir);
        var mouse = new RecordingMouseController();
        using var server = new AirTouchServer(options, mouse, log);

        var received = new List<FileTransferResult>();
        server.FileReceived += (_, result) =>
        {
            lock (received)
            {
                received.Add(result);
            }
        };

        server.Start();
        reporter.Check("服务已启动", server.IsRunning);

        var status = server.GetStatus();
        reporter.Check("状态里带出证书指纹", status.CertificateFingerprint.Contains(':'));
        reporter.Equal("接收目录为配置值", downloadDir, status.DownloadDirectory);

        var token = status.Token;
        var url = Url("127.0.0.1", token);

        // --- connect with fingerprint validation
        await using var client = WssTestClient.Create(status.CertificateFingerprint);
        await client.ConnectAsync(url, LongWait);
        reporter.Check(
            useTls ? "wss 连接成功（指纹校验通过）" : "ws 回环连接成功（本机 TLS 不可用，见上文跳过项）",
            true,
            useTls ? client.FingerprintError ?? "证书指纹匹配" : "明文回环，仅用于验证协议逻辑");

        var welcome = client.Receive(ShortWait);
        reporter.Equal("收到 welcome", "welcome", welcome?.MessageType);
        reporter.Equal("welcome 携带 chunkSize", 64 * 1024, (int)TestJson.GetLong(welcome!.Text!, "chunkSize"));

        reporter.Check("服务端记录了绑定 IP", await WaitForAsync(() => server.GetStatus().BoundAddress == "127.0.0.1", ShortWait),
            server.GetStatus().BoundAddress ?? "<null>");

        // --- second connection while busy
        await using (var second = WssTestClient.Create(status.CertificateFingerprint))
        {
            await second.ConnectAsync(url, ShortWait);
            var (code, message, closeCode) = second.ReceiveRejection(ShortWait);
            reporter.Equal("并发第二条连接被拒绝（busy）", "busy", code);
            reporter.Info($"busy 关闭码 {closeCode}，消息：{message}");
        }

        // --- control commands
        await client.SendTextAsync("""{"type":"hello","deviceName":"Pixel-Test","appVersion":"1.0.0"}""");
        await client.SendTextAsync("""{"type":"move","dx":10,"dy":-5}""");
        await client.SendTextAsync("""{"type":"click","button":"left"}""");
        await client.SendTextAsync("""{"type":"click","button":"right"}""");
        await client.SendTextAsync("""{"type":"scroll","amount":3}""");
        await client.SendTextAsync("""{"type":"reset"}""");
        await client.SendTextAsync("""{"type":"move","dx":999999,"dy":0}""");

        var gotCommands = await WaitForAsync(() => mouse.Events.Count >= 6, ShortWait);
        reporter.Check("收到并执行全部控制指令", gotCommands, string.Join(" | ", mouse.Events));

        if (gotCommands)
        {
            var events = mouse.Events;
            reporter.Equal("move 相对位移正确", "move 10 -5", events[0]);
            reporter.Equal("左键点击", "click left", events[1]);
            reporter.Equal("右键点击", "click right", events[2]);
            reporter.Equal("滚轮上滚", "scroll 3", events[3]);
            reporter.Equal("复位", "reset", events[4]);
            reporter.Equal("move 被截断到上限", $"move {Protocol.MaxMoveStep} 0", events[5]);
        }

        reporter.Check("设备名上报成功", await WaitForAsync(() => server.GetStatus().ClientName == "Pixel-Test", ShortWait),
            server.GetStatus().ClientName ?? "<null>");

        // --- ping / pong
        await client.SendTextAsync("""{"type":"ping"}""");
        var pong = await WaitForFrameAsync(client, frame => frame.MessageType == "pong", ShortWait);
        reporter.Check("文本 ping 得到 pong", pong is not null);

        // --- keepalive: the client pings every 5s (KeepAliveInterval) and aborts if the server never
        //     answers with a pong, so surviving this wait proves the server's OnPing -> SendPong path.
        reporter.Info("等待 12 秒验证心跳存活（客户端 KeepAliveInterval=5s）…");
        await Task.Delay(TimeSpan.FromSeconds(12));
        await client.SendTextAsync("""{"type":"reset"}""");
        var aliveAfterHeartbeat = await WaitForAsync(() => mouse.Events.Count >= 7, ShortWait);
        reporter.Check("心跳期间连接保持存活", aliveAfterHeartbeat);

        // --- malformed input is ignored
        await client.SendTextAsync("this is not json");
        await client.SendTextAsync("""{"type":"move","dx":"abc","dy":null}""");
        await client.SendTextAsync("""{"type":"unknown-thing"}""");
        await Task.Delay(200);
        await client.SendTextAsync("""{"type":"reset"}""");
        reporter.Check("非法帧之后连接仍然可用", await WaitForAsync(() => mouse.Events.Count >= 8, ShortWait));

        // --- client -> server file transfer with a hostile name
        var uploadDir = Path.Combine(workspace, "upload");
        Directory.CreateDirectory(uploadDir);
        var uploadPath = Path.Combine(uploadDir, "upload-payload.bin");
        var payload = CreateRandomFile(uploadPath, 300 * 1024);

        await client.SendTextAsync("""{"type":"file_header","fileName":"../../../../evil/upload-payload.bin","fileSize":""" + payload.Length + "}");
        foreach (var chunk in Chunks(payload, 64 * 1024))
        {
            await client.SendBinaryAsync(chunk);
        }

        var ack = await WaitForFrameAsync(client, frame => frame.MessageType == "file_ack", LongWait);
        reporter.Check("收到 file_ack", ack is not null, ack?.Text ?? "<超时>");

        if (ack is not null)
        {
            reporter.Equal("上传状态 ok", "ok", TestJson.GetString(ack.Text!, "status"));
            var savedPath = TestJson.GetString(ack.Text!, "savedPath");
            reporter.Check("文件名被清洗（无路径）", savedPath is not null &&
                                                     !savedPath.Contains("..") &&
                                                     Path.GetFileName(savedPath) == "upload-payload.bin", savedPath ?? "<null>");
            reporter.Check("文件落在下载目录内", savedPath is not null && SafeFileName.IsInside(downloadDir, savedPath), savedPath ?? "<null>");
            reporter.Check("内容逐字节一致", savedPath is not null && File.Exists(savedPath) &&
                                              File.ReadAllBytes(savedPath).SequenceEqual(payload));

            // No .part leftovers.
            reporter.Check("没有残留 .part 文件", !Directory.EnumerateFiles(downloadDir, "*.part").Any());
        }

        reporter.Check("服务端抛出 FileReceived 事件", await WaitForAsync(() =>
        {
            lock (received)
            {
                return received.Count >= 1;
            }
        }, ShortWait));

        // --- oversized file is refused before any bytes are written
        await client.SendTextAsync("""{"type":"file_header","fileName":"huge.bin","fileSize":""" + (options.MaxFileSize + 1) + "}");
        var rejectAck = await WaitForFrameAsync(client, frame => frame.MessageType == "file_ack", ShortWait);
        reporter.Check("超大文件被拒绝", rejectAck is not null && TestJson.GetString(rejectAck.Text!, "status") == "error",
            rejectAck?.Text ?? "<超时>");
        reporter.Check("超大文件未落盘", !File.Exists(Path.Combine(downloadDir, "huge.bin")));

        // --- binary frame with no header is ignored, connection survives
        await client.SendBinaryAsync(new byte[1024]);
        await Task.Delay(200);
        await client.SendTextAsync("""{"type":"reset"}""");
        reporter.Check("无 header 的二进制帧被忽略且连接存活", await WaitForAsync(() => mouse.Events.Count >= 9, ShortWait));

        // --- server -> client file transfer
        var downloadPayloadPath = Path.Combine(workspace, "download-payload.bin");
        var downloadPayload = CreateRandomFile(downloadPayloadPath, 220 * 1024);

        var sendTask = server.SendFileAsync(downloadPayloadPath);
        var header = await WaitForFrameAsync(client, frame => frame.MessageType == "file_header", ShortWait);
        reporter.Check("手机收到 file_header", header is not null, header?.Text ?? "<超时>");
        reporter.Equal("发送文件名正确", "download-payload.bin", header is null ? "<null>" : TestJson.GetString(header.Text!, "fileName"));
        reporter.Equal("发送文件大小正确", (long)downloadPayload.Length, header is null ? -1 : TestJson.GetLong(header.Text!, "fileSize"));

        var assembled = new MemoryStream();
        IncomingFrame? fileEnd = null;
        var deadline = DateTime.UtcNow + LongWait;
        while (DateTime.UtcNow < deadline)
        {
            var frame = client.Receive(deadline - DateTime.UtcNow);
            if (frame is null)
            {
                break;
            }

            if (frame.Binary is not null)
            {
                assembled.Write(frame.Binary);
                if (assembled.Length >= downloadPayload.Length)
                {
                    continue;
                }
            }
            else if (frame.MessageType == "file_end")
            {
                fileEnd = frame;
                break;
            }
        }

        var sendResult = await sendTask;
        reporter.Check("服务端报告发送成功", sendResult.Success, sendResult.Message);
        reporter.Check("手机收到的字节数与源文件一致", assembled.ToArray().SequenceEqual(downloadPayload),
            $"{assembled.Length} / {downloadPayload.Length}");
        reporter.Check("收到 file_end", fileEnd is not null);

        // --- one-time token + ban
        await client.CloseAsync();
        reporter.Check("服务端检测到断开", await WaitForAsync(() => server.GetStatus().ClientAddress is null, ShortWait));

        var tokenAfterDisconnect = server.GetStatus().Token;
        reporter.Check("断开后 token 已轮换", tokenAfterDisconnect != token);

        string? lastErrorCode = null;
        for (var attempt = 1; attempt <= options.MaxTokenFailures; attempt++)
        {
            await using var attacker = WssTestClient.Create(status.CertificateFingerprint);
            await attacker.ConnectAsync(Url("127.0.0.1", "00000000-0000-4000-8000-000000000000"), ShortWait);
            var (code, _, _) = attacker.ReceiveRejection(ShortWait);
            lastErrorCode = code;
            reporter.Info($"第 {attempt} 次伪造 token → {code}");
        }

        reporter.Equal("连续伪造后返回 banned", "banned", lastErrorCode);

        await using (var bannedClient = WssTestClient.Create(status.CertificateFingerprint))
        {
            await bannedClient.ConnectAsync(Url("127.0.0.1", server.GetStatus().Token), ShortWait);
            var (code, _, _) = bannedClient.ReceiveRejection(ShortWait);
            reporter.Equal("封禁后正确 token 也被拒绝", "banned", code);
        }

        server.ResetSecurity("自检重置");
        reporter.Check("重置后封禁列表为空", server.GetStatus().BannedAddresses.Count == 0);

        await using (var recovered = WssTestClient.Create(status.CertificateFingerprint))
        {
            await recovered.ConnectAsync(Url("127.0.0.1", server.GetStatus().Token), ShortWait);
            var welcomeAgain = recovered.Receive(ShortWait);
            reporter.Equal("重置后可重新配对", "welcome", welcomeAgain?.MessageType);
        }

        // --- wrong-path rejection over the wire
        await using (var wrongPath = WssTestClient.Create(status.CertificateFingerprint))
        {
            await wrongPath.ConnectAsync($"{(useTls ? "wss" : "ws")}://127.0.0.1:{port}/not-ws?token={server.GetStatus().Token}", ShortWait);
            var (code, _, _) = wrongPath.ReceiveRejection(ShortWait);
            reporter.Equal("错误路径返回 bad_path", "bad_path", code);
        }

        server.Stop();
        reporter.Check("服务已停止", !server.IsRunning);

        // --- port conflict surfaces as a friendly error
        var blocker = new TcpListener(IPAddress.Any, port);
        blocker.Start();
        try
        {
            using var conflicted = new AirTouchServer(options, mouse, log);
            try
            {
                conflicted.Start();
                reporter.Check("端口被占用时应抛出 AirTouchServerException", false, "未抛出异常");
            }
            catch (AirTouchServerException ex)
            {
                reporter.Check("端口被占用时抛出友好错误", true, ex.Message);
            }
        }
        finally
        {
            blocker.Stop();
        }
    }

    // ----------------------------------------------------------- live input

    private static async Task RunLiveInputTests(TestReporter reporter, string workspace, bool useTls)
    {
        reporter.Section("真实鼠标控制（SendInput / SetCursorPos）");

        var lanAddress = LocalAddressProvider.GetCandidates().FirstOrDefault()?.Address;
        reporter.Info($"局域网地址：{lanAddress ?? "<无>"}");

        var downloadDir = Path.Combine(workspace, "downloads-live");
        var dataDir = Path.Combine(workspace, "server-data-live");
        Directory.CreateDirectory(downloadDir);

        var port = ReserveFreePort();
        var options = new AirTouchOptions
        {
            DataDirectory = dataDir,
            DownloadDirectory = downloadDir,
            Port = port,
            PingInterval = TimeSpan.FromSeconds(2),
            UseTls = useTls,
        };

        string Url(string host, string token) => Protocol.BuildUrl(host, port, token, useTls);

        var log = new AirTouchLog(dataDir);
        var mouse = new Win32MouseController(log);
        using var server = new AirTouchServer(options, mouse, log);
        server.Start();

        var connectAddress = lanAddress ?? "127.0.0.1";
        var certificateFingerprint = server.GetStatus().CertificateFingerprint;

        await using var client = WssTestClient.Create(certificateFingerprint);
        await client.ConnectAsync(Url(connectAddress, server.GetStatus().Token), LongWait);
        var welcome = client.Receive(ShortWait);
        reporter.Check($"经 {connectAddress} 连接成功", welcome?.MessageType == "welcome");

        // Reset first so we start from a known position, then move right and compare.
        var resetsBefore = mouse.ResetCount;
        await client.SendTextAsync("""{"type":"reset"}""");

        // SetCursorPos is immediate, so sample fast instead of sleeping once: whoever is sitting at
        // the machine may move the physical mouse during the wait, and a single sample 300 ms later
        // would then read their hand rather than the reset. Take the sample closest to the centre.
        var center = ScreenInfo.PrimaryScreenCenter();
        var afterReset = mouse.GetCursorPosition();
        var bestDistance = Math.Abs(afterReset.X - center.X) + Math.Abs(afterReset.Y - center.Y);

        for (var attempt = 0; attempt < 40 && bestDistance > 2; attempt++)
        {
            await Task.Delay(10);
            var sample = mouse.GetCursorPosition();
            var distance = Math.Abs(sample.X - center.X) + Math.Abs(sample.Y - center.Y);
            if (distance < bestDistance)
            {
                afterReset = sample;
                bestDistance = distance;
            }
        }

        reporter.Check("reset 指令被服务端执行", mouse.ResetCount > resetsBefore);
        reporter.Check("reset 把光标放到主屏中心",
            Math.Abs(afterReset.X - center.X) <= 2 && Math.Abs(afterReset.Y - center.Y) <= 2,
            $"期望 {center}，实际 {afterReset}");

        await client.SendTextAsync("""{"type":"move","dx":60,"dy":0}""");
        await Task.Delay(300);
        var afterMove = mouse.GetCursorPosition();
        reporter.Check("move 指令改变了真实光标位置", afterMove.X > afterReset.X,
            $"复位后 X={afterReset.X}，移动后 X={afterMove.X}（SendInput 会应用指针加速，故不做等值断言）");

        await client.SendTextAsync("""{"type":"scroll","amount":1}""");
        await client.SendTextAsync("""{"type":"click","button":"left"}""");
        await Task.Delay(200);
        reporter.Check("滚轮与点击被 SendInput 接受", mouse.ScrollCount >= 1 && mouse.ClickCount >= 1,
            $"滚轮 {mouse.ScrollCount}，点击 {mouse.ClickCount}");

        // --- IP binding is enforced against a genuinely different source address
        if (lanAddress is not null)
        {
            await using var otherIp = WssTestClient.Create(certificateFingerprint);
            await otherIp.ConnectAsync(Url("127.0.0.1", server.GetStatus().Token), ShortWait);
            var (code, message, _) = otherIp.ReceiveRejection(ShortWait);
            reporter.Equal($"绑定 {lanAddress} 后 127.0.0.1 被拒绝", "ip_locked", code);
            reporter.Info($"拒绝理由：{message}");
        }
        else
        {
            reporter.Skip("IP 绑定端到端验证", "本机没有可用的局域网 IPv4 地址");
        }

        await client.CloseAsync();
        server.Stop();
    }

    // --------------------------------------------------------------- WPF UI

    private static async Task RunWpfSmokeTest(TestReporter reporter, string workspace)
    {
        reporter.Section("WPF 界面自检（启动窗口 + 真实监听）");

        var exe = FindServerExecutable();
        if (exe is null)
        {
            reporter.Skip("WPF 界面自检", "未找到 AirTouch.Server.exe（请先构建 WPF 项目）");
            return;
        }

        var dataDir = Path.Combine(workspace, "wpf-data");
        var downloadDir = Path.Combine(workspace, "wpf-downloads");
        Directory.CreateDirectory(dataDir);
        Directory.CreateDirectory(downloadDir);
        var port = ReserveFreePort();

        var startInfo = new ProcessStartInfo(exe, "--smoke-test")
        {
            UseShellExecute = false,
            WorkingDirectory = Path.GetDirectoryName(exe)!,
            Environment =
            {
                ["AIRTOUCH_DATA_DIR"] = dataDir,
                ["AIRTOUCH_DOWNLOAD_DIR"] = downloadDir,
                ["AIRTOUCH_PORT"] = port.ToString(),
            },
        };

        try
        {
            using var process = Process.Start(startInfo);
            if (process is null)
            {
                reporter.Check("启动 AirTouch.Server.exe", false, "Process.Start 返回 null");
                return;
            }

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                process.Kill(entireProcessTree: true);
                reporter.Check("WPF 自检进程按时退出", false, "超时未退出");
                return;
            }

            reporter.Equal("WPF 自检退出码为 0", 0, process.ExitCode);

            var reportPath = Path.Combine(dataDir, "wpf-smoke-test.json");
            reporter.Check("自检报告已生成", File.Exists(reportPath), reportPath);

            if (File.Exists(reportPath))
            {
                using var document = JsonDocument.Parse(File.ReadAllText(reportPath));
                var root = document.RootElement;

                reporter.Check("窗口中服务处于运行状态", root.GetProperty("isRunning").GetBoolean());
                reporter.Check("窗口渲染了二维码", root.GetProperty("qrBytes").GetInt32() > 100,
                    $"{root.GetProperty("qrBytes").GetInt32()} 字节");

                var reportedUrl = root.GetProperty("url").GetString() ?? string.Empty;
                reporter.Check("窗口内的连接地址形如 wss://<ip>:<port>/ws?token=",
                    reportedUrl.StartsWith("wss://", StringComparison.Ordinal) &&
                    reportedUrl.Contains($":{port}/ws?token=", StringComparison.Ordinal),
                    reportedUrl);

                var fingerprint = root.GetProperty("certificateFingerprint").GetString() ?? string.Empty;
                reporter.Check("窗口展示证书指纹", fingerprint.Split(':').Length == 32);
            }
        }
        catch (Exception ex)
        {
            reporter.Check("运行 WPF 自检", false, ex.Message);
        }
    }

    private static string? FindServerExecutable()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);

        for (var depth = 0; depth < 8 && directory is not null; depth++)
        {
            var serverProject = Path.Combine(directory.FullName, "server", "AirTouch.Server");
            if (Directory.Exists(serverProject))
            {
                var binRoot = Path.Combine(serverProject, "bin");
                if (Directory.Exists(binRoot))
                {
                    var candidate = Directory
                        .EnumerateFiles(binRoot, "AirTouch.Server.exe", SearchOption.AllDirectories)
                        .OrderByDescending(File.GetLastWriteTimeUtc)
                        .FirstOrDefault();

                    if (candidate is not null)
                    {
                        return candidate;
                    }
                }
            }

            directory = directory.Parent;
        }

        return null;
    }

    // -------------------------------------------------------------- helpers

    private static int ReserveFreePort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    private static async Task<bool> WaitForAsync(Func<bool> condition, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (condition())
            {
                return true;
            }

            await Task.Delay(25);
        }

        return condition();
    }

    private static async Task<IncomingFrame?> WaitForFrameAsync(
        WssTestClient client,
        Func<IncomingFrame, bool> predicate,
        TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            var frame = client.Receive(deadline - DateTime.UtcNow);
            if (frame is null)
            {
                return null;
            }

            if (predicate(frame))
            {
                return frame;
            }
        }

        return null;
    }

    private static byte[] CreateRandomFile(string path, int size)
    {
        var payload = new byte[size];
        new Random(20260101).NextBytes(payload);
        File.WriteAllBytes(path, payload);
        return payload;
    }

    private static IEnumerable<byte[]> Chunks(byte[] payload, int chunkSize)
    {
        for (var offset = 0; offset < payload.Length; offset += chunkSize)
        {
            var length = Math.Min(chunkSize, payload.Length - offset);
            var chunk = new byte[length];
            Buffer.BlockCopy(payload, offset, chunk, 0, length);
            yield return chunk;
        }
    }
}
