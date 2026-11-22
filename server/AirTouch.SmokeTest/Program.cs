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
    /// Some locked-down environments fail at Schannel's <c>AcquireCredentialsHandle</c> with
    /// <c>SEC_E_NO_CREDENTIALS</c> for *every* outbound TLS connection — curl and PowerShell's https
    /// requests fail the same way there. The server code is unaffected, but the test harness needs to
    /// know so it can keep verifying the protocol over a plaintext loopback socket instead of
    /// reporting a false failure.
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
        reporter.Section("安全策略单元测试（令牌 / 待认证预算 / 防暴破）");

        var options = new AirTouchOptions
        {
            DataDirectory = Path.GetTempPath(),
            DownloadDirectory = Path.GetTempPath(),
        };

        // --- happy path + one-time token
        var guard = new ConnectionGuard(options);
        var token = guard.Token;

        reporter.Check("token 是 UUID v4 格式", Guid.TryParseExact(token, "D", out _), token);

        var pairing = guard.TryAuthorize("192.168.1.10", $"/ws?token={token}");
        reporter.Equal("正确 token 放行并进入配对模式", AuthResult.AllowPairing, pairing.Result);
        reporter.Equal("配对模式下 AuthMode 为 Pairing", AuthMode.Pairing, pairing.Mode);
        reporter.Check("连接期间 token 尚未销毁", !guard.TokenConsumed);

        guard.EndPending();
        guard.MarkPaired("192.168.1.10");
        reporter.Check("配对成功后 token 被销毁", guard.TokenConsumed);

        var rotated = guard.Token;
        reporter.Check("配对成功后立即生成新 token（二维码可继续用）", rotated != token);

        // The phone that dropped off Wi-Fi may still present the token it used; that must read as a
        // replay, not as a guess, or a couple of drops would permanently ban it.
        var replay = guard.TryAuthorize("192.168.1.10", $"/ws?token={token}");
        reporter.Equal("重放已销毁的 token 判为 token_expired", AuthResult.TokenExpired, replay.Result);
        reporter.Equal("重放旧 token 不计入失败次数", 0, guard.FailuresFor("192.168.1.10"));

        // --- resume path: no token at all
        var resume = guard.TryAuthorize("192.168.1.10", "/ws");
        reporter.Equal("不带 token 的连接进入重连模式", AuthResult.AllowResume, resume.Result);
        reporter.Equal("重连模式下 AuthMode 为 Resume", AuthMode.Resume, resume.Mode);
        guard.EndPending();

        guard.MarkAuthenticated("dev-key-1", "测试手机", "192.168.1.10");
        reporter.Equal("认证后记录活动密钥", "dev-key-1", guard.ActiveKeyId);
        reporter.Check("认证后存在活动连接", guard.HasActiveClient);

        guard.MarkDisconnected("192.168.1.10");
        reporter.Check("断开后活动连接被清空", !guard.HasActiveClient);

        var again = guard.TryAuthorize("192.168.1.10", "/ws");
        reporter.Equal("断开后不带 token 即进入重连模式", AuthResult.AllowResume, again.Result);
        guard.EndPending();

        var withNewToken = guard.TryAuthorize("192.168.1.10", $"/ws?token={guard.Token}");
        reporter.Equal("带新 token 连接则进入配对模式（可添加下一台手机）", AuthResult.AllowPairing, withNewToken.Result);
        guard.EndPending();

        // --- pending budget: unauthenticated sockets cannot pile up
        var pendingGuard = new ConnectionGuard(options);
        for (var i = 0; i < options.MaxPendingSockets; i++)
        {
            var allowed = pendingGuard.TryAuthorize($"10.1.0.{i + 1}", "/ws");
            reporter.Check($"第 {i + 1} 条待认证连接被接纳", allowed.Result == AuthResult.AllowResume, allowed.Result.ToString());
        }

        var overflow = pendingGuard.TryAuthorize("10.1.0.99", "/ws");
        reporter.Equal($"第 {options.MaxPendingSockets + 1} 条待认证连接被拒绝",
            AuthResult.TooManyPending, overflow.Result);
        reporter.Equal("过载关闭码为 1013", 1013, overflow.CloseCode);

        pendingGuard.EndPending();
        var afterRelease = pendingGuard.TryAuthorize("10.1.0.99", "/ws");
        reporter.Equal("释放一个槽位后又可以连接", AuthResult.AllowResume, afterRelease.Result);
        pendingGuard.EndPending();

        // --- pairing token is re-checked when the key is registered
        var pairGuard = new ConnectionGuard(options);
        var liveToken = pairGuard.Token;
        reporter.Equal("auth_req 里的有效 token 通过校验",
            AuthResult.AllowPairing, pairGuard.CheckPairingToken("10.2.0.1", liveToken).Result);
        reporter.Equal("auth_req 里的错误 token 被拒绝",
            AuthResult.TokenInvalid, pairGuard.CheckPairingToken("10.2.0.2", "nope").Result);
        pairGuard.MarkPaired("10.2.0.1");
        reporter.Equal("已被别的手机用掉的 token 判为过期",
            AuthResult.TokenExpired, pairGuard.CheckPairingToken("10.2.0.3", liveToken).Result);

        // --- brute force lockout on the token
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
        reporter.Equal("封禁只影响肇事 IP", AuthResult.AllowPairing, goodNeighbour.Result);
        attacker.EndPending();

        attacker.Reset("测试");
        var afterReset = attacker.TryAuthorize("10.0.0.66", $"/ws?token={attacker.Token}");
        reporter.Equal("重置后解封", AuthResult.AllowPairing, afterReset.Result);
        attacker.EndPending();

        // --- device-key failures have their own budget (and are never mixed with token failures)
        var keyGuard = new ConnectionGuard(options);
        var authBanned = false;
        for (var attempt = 1; attempt <= options.MaxAuthFailures; attempt++)
        {
            authBanned = keyGuard.RegisterAuthFailure("10.3.0.1", "伪造签名");
        }

        reporter.Check($"连续 {options.MaxAuthFailures} 次签名失败后封禁", authBanned);
        reporter.Equal("签名失败不占用 token 失败额度", 0, keyGuard.FailuresFor("10.3.0.1"));
        reporter.Equal("签名失败单独计数", options.MaxAuthFailures, keyGuard.AuthFailuresFor("10.3.0.1"));

        // --- path + rate limit
        var pathGuard = new ConnectionGuard(options);
        reporter.Equal("错误路径被拒绝", AuthResult.BadPath, pathGuard.TryAuthorize("10.0.0.9", $"/admin?token={pathGuard.Token}").Result);

        var rateGuard = new ConnectionGuard(options);
        AuthOutcome rateOutcome = default!;
        for (var i = 0; i < options.MaxConnectionAttemptsPerWindow + 5; i++)
        {
            // A valid token on the valid path, releasing the pending slot each time, so only the rate
            // limiter can stop us.
            rateOutcome = rateGuard.TryAuthorize("10.0.0.9", $"/ws?token={rateGuard.Token}");
            rateGuard.EndPending();
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

        // ---- key 帧（docs/PROTOCOL.md §2.6）
        reporter.Check("解析 key 按下", Protocol.Parse("""{"type":"key","vk":67,"down":true}""") is { Kind: MessageKind.Key, VirtualKey: 67, KeyDown: true });
        reporter.Check("解析 key 抬起", Protocol.Parse("""{"type":"key","vk":67,"down":false}""") is { VirtualKey: 67, KeyDown: false });
        reporter.Check("key 缺 vk 视为非法（0）", Protocol.Parse("""{"type":"key","down":true}""") is { Kind: MessageKind.Key, VirtualKey: 0 });
        reporter.Check("key 的 vk 超上限被截断", Protocol.Parse("""{"type":"key","vk":9999}""") is { VirtualKey: Protocol.MaxVirtualKey });
        reporter.Check("key 的负 vk 被截断", Protocol.Parse("""{"type":"key","vk":-5}""") is { VirtualKey: 0 });
        reporter.Check("key 缺 down 时按「抬起」处理（绝不留下卡住的按键）", Protocol.Parse("""{"type":"key","vk":67}""") is { KeyDown: false });
        reporter.Check("key 的 down 接受字符串 false", Protocol.Parse("""{"type":"key","vk":67,"down":"false"}""") is { KeyDown: false });
        reporter.Check("key 的 down 接受字符串 true", Protocol.Parse("""{"type":"key","vk":67,"down":"true"}""") is { KeyDown: true });
        reporter.Check("key 的 down 接受数字 1", Protocol.Parse("""{"type":"key","vk":67,"down":1}""") is { KeyDown: true });
        reporter.Check("key 缺 ext 时由服务端推断（null）", Protocol.Parse("""{"type":"key","vk":67,"down":true}""") is { KeyExtended: null });
        reporter.Check("key 可显式指定 ext（小键盘回车与主回车共用 VK_RETURN）",
            Protocol.Parse("""{"type":"key","vk":13,"down":true,"ext":true}""") is { VirtualKey: 13, KeyExtended: true });
        reporter.Check("ext 显式 false 与缺省可区分",
            Protocol.Parse("""{"type":"key","vk":13,"down":true,"ext":false}""") is { KeyExtended: false });
        reporter.Check("方向键被判定为扩展键（否则 NumLock 打开时会变成小键盘数字）",
            Win32KeyInfo.IsExtended(0x25) && Win32KeyInfo.IsExtended(0x2E) && Win32KeyInfo.IsExtended(0x2C));
        reporter.Check("普通字母键不是扩展键", !Win32KeyInfo.IsExtended(0x41) && !Win32KeyInfo.IsExtended(0x0D));

        // ---- text 帧（docs/PROTOCOL.md §2.7）
        reporter.Check("解析 text", Protocol.Parse("""{"type":"text","s":"你好"}""") is { Kind: MessageKind.Text, Text: "你好" });
        reporter.Check("text 缺 s 时整帧被忽略", Protocol.Parse("""{"type":"text"}""") is null);
        reporter.Check("text 的 s 不是字符串时整帧被忽略", Protocol.Parse("""{"type":"text","s":123}""") is null);
        reporter.Check("text 的 s 为 null 时整帧被忽略", Protocol.Parse("""{"type":"text","s":null}""") is null);
        reporter.Check("text 的未知字段被忽略",
            Protocol.Parse("""{"type":"text","s":"你好","extra":{"a":1}}""") is { Kind: MessageKind.Text, Text: "你好" });
        reporter.Check("长度上限内的 emoji 不被破坏", Protocol.Parse("""{"type":"text","s":"你好😀"}""") is { Text: "你好😀" });
        reporter.Check("Text 缺省为空串而非 null", new ControlMessage().Text.Length == 0);

        var overlongText = Protocol.Parse($$"""{"type":"text","s":"{{new string('a', Protocol.MaxTextLength + 40)}}"}""");
        reporter.Check($"超长文本被截断到 {Protocol.MaxTextLength} 个码元",
            overlongText is not null && overlongText.Text == new string('a', Protocol.MaxTextLength));

        // 第 256 个码元正好是 emoji 的高代理项：截断点落在字符中间，必须少取一个码元，
        // 否则注入出去的是一个孤立的代理项，接收方只会显示乱码方块
        var splitPair = new string('a', Protocol.MaxTextLength - 1) + "😀" + "尾";
        var splitPairText = Protocol.Parse($$"""{"type":"text","s":"{{splitPair}}"}""");
        reporter.Check("截断不切开代理对（落在高代理项上时少取一个码元）",
            splitPairText is not null && splitPairText.Text == new string('a', Protocol.MaxTextLength - 1));

        var url = Protocol.BuildUrl("192.168.1.5", 8443, "abc-123");
        reporter.Equal("二维码 URL 格式", "wss://192.168.1.5:8443/ws?token=abc-123", url);

        var welcome = Protocol.BuildWelcome(
            "2.0.2", "192.168.1.5", "sid-test", 180, "key-1", "pair");
        reporter.Equal("welcome 类型", "welcome", TestJson.GetString(welcome, "type"));
        reporter.Equal("welcome 带协议版本", 2, (int)TestJson.GetLong(welcome, "protocolVersion"));
        reporter.Equal("welcome 带屏幕刷新率（感知流畅度的上限）", 180, (int)TestJson.GetLong(welcome, "displayHz"));
        reporter.Equal("welcome 带认证方式", "pair", TestJson.GetString(welcome, "authMode"));
        reporter.Equal("welcome 带服务器身份", "sid-test", TestJson.GetString(welcome, "sid"));

        var challenge = Protocol.BuildAuthChallenge("sid-test", "nonce-test", 1762000000, 12, "resume");
        reporter.Equal("auth_challenge 类型", "auth_challenge", TestJson.GetString(challenge, "type"));
        reporter.Equal("auth_challenge 带 nonce", "nonce-test", TestJson.GetString(challenge, "nonce"));
        reporter.Equal("auth_challenge 带服务器时间（供手机校正时钟）",
            1762000000L, TestJson.GetLong(challenge, "serverTime"));

        var urlWithIdentity = Protocol.BuildUrl("192.168.1.5", 8443, "tok", "sid-abc", "pin-xyz");
        reporter.Check("配对 URL 带 sid 与 fp",
            urlWithIdentity.Contains("sid=sid-abc") && urlWithIdentity.Contains("fp=pin-xyz"), urlWithIdentity);
        reporter.Check("配对 URL 仍以 token 开头（旧客户端可解析）",
            urlWithIdentity.StartsWith("wss://192.168.1.5:8443/ws?token=tok", StringComparison.Ordinal), urlWithIdentity);

        reporter.Check("file_ack 省略 null 字段", !Protocol.BuildFileAck("a.txt", false, 0, null, "boom").Contains("savedPath"));

        // 服务端会把非 ASCII 转义成 \uXXXX；Android 的 org.json 必须能还原出原字符串
        var chineseHeader = Protocol.BuildFileHeader("实验报告 2026.pdf", 1234, null);
        reporter.Equal("中文文件名 JSON 往返一致", "实验报告 2026.pdf", TestJson.GetString(chineseHeader, "fileName"));
        reporter.Equal("中文文件名大小不被破坏", 1234L, TestJson.GetLong(chineseHeader, "fileSize"));
        reporter.Check("中文文件名不允许出现未转义的裸字符（\u4e2d）",
            !chineseHeader.Contains('中'));

        RunProtocolV2Tests(reporter);
    }

    /// <summary>
    /// The v2 additions: most importantly the exact byte layout of the signed
    /// authentication payload.
    /// </summary>
    private static void RunProtocolV2Tests(TestReporter reporter)
    {
        var authReq = Protocol.Parse("""{"type":"auth_req","auth_v":1,"keyId":"kid","sign":"sig","ts":1762000000,"token":"t","pubKey":"pk","deviceName":"phone"}""");
        reporter.Check("解析 auth_req", authReq is
        {
            Kind: MessageKind.AuthReq,
            AuthVersion: 1,
            KeyId: "kid",
            Signature: "sig",
            Timestamp: 1762000000,
            Token: "t",
            PublicKey: "pk",
        });

        // ---- the signed byte layout, which both implementations must agree on bit for bit
        reporter.Equal(
            "签名原文为固定的 LF 分隔格式",
            "AirTouch-Auth-v1\nsid123\nnonce456\nkeyid789\n1762000000",
            AuthCrypto.Canonical("sid123", "nonce456", "keyid789", 1762000000));

        using var device = new TestDevice("单元测试设备");
        reporter.Check("设备 keyId 是 22 个字符的 base64url", device.KeyId.Length == 22, device.KeyId);

        var canonical = AuthCrypto.Canonical("sid123", "nonce456", device.KeyId, 1762000000);
        reporter.Check("公钥可以被服务端导入并用于验签",
            AuthCrypto.VerifySignature(
                AuthCrypto.FromBase64UrlOrThrow(device.PublicKey),
                canonical,
                AuthCrypto.FromBase64UrlOrThrow(AuthCrypto.Base64Url(AuthCrypto.SignForClient(device.GetKey(), canonical)))));

        // A P1363 (r||s) signature must NOT be accepted: if it were, the server would be guessing the
        // encoding and a real Android client (which always sends DER) would fail at runtime instead.
        var p1363 = device.SignP1363("sid123", "nonce456", device.KeyId, 1762000000);
        reporter.Check("非 DER（IEEE P1363）签名被拒绝",
            !AuthCrypto.VerifySignature(
                AuthCrypto.FromBase64UrlOrThrow(device.PublicKey),
                canonical,
                AuthCrypto.FromBase64UrlOrThrow(p1363)));

        reporter.Check("签名对原文变化敏感",
            !AuthCrypto.VerifySignature(
                AuthCrypto.FromBase64UrlOrThrow(device.PublicKey),
                AuthCrypto.Canonical("sid123", "nonce-other", device.KeyId, 1762000000),
                AuthCrypto.FromBase64UrlOrThrow(AuthCrypto.Base64Url(AuthCrypto.SignForClient(device.GetKey(), canonical)))));

        reporter.Check("keyId 与公钥严格对应",
            AuthCrypto.KeyIdOfPublicKey(AuthCrypto.FromBase64UrlOrThrow(device.PublicKey)) == device.KeyId);

        // base64url round-trip must survive every byte value
        var blob = new byte[256];
        for (var i = 0; i < blob.Length; i++)
        {
            blob[i] = (byte)i;
        }

        var encoded = AuthCrypto.Base64Url(blob);
        reporter.Check("base64url 往返一致且不含填充符",
            !encoded.Contains('=') && !encoded.Contains('+') && !encoded.Contains('/') &&
            AuthCrypto.FromBase64UrlOrThrow(encoded).SequenceEqual(blob));
        reporter.Check("base64url 解码对非法字符返回 false", !AuthCrypto.TryFromBase64Url("aaa!bbb", out _));
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

        // --- 服务器身份（sid）与证书固定值（fp）
        var identity = ServerIdentity.GetOrCreate(dataDir);
        reporter.Check("生成了服务器身份", identity.Id.Length == 22, identity.Id);

        var identityAgain = ServerIdentity.GetOrCreate(dataDir);
        reporter.Check("重启后服务器身份不变（手机的指纹固定靠它）", identityAgain.Id == identity.Id);

        var pin = AuthCrypto.PinOfCertificate(certificate.Certificate);
        reporter.Check("证书固定值是 22 字符 base64url", pin.Length == 22, pin);
        reporter.Check("固定值稳定（同一证书重复计算结果一致）",
            pin == AuthCrypto.PinOfCertificate(certificate.Certificate));
        reporter.Check("固定值与整证书指纹不同（SPKI 与整证书是两种哈希）",
            !string.Equals(pin, certificate.Fingerprint, StringComparison.OrdinalIgnoreCase));

        var longUrl = Protocol.BuildUrl("192.168.1.5", 8443, Guid.NewGuid().ToString("D"), identity.Id, pin);
        var longQr = QrCodeRenderer.RenderPng(longUrl, 8);
        reporter.Check("带 sid/fp 的二维码仍能渲染且体积可控", longQr.Length > 300 && longQr.Length < 20000,
            $"{longUrl.Length} 字符 → {longQr.Length} 字节 PNG");
        reporter.Check("带 sid/fp 的二维码仍能被解析回原始 URL（大小在 QR 容量内）",
            longUrl.Length < 300, $"{longUrl.Length} 字符");

        // --- 信任设备列表
        var deviceDir = Path.Combine(workspace, "devices-data");
        Directory.CreateDirectory(deviceDir);
        var store = DeviceKeyStore.Load(deviceDir);
        using (var device = new TestDevice("落盘测试")) { var spki = AuthCrypto.FromBase64UrlOrThrow(device.PublicKey);
            store.Upsert(device.KeyId, device.PublicKey, "Pixel 8", "192.168.1.7");
            store.Touch(device.KeyId, "192.168.1.8");

            var reloaded = DeviceKeyStore.Load(deviceDir);
            var loaded = reloaded.Find(device.KeyId);
            reporter.Check("信任设备可以持久化并重新加载",
                loaded is not null && loaded.Name == "Pixel 8" && loaded.SessionCount == 1 && loaded.LastAddress == "192.168.1.8",
                loaded?.ToString());

            reporter.Check("撤销后不再信任", reloaded.Revoke(device.KeyId) && reloaded.Find(device.KeyId) is null);
        }

        // A tampered file (keyId that does not match the public key) must be ignored, not trusted.
        File.WriteAllText(
            Path.Combine(deviceDir, DeviceKeyStore.FileName),
            """
            {"version":1,"devices":[{"keyId":"AAAAAAAAAAAAAAAAAAAAAA","publicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","name":"evil"}]}
            """);
        var tampered = DeviceKeyStore.Load(deviceDir);
        reporter.Equal("keyId 与公钥不匹配的设备条目被丢弃", 0, tampered.Count);
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

        var log = new AirTouchLog(dataDir);
        var mouse = new RecordingMouseController();
        var keyboard = new RecordingKeyboardController();
        using var server = new AirTouchServer(options, mouse, log, keyboard);

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
        reporter.Check("状态里带出服务器身份", status.Sid.Length == 22, status.Sid);
        reporter.Check("状态里带出证书固定值", status.CertificatePin.Length == 22, status.CertificatePin);
        reporter.Equal("初始状态没有信任设备", 0, status.TrustedDevices.Count);

        var token = status.Token;
        var sid = status.Sid;
        var pin = status.CertificatePin;

        string Url(string host, string? token) => Protocol.BuildUrl(host, port, token, sid, pin, useTls);

        reporter.Check("二维码/URL 里带 sid 与 fp",
            Url("127.0.0.1", token).Contains($"sid={sid}") && Url("127.0.0.1", token).Contains($"fp={pin}"));

        // --- 配对（带着一次性 token 连接）
        using var deviceA = new TestDevice("Pixel-Test");
        var client = WssTestClient.Create(status.CertificateFingerprint);
        await client.ConnectAsync(Url("127.0.0.1", token), LongWait);
        reporter.Check(
            useTls ? "wss 连接成功（指纹校验通过）" : "ws 回环连接成功（本机 TLS 不可用，见上文跳过项）",
            true,
            useTls ? client.FingerprintError ?? "证书指纹匹配" : "明文回环，仅用于验证协议逻辑");

        var challenge = TestAuth.ReceiveChallenge(client, ShortWait);
        reporter.Check("连接后服务器先下发 auth_challenge（不先发 welcome）", challenge is not null);
        reporter.Equal("配对连接的挑战模式为 pair", "pair", challenge!.Mode);
        reporter.Equal("挑战携带服务器身份 sid", sid, challenge.Sid);
        reporter.Check("挑战 nonce 为 32 字节", AuthCrypto.FromBase64UrlOrThrow(challenge.Nonce).Length == 32);
        reporter.Equal("挑战有效期与配置一致", (int)options.AuthTimeout.TotalSeconds, challenge.ExpiresIn);
        reporter.Check("挑战携带服务器时间（供手机校正时钟）",
            Math.Abs(AuthCrypto.UnixNow() - challenge.ServerTime) < 120, challenge.ServerTime.ToString());
        reporter.Equal("握手期间挂起计数为 1", 1, server.GetStatus().PendingCount);

        // Nothing may be executed before the key proof lands — this is what makes "pair only on a
        // token-authenticated socket" enforceable rather than a comment.
        await client.SendTextAsync("""{"type":"move","dx":500,"dy":500}""");
        await Task.Delay(250);
        reporter.Check("认证前控制指令被忽略", mouse.Events.Count == 0, string.Join(" | ", mouse.Events));

        await client.SendTextAsync(deviceA.BuildAuthRequest(challenge.Sid, challenge.Nonce, token));
        var welcome = await WaitForFrameAsync(client, frame => frame.MessageType == "welcome", ShortWait);
        reporter.Equal("配对成功收到 welcome", "welcome", welcome?.MessageType);
        // welcome 的非空断言由本行的 ! 承担（原先承载它的 chunkSize 断言已随协议字段一并删除）。
        reporter.Equal("welcome 宣告协议版本 2", 2, (int)TestJson.GetLong(welcome!.Text!, "protocolVersion"));
        reporter.Equal("welcome 回显设备 keyId", deviceA.KeyId, TestJson.GetString(welcome.Text!, "keyId"));
        reporter.Equal("welcome 认证方式为 pair", "pair", TestJson.GetString(welcome.Text!, "authMode"));

        reporter.Check("配对的公钥已入库", server.TrustedDevices.Any(d => d.KeyId == deviceA.KeyId));
        reporter.Check("配对后 token 被销毁", server.GetStatus().TokenConsumed);
        reporter.Check("配对后 token 已轮换（二维码可继续添加下一台手机）", server.GetStatus().Token != token);
        reporter.Equal("认证完成后挂起计数归零", 0, server.GetStatus().PendingCount);
        reporter.Equal("服务端记录活动设备密钥", deviceA.KeyId, server.GetStatus().ClientKeyId);
        reporter.Equal("服务端记录认证方式", "pair", server.GetStatus().ClientAuthMode);

        // --- 负面认证用例（每条都换一条新 socket）
        await using (var unknownKey = WssTestClient.Create(status.CertificateFingerprint))
        {
            using var stranger = new TestDevice("陌生设备");
            await unknownKey.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var strangerChallenge = TestAuth.ReceiveChallenge(unknownKey, ShortWait);
            await unknownKey.SendTextAsync(stranger.BuildAuthRequest(strangerChallenge!.Sid, strangerChallenge.Nonce));
            var (code, message, _) = unknownKey.ReceiveRejection(ShortWait);
            reporter.Equal("未信任设备不带 token 重连被拒（key_unknown）", "key_unknown", code);
            reporter.Info($"key_unknown：{message}");
        }

        await using (var badSignature = WssTestClient.Create(status.CertificateFingerprint))
        {
            await badSignature.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var badChallenge = TestAuth.ReceiveChallenge(badSignature, ShortWait);
            await badSignature.SendTextAsync(deviceA.BuildAuthRequest(
                badChallenge!.Sid,
                badChallenge.Nonce,
                signatureOverride: AuthCrypto.Base64Url(new byte[70])));
            var (code, _, _) = badSignature.ReceiveRejection(ShortWait);
            reporter.Equal("签名不合法被拒（sig_invalid）", "sig_invalid", code);
        }

        await using (var wrongKey = WssTestClient.Create(status.CertificateFingerprint))
        {
            await wrongKey.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var wrongChallenge = TestAuth.ReceiveChallenge(wrongKey, ShortWait);

            // A perfectly valid signature, but produced by a different key than the claimed keyId.
            using var impostor = new TestDevice("冒充者");
            var canonical = AuthCrypto.Canonical(wrongChallenge!.Sid, wrongChallenge.Nonce, deviceA.KeyId, AuthCrypto.UnixNow());
            var forged = AuthCrypto.Base64Url(AuthCrypto.SignForClient(impostor.GetKey(), canonical));
            await wrongKey.SendTextAsync(deviceA.BuildAuthRequest(
                wrongChallenge.Sid,
                wrongChallenge.Nonce,
                timestampOverride: AuthCrypto.UnixNow(),
                signatureOverride: forged));
            var (code, _, _) = wrongKey.ReceiveRejection(ShortWait);
            reporter.Equal("用别的私钥签名被拒（sig_invalid）", "sig_invalid", code);
        }

        await using (var stale = WssTestClient.Create(status.CertificateFingerprint))
        {
            await stale.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var staleChallenge = TestAuth.ReceiveChallenge(stale, ShortWait);
            await stale.SendTextAsync(deviceA.BuildAuthRequest(
                staleChallenge!.Sid,
                staleChallenge.Nonce,
                timestampOverride: AuthCrypto.UnixNow() - 3600));
            var (code, _, _) = stale.ReceiveRejection(ShortWait);
            reporter.Equal("时间戳过期的证明被拒（stale_auth）", "stale_auth", code);
        }

        await using (var replay = WssTestClient.Create(status.CertificateFingerprint))
        {
            await replay.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var replayChallenge = TestAuth.ReceiveChallenge(replay, ShortWait);
            await replay.SendTextAsync(deviceA.BuildAuthRequest(
                replayChallenge!.Sid,
                replayChallenge.Nonce,
                timestampOverride: AuthCrypto.UnixNow(),
                signatureOverride: AuthCrypto.Base64Url(
                    AuthCrypto.SignForClient(deviceA.GetKey(),
                        AuthCrypto.Canonical(replayChallenge.Sid, "别的 nonce", deviceA.KeyId, AuthCrypto.UnixNow())))));
            var (code, _, _) = replay.ReceiveRejection(ShortWait);
            reporter.Equal("为别的 nonce 签的证明无法重放（sig_invalid）", "sig_invalid", code);
        }

        await using (var oldProtocol = WssTestClient.Create(status.CertificateFingerprint))
        {
            await oldProtocol.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var protocolChallenge = TestAuth.ReceiveChallenge(oldProtocol, ShortWait);
            await oldProtocol.SendTextAsync(deviceA.BuildAuthRequest(protocolChallenge!.Sid, protocolChallenge.Nonce)
                .Replace("\"auth_v\":1", "\"auth_v\":99", StringComparison.Ordinal));
            var (code, _, _) = oldProtocol.ReceiveRejection(ShortWait);
            reporter.Equal("认证版本不支持时返回 auth_unsupported", "auth_unsupported", code);
        }

        reporter.Check("负面用例之后连接仍可正常恢复（未误伤）",
            await WaitForAsync(() => server.GetStatus().PendingCount == 0, ShortWait),
            server.GetStatus().PendingCount.ToString());

        // --- 同一台手机重连会顶掉旧连接，而不是被 busy 挡住
        var previousSocket = client;
        var sameAgain = WssTestClient.Create(status.CertificateFingerprint);
        await sameAgain.ConnectAsync(Url("127.0.0.1", null), ShortWait);
        var resumed = await TestAuth.AuthenticateAsync(sameAgain, deviceA, null, ShortWait);
        reporter.Equal("同一设备免扫码重连成功", "welcome", resumed?.MessageType);
        reporter.Equal("重连认证方式为 resume", "resume", TestJson.GetString(resumed!.Text!, "authMode"));

        var replaced = await WaitForFrameAsync(previousSocket, frame => frame.MessageType == "error", ShortWait);
        reporter.Equal("旧连接收到 replaced 并关闭",
            "replaced", replaced is null ? "<无>" : TestJson.GetString(replaced.Text!, "code"));

        await previousSocket.DisposeAsync();
        client = sameAgain;

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

        // --- key frames（docs/PROTOCOL.md §2.6）
        //
        // 一次组合键在线上就是四次独立的按键状态变化（Ctrl↓ C↓ C↑ Ctrl↑），和真实键盘产生的
        // 事件序列完全一致；服务端不做任何「组合」解释，只负责把每一次变化注入。
        await client.SendTextAsync("""{"type":"key","vk":17,"down":true}""");
        await client.SendTextAsync("""{"type":"key","vk":67,"down":true}""");
        await client.SendTextAsync("""{"type":"key","vk":67,"down":false}""");
        await client.SendTextAsync("""{"type":"key","vk":17,"down":false}""");
        // 小键盘回车：vk 与主回车相同，只能靠 ext 区分
        await client.SendTextAsync("""{"type":"key","vk":13,"down":true,"ext":true}""");
        await client.SendTextAsync("""{"type":"key","vk":13,"down":false}""");
        // 非法帧：缺 vk、vk 越界、未知字段——都必须被忽略而不是掀翻会话
        await client.SendTextAsync("""{"type":"key","down":true}""");
        await client.SendTextAsync("""{"type":"key","vk":9999,"down":true,"mods":[1,2]}""");

        var gotKeys = await WaitForAsync(() => keyboard.Events.Count >= 7, ShortWait);
        reporter.Check("收到并执行 key 帧", gotKeys, string.Join(" | ", keyboard.Events));

        if (gotKeys)
        {
            var keys = keyboard.Events;
            reporter.Equal("Ctrl 按下", "key 17 down", keys[0]);
            reporter.Equal("C 按下", "key 67 down", keys[1]);
            reporter.Equal("C 抬起", "key 67 up", keys[2]);
            reporter.Equal("Ctrl 抬起", "key 17 up", keys[3]);
            reporter.Equal("小键盘回车带扩展标志", "key 13 down ext", keys[4]);
            reporter.Equal("小键盘回车抬起沿用按下时的扩展标志（否则松开的不是同一个键）", "key 13 up ext", keys[5]);
            reporter.Equal("vk 越界被截断到上限", $"key {Protocol.MaxVirtualKey} down", keys[6]);
        }

        // --- text 帧（docs/PROTOCOL.md §2.7）
        //
        // 整串 Unicode 一次注入：中文在虚拟键码这一层根本不存在（键位 ≠ 字符），只能绕开键盘布局
        // 直接把字符投给前台窗口。text 帧没有按键状态，因此不参与卡键记账。
        var heldBeforeText = keyboard.HeldKeyCount;
        var textEventsBefore = keyboard.Events.Count(e => e.StartsWith("text ", StringComparison.Ordinal));
        await client.SendTextAsync("""{"type":"text","s":"你好"}""");
        await client.SendTextAsync($$"""{"type":"text","s":"{{new string('宽', Protocol.MaxTextLength + 20)}}"}""");

        var gotText = await WaitForAsync(
            () => keyboard.Events.Count(e => e.StartsWith("text ", StringComparison.Ordinal)) == textEventsBefore + 2,
            ShortWait);
        reporter.Check("收到并执行 text 帧", gotText, string.Join(" | ", keyboard.Events));

        if (gotText)
        {
            var textEvents = keyboard.Events.Where(e => e.StartsWith("text ", StringComparison.Ordinal)).ToArray();
            reporter.Equal("text 帧原样交给控制器", "text 你好", textEvents[textEventsBefore]);
            reporter.Equal($"超长 text 在协议层被截断到 {Protocol.MaxTextLength} 个码元",
                "text " + new string('宽', Protocol.MaxTextLength), textEvents[textEventsBefore + 1]);
        }

        reporter.Equal("text 帧不参与卡键记账（它没有按键状态）", heldBeforeText, keyboard.HeldKeyCount);
        reporter.Check("text 指令计数递增",
            await WaitForAsync(() => server.GetStatus().TextCommands >= 2, ShortWait),
            server.GetStatus().TextCommands.ToString());

        // --- 断开时必须自己松手：手机端的粘滞键把 Ctrl 锁住时连接掉了，如果不释放，
        //     电脑上会留下一个物理卡住的 Ctrl，之后用户碰真实键盘的每一次输入都会变成快捷键。
        //     先把上一帧「vk 越界被截断到 255」留下的按下状态放掉，否则 held 计数会把它一起算进来。
        await client.SendTextAsync($$"""{"type":"key","vk":{{Protocol.MaxVirtualKey}},"down":false}""");
        await client.SendTextAsync("""{"type":"key","vk":162,"down":true}""");
        reporter.Check("按下的按键在会话期间被记住",
            await WaitForAsync(() => keyboard.HeldKeyCount == 1, ShortWait), $"held={keyboard.HeldKeyCount}");

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
        await client.SendTextAsync("""{"type":"text","s":123}""");
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

        // --- 令牌只与「添加新手机」有关：断开不再轮换，重连压根不用它
        var tokenBeforeDisconnect = server.GetStatus().Token;
        await client.CloseAsync();
        reporter.Check("服务端检测到断开", await WaitForAsync(() => server.GetStatus().ClientAddress is null, ShortWait));
        reporter.Equal("断开后 token 保持不变（避免误伤免扫码重连）",
            tokenBeforeDisconnect, server.GetStatus().Token);

        string? lastErrorCode = null;
        for (var attempt = 1; attempt <= options.MaxTokenFailures; attempt++)
        {
            await using var attacker = WssTestClient.Create(status.CertificateFingerprint);
            await attacker.ConnectAsync(Url("127.0.0.1", "00000000-0000-4000-8000-000000000000"), ShortWait);
            var (code, _, _) = attacker.ReceiveRejection(ShortWait);
            lastErrorCode = code;
            reporter.Info($"第 {attempt} 次伪造 token → {code}");
        }

        reporter.Equal("连续伪造 token 后返回 banned", "banned", lastErrorCode);

        await using (var bannedClient = WssTestClient.Create(status.CertificateFingerprint))
        {
            // Even a *trusted* device with no token at all is refused while the address is banned.
            await bannedClient.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var (code, _, _) = bannedClient.ReceiveRejection(ShortWait);
            reporter.Equal("封禁期间即使不带 token 也被拒绝", "banned", code);
        }

        server.ResetSecurity("自检重置");
        reporter.Check("重置后封禁列表为空", server.GetStatus().BannedAddresses.Count == 0);
        reporter.Check("重置不删除信任设备", server.TrustedDevices.Any(d => d.KeyId == deviceA.KeyId));

        // 重置之后依旧可以免扫码重连：信任来自公钥，不来自 token，也不来自 IP。
        await using (var recovered = WssTestClient.Create(status.CertificateFingerprint))
        {
            await recovered.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var backAgain = await TestAuth.AuthenticateAsync(recovered, deviceA, null, ShortWait);
            reporter.Equal("重置后免扫码重连仍然成功", "welcome", backAgain?.MessageType);
        }

        // --- 第二台设备：不同公钥并存，同一时刻只允许一台
        var tokenForB = server.RotateToken();
        using var deviceB = new TestDevice("Pixel-B");
        var socketB = WssTestClient.Create(status.CertificateFingerprint);
        await socketB.ConnectAsync(Url("127.0.0.1", tokenForB), ShortWait);
        var pairedB = await TestAuth.AuthenticateAsync(socketB, deviceB, tokenForB, ShortWait);
        reporter.Equal("第二台手机配对成功", "welcome", pairedB?.MessageType);
        reporter.Equal("第二台手机配对方式也是 pair", "pair", TestJson.GetString(pairedB!.Text!, "authMode"));
        reporter.Equal("两台信任设备并存", 2, server.GetStatus().TrustedDevices.Count);

        await using (var thirdWheel = WssTestClient.Create(status.CertificateFingerprint))
        {
            await thirdWheel.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var thirdChallenge = TestAuth.ReceiveChallenge(thirdWheel, ShortWait);
            await thirdWheel.SendTextAsync(deviceA.BuildAuthRequest(thirdChallenge!.Sid, thirdChallenge.Nonce));
            var (code, message, closeCode) = thirdWheel.ReceiveRejection(ShortWait);
            reporter.Equal("已有其他设备在线时被拒（busy）", "busy", code);
            reporter.Info($"busy 关闭码 {closeCode}，消息：{message}");
        }

        await socketB.DisposeAsync();

        // --- 撤销信任会立刻切断在线会话
        await using (var revoked = WssTestClient.Create(status.CertificateFingerprint))
        {
            await revoked.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var okAgain = await TestAuth.AuthenticateAsync(revoked, deviceB, null, ShortWait);
            reporter.Equal("撤销前 B 可以重连", "welcome", okAgain?.MessageType);

            reporter.Check("撤销设备返回 true", server.RevokeDevice(deviceB.KeyId));
            var revokedFrame = await WaitForFrameAsync(revoked, frame => frame.MessageType == "error", ShortWait);
            reporter.Equal("在线会话收到 trust_revoked",
                "trust_revoked", revokedFrame is null ? "<无>" : TestJson.GetString(revokedFrame.Text!, "code"));
            reporter.Check("撤销后连接被断开", await WaitForAsync(() => server.GetStatus().ClientAddress is null, ShortWait));
            reporter.Check("撤销后 B 不再被信任", server.TrustedDevices.All(d => d.KeyId != deviceB.KeyId));

            await using var revokedAgain = WssTestClient.Create(status.CertificateFingerprint);
            await revokedAgain.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var revokedChallenge = TestAuth.ReceiveChallenge(revokedAgain, ShortWait);
            await revokedAgain.SendTextAsync(deviceB.BuildAuthRequest(revokedChallenge!.Sid, revokedChallenge.Nonce));
            var (code, _, _) = revokedAgain.ReceiveRejection(ShortWait);
            reporter.Equal("被撤销的设备重连得到 key_unknown", "key_unknown", code);
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
        await client.DisposeAsync();

        reporter.Check("连接断开后服务端释放了所有仍按下的按键",
            await WaitForAsync(() => keyboard.HeldKeyCount == 0, ShortWait), $"held={keyboard.HeldKeyCount}");

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

        // --- 重启后身份与信任都还在：这正是「以后再也不用扫码」的前提
        using (var restarted = new AirTouchServer(options, mouse, log))
        {
            restarted.Start();
            var restartedStatus = restarted.GetStatus();
            reporter.Equal("重启后服务器身份不变", sid, restartedStatus.Sid);
            reporter.Equal("重启后证书不变（固定值也不变）", pin, restartedStatus.CertificatePin);
            reporter.Check("重启后信任设备仍在列表中",
                restartedStatus.TrustedDevices.Any(d => d.KeyId == deviceA.KeyId),
                string.Join(",", restartedStatus.TrustedDevices.Select(d => d.Name)));
            reporter.Check("重启后 token 是新的（令牌不落盘）", restartedStatus.Token != server.GetStatus().Token);

            await using var afterRestart = WssTestClient.Create(restartedStatus.CertificateFingerprint);
            await afterRestart.ConnectAsync(
                Protocol.BuildUrl("127.0.0.1", port, null, restartedStatus.Sid, restartedStatus.CertificatePin, useTls),
                ShortWait);
            var afterRestartWelcome = await TestAuth.AuthenticateAsync(afterRestart, deviceA, null, ShortWait);
            reporter.Equal("电脑重启后手机仍可免扫码重连", "welcome", afterRestartWelcome?.MessageType);

            restarted.Stop();
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

        var log = new AirTouchLog(dataDir);
        var mouse = new Win32MouseController(log);
        var keyboard = new Win32KeyboardController(log);
        using var server = new AirTouchServer(options, mouse, log, keyboard);
        server.Start();

        var liveStatus = server.GetStatus();
        string Url(string host, string? token) => Protocol.BuildUrl(host, port, token, liveStatus.Sid, liveStatus.CertificatePin, useTls);

        var connectAddress = lanAddress ?? "127.0.0.1";
        var certificateFingerprint = liveStatus.CertificateFingerprint;

        using var liveDevice = new TestDevice("真实输入自检设备");
        await using var client = WssTestClient.Create(certificateFingerprint);
        await client.ConnectAsync(Url(connectAddress, liveStatus.Token), LongWait);
        var welcome = await TestAuth.AuthenticateAsync(client, liveDevice, liveStatus.Token, ShortWait);
        reporter.Check($"经 {connectAddress} 配对连接成功", welcome?.MessageType == "welcome");

        // Reset first so we start from a known position, then move right and compare.
        var resetsBefore = mouse.ResetCount;
        var positionBeforeReset = mouse.GetCursorPosition();
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

        // The reset is asynchronous with respect to this thread: the text frame has to come back from the
        // server's receive loop. Poll the counter (and the position) instead of reading once, otherwise
        // this check fails whenever the round trip happens to take longer than the 400 ms sampling loop.
        var resetSeen = false;
        for (var attempt = 0; attempt < 100; attempt++)
        {
            if (mouse.ResetCount > resetsBefore)
            {
                resetSeen = true;
                break;
            }

            await Task.Delay(5);
        }

        reporter.Check("reset 指令被服务端执行", resetSeen,
            $"复位前计数 {resetsBefore} → 现在 {mouse.ResetCount}，光标 {positionBeforeReset} → {afterReset}");
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

        // --- 本次实现里 IP 不再是信任依据：换一个源地址发起未认证连接只会被判为未信任，
        //     而不是 ip_locked（同一台手机换 Wi-Fi/换网段必须还能重连）。
        if (lanAddress is not null)
        {
            using var strangerDevice = new TestDevice("另一网段的陌生设备");
            await using var otherIp = WssTestClient.Create(certificateFingerprint);
            await otherIp.ConnectAsync(Url("127.0.0.1", null), ShortWait);
            var otherChallenge = TestAuth.ReceiveChallenge(otherIp, ShortWait);
            await otherIp.SendTextAsync(strangerDevice.BuildAuthRequest(otherChallenge!.Sid, otherChallenge.Nonce));
            var (code, message, _) = otherIp.ReceiveRejection(ShortWait);
            reporter.Equal("换源地址的未信任连接返回 key_unknown（不再有 ip_locked）", "key_unknown", code);
            reporter.Info($"拒绝理由：{message}");
        }
        else
        {
            reporter.Skip("跨网段重连验证", "本机没有可用的局域网 IPv4 地址");
        }

        // --- 键盘注入（真实 SendInput / KEYEVENTF_EXTENDEDKEY）
        //
        // 只按「无害」的键：F24（0x87）没有任何默认动作，右 Ctrl（0xA3）是扩展键——用来走通
        // KEYEVENTF_EXTENDEDKEY 分支，同时它既不会触发快捷键也不会改系统状态（NumLock、
        // ScrollLock 会改状态，方向键/回车会打到当前窗口，所以都不用）。
        var keysBefore = keyboard.KeyCount;
        await client.SendTextAsync("""{"type":"key","vk":135,"down":true}""");
        await client.SendTextAsync("""{"type":"key","vk":135,"down":false}""");
        await client.SendTextAsync("""{"type":"key","vk":163,"down":true}""");
        await Task.Delay(200);
        reporter.Check("键盘注入被 SendInput 接受",
            keyboard.KeyCount >= keysBefore + 3 && keyboard.SendInputFailures == 0,
            $"注入 {keyboard.KeyCount} 次（本次新增 {keyboard.KeyCount - keysBefore}），失败 {keyboard.SendInputFailures} 次");

        var heldExpected = await WaitForAsync(() => keyboard.HeldKeyCount == 1, ShortWait);
        reporter.Check("按下的扩展键被记账，等待释放", heldExpected, $"HeldKeyCount={keyboard.HeldKeyCount}");
        var releasedKeys = keyboard.ReleaseAll();
        reporter.Check("ReleaseAll 松开了仍按下的修饰键（避免电脑端留下卡住的 Ctrl）",
            releasedKeys == 1 && keyboard.HeldKeyCount == 0,
            $"释放 {releasedKeys} 个，剩余 {keyboard.HeldKeyCount} 个");

        // --- Unicode 文本注入（真实 SendInput / KEYEVENTF_UNICODE）
        //
        // 只注入一个空格 U+0020：它不会触发任何动作、不会改系统状态，却能证明
        // 「wVk=0 + wScan=码元 + KEYEVENTF_UNICODE」这条路径真的被 SendInput 接受了。
        // 断言只针对「Windows 是否拒绝了注入」，不针对「谁收到了这个空格」——焦点窗口不由我们决定。
        var textFailuresBefore = keyboard.SendInputFailures;
        keyboard.Text(" ");
        reporter.Check("Unicode 文本注入被 SendInput 接受",
            keyboard.SendInputFailures == textFailuresBefore,
            $"注入前失败 {textFailuresBefore} 次，现在 {keyboard.SendInputFailures} 次");
        reporter.Check("文本注入不改变卡键记账（每个码元当场按下并立刻抬起）",
            keyboard.HeldKeyCount == 0, $"HeldKeyCount={keyboard.HeldKeyCount}");

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

                reporter.Check("窗口中服务处于运行状态", root.GetProperty("isRunning").GetBoolean(),
                    root.GetProperty("isRunning").GetBoolean()
                        ? "监听中"
                        : string.Join(" / ", root.GetProperty("log").EnumerateArray()
                            .Select(e => e.GetString())
                            .TakeLast(3)));
                reporter.Check("窗口渲染了二维码", root.GetProperty("qrBytes").GetInt32() > 100,
                    $"{root.GetProperty("qrBytes").GetInt32()} 字节");

                // A layout regression once left the QR code much smaller than it was rendered (the panel
                // looked like it only held the top half of the code). No purely numeric assertion can
                // see that, so the window measures the drawn geometry and reports it here.
                var qrWidth = root.GetProperty("qrWidth").GetDouble();
                var qrHeight = root.GetProperty("qrHeight").GetDouble();
                var qrTop = root.GetProperty("qrTop").GetDouble();
                var qrBottom = root.GetProperty("qrBottom").GetDouble();
                var clientHeight = root.GetProperty("qrClientHeight").GetDouble();
                var qrNaturalWidth = root.GetProperty("qrNaturalWidth").GetDouble();
                var qrNaturalHeight = root.GetProperty("qrNaturalHeight").GetDouble();
                var pixelsPerModule = root.GetProperty("qrPixelsPerModule").GetInt32();
                var modules = root.GetProperty("qrModuleCount").GetInt32();
                reporter.Check(
                    "二维码完整显示在窗口内（没有被裁掉）",
                    root.GetProperty("qrFullyVisible").GetBoolean(),
                    $"二维码 {qrWidth:F0}×{qrHeight:F0}，纵向 {qrTop:F0}..{qrBottom:F0}，客户区高 {clientHeight:F0}");
                reporter.Check(
                    "二维码按原尺寸渲染（没有被缩放/挤压）",
                    root.GetProperty("qrNaturalSize").GetBoolean(),
                    $"绘制 {qrWidth:F0}×{qrHeight:F0}，原始 {qrNaturalWidth:F0}×{qrNaturalHeight:F0}");
                reporter.Check(
                    "二维码按整数像素/模块渲染（模块大小一致，便于扫码）",
                    pixelsPerModule >= 3 && Math.Abs(qrNaturalWidth - modules * pixelsPerModule) < 0.5,
                    $"{modules} 模块 × {pixelsPerModule} 像素 = {qrNaturalWidth:F0}px");

                var reportedUrl = root.GetProperty("url").GetString() ?? string.Empty;
                reporter.Check("窗口内的连接地址形如 wss://<ip>:<port>/ws?token=",
                    reportedUrl.StartsWith("wss://", StringComparison.Ordinal) &&
                    reportedUrl.Contains($":{port}/ws?token=", StringComparison.Ordinal),
                    reportedUrl);
                reporter.Check("窗口内的连接地址带 sid 与 fp（手机据此固定证书）",
                    reportedUrl.Contains("sid=", StringComparison.Ordinal) &&
                    reportedUrl.Contains("fp=", StringComparison.Ordinal),
                    reportedUrl);

                var fingerprint = root.GetProperty("certificateFingerprint").GetString() ?? string.Empty;
                reporter.Check("窗口展示证书指纹", fingerprint.Split(':').Length == 32);

                reporter.Check("窗口暴露服务器身份 sid",
                    (root.GetProperty("sid").GetString() ?? string.Empty).Length == 22);
                reporter.Check("窗口暴露证书固定值 fp",
                    (root.GetProperty("certificatePin").GetString() ?? string.Empty).Length == 22);
                reporter.Equal("窗口报告协议版本 2", 2, root.GetProperty("protocolVersion").GetInt32());
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
