namespace AirTouch.Core;

/// <summary>Why a connection was allowed to enter the authentication exchange.</summary>
public enum AuthMode
{
    /// <summary>A valid one-time token was presented: this connection may register a new device key.</summary>
    Pairing,

    /// <summary>No token was presented: the connection may only resume with an already trusted key.</summary>
    Resume,
}

/// <summary>Outcome of the pre-handshake authorization performed for every new connection.</summary>
public enum AuthResult
{
    AllowPairing,
    AllowResume,
    BadPath,
    Banned,
    RateLimited,
    IpLocked,
    Busy,
    TooManyPending,
    TokenInvalid,
    TokenExpired,
}

public sealed record AuthOutcome(AuthResult Result, string Message)
{
    public bool IsAllowed => Result is AuthResult.AllowPairing or AuthResult.AllowResume;

    /// <summary>Mode for an allowed connection; meaningless for a rejection.</summary>
    public AuthMode Mode => Result == AuthResult.AllowPairing ? AuthMode.Pairing : AuthMode.Resume;

    /// <summary>WebSocket close code sent back to the rejected peer.</summary>
    public int CloseCode => Result switch
    {
        AuthResult.Busy => 1013,          // "try again later"
        AuthResult.TooManyPending => 1013,
        _ => 1008,                        // "policy violation"
    };

    /// <summary>Machine readable code sent inside the final <c>error</c> text frame.</summary>
    public string ErrorCode => Result switch
    {
        AuthResult.BadPath => "bad_path",
        AuthResult.Banned => "banned",
        AuthResult.RateLimited => "rate_limited",
        AuthResult.IpLocked => "ip_locked",
        AuthResult.Busy => "busy",
        AuthResult.TooManyPending => "too_many_pending",
        AuthResult.TokenInvalid => "token_invalid",
        AuthResult.TokenExpired => "token_expired",
        _ => "ok",
    };
}

/// <summary>
/// Owns every connection-level security decision: the one-time pairing token, the pending-auth budget,
/// brute-force lockout and a coarse per-IP rate limit.
/// </summary>
/// <remarks>
/// <para>
/// Protocol v2 replaced "the first IP that authenticates owns the PC" with "a device key owns the PC"
/// (docs/PROTOCOL.md §4). Address binding could not survive a DHCP lease change and said nothing about
/// <em>which</em> phone was connecting; a signature over a server nonce does both. The guard therefore
/// no longer gates on the peer address: an unauthenticated socket is not rejected outright but admitted
/// as <em>pending</em>, with a hard auth deadline and a small concurrency budget, and it only becomes the
/// active session after the key proof succeeds.
/// </para>
/// <para>
/// Deliberately free of networking types so the policy can be unit tested and fuzzed directly.
/// All members are safe to call from Fleck's worker threads.
/// </para>
/// </remarks>
public sealed class ConnectionGuard
{
    public const string WebSocketPath = "/ws";

    /// <summary>How many already-consumed tokens are remembered for the <c>token_expired</c> answer.</summary>
    private const int ConsumedTokenMemory = 8;

    private readonly object _gate = new();
    private readonly AirTouchOptions _options;
    private readonly IAirTouchLog _log;
    private readonly Dictionary<string, int> _tokenFailures = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, int> _authFailures = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _bannedAddresses = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, List<DateTimeOffset>> _attempts = new(StringComparer.OrdinalIgnoreCase);
    private readonly Queue<string> _consumedTokens = new();

    private string _token;
    private bool _tokenConsumed;
    private int _pending;
    private string? _activeKeyId;
    private string? _activeDeviceName;
    private string? _activeAddress;

    public ConnectionGuard(AirTouchOptions options, IAirTouchLog? log = null)
    {
        _options = options;
        _log = log ?? NullLog.Instance;
        _token = NewToken();
    }

    /// <summary>Raised (outside the lock) whenever observable security state changes.</summary>
    public event EventHandler? Changed;

    public string Token
    {
        get
        {
            lock (_gate)
            {
                return _token;
            }
        }
    }

    /// <summary>
    /// True once a pairing has consumed a token since the last manual rotation or reset (the current
    /// token itself is always fresh — <see cref="MarkPaired"/> rotates immediately). Display only.
    /// </summary>
    public bool TokenConsumed
    {
        get
        {
            lock (_gate)
            {
                return _tokenConsumed;
            }
        }
    }

    /// <summary>Key id of the device that currently owns the session, or null when nobody is connected.</summary>
    public string? ActiveKeyId
    {
        get
        {
            lock (_gate)
            {
                return _activeKeyId;
            }
        }
    }

    public string? ActiveDeviceName
    {
        get
        {
            lock (_gate)
            {
                return _activeDeviceName;
            }
        }
    }

    public string? ActiveAddress
    {
        get
        {
            lock (_gate)
            {
                return _activeAddress;
            }
        }
    }

    public bool HasActiveClient
    {
        get
        {
            lock (_gate)
            {
                return _activeKeyId is not null;
            }
        }
    }

    /// <summary>Number of sockets currently waiting for their <c>auth_req</c>.</summary>
    public int PendingCount
    {
        get
        {
            lock (_gate)
            {
                return _pending;
            }
        }
    }

    public IReadOnlyCollection<string> BannedAddresses
    {
        get
        {
            lock (_gate)
            {
                return _bannedAddresses.ToArray();
            }
        }
    }

    public int FailuresFor(string address)
    {
        lock (_gate)
        {
            return _tokenFailures.TryGetValue(address, out var count) ? count : 0;
        }
    }

    public int AuthFailuresFor(string address)
    {
        lock (_gate)
        {
            return _authFailures.TryGetValue(address, out var count) ? count : 0;
        }
    }

    public static string NewToken() => Guid.NewGuid().ToString("D");

    /// <summary>
    /// Evaluates a new connection attempt. Checks run in a fixed order so the client always gets the most
    /// specific reason: path, ban, rate limit, pending budget, then the token.
    /// </summary>
    public AuthOutcome TryAuthorize(
        string clientAddress,
        string? rawPath,
        IEnumerable<KeyValuePair<string, string>>? headers = null)
    {
        if (!ConnectionRequest.TryParse(rawPath, out var path, out var query))
        {
            return new AuthOutcome(AuthResult.BadPath, "请求路径无效");
        }

        if (!string.Equals(path, WebSocketPath, StringComparison.OrdinalIgnoreCase))
        {
            return new AuthOutcome(AuthResult.BadPath, $"路径必须是 {WebSocketPath}");
        }

        var normalized = Normalize(clientAddress);
        var stateChanged = false;
        AuthOutcome outcome;

        lock (_gate)
        {
            if (_bannedAddresses.Contains(normalized))
            {
                _log.Warn($"拒绝已封禁的 IP：{normalized}");
                return new AuthOutcome(AuthResult.Banned, "该 IP 已被封禁");
            }

            if (IsRateLimited(normalized))
            {
                _log.Warn($"拒绝连接过于频繁的 IP：{normalized}");
                return new AuthOutcome(AuthResult.RateLimited, "连接过于频繁，请稍后再试");
            }

            if (_pending >= _options.MaxPendingSockets)
            {
                _log.Warn($"拒绝连接：等待认证的连接已达上限 {_options.MaxPendingSockets}（{normalized}）");
                return new AuthOutcome(AuthResult.TooManyPending, "服务器正忙，请稍后重试");
            }

            // The token arrives in the query string (the QR code format) or, as a fallback, in an
            // X-Auth-Token header for clients that cannot keep a query string.
            var presented = ConnectionRequest.GetToken(query, headers);

            if (string.IsNullOrEmpty(presented))
            {
                // No token at all: the normal "I am already paired" path. It is admitted as a pending
                // connection and must prove possession of a trusted key before anything else.
                outcome = new AuthOutcome(AuthResult.AllowResume, "等待设备密钥认证");
            }
            else if (IsConsumedToken(presented))
            {
                // A replay of an already-consumed token is not a brute-force attempt: it is what a phone
                // does when it still remembers a stale QR. It must not ban the device.
                _log.Warn($"拒绝重放已销毁的 token：{normalized}");
                outcome = new AuthOutcome(AuthResult.TokenExpired, "token 已失效，请重新扫码");
            }
            else if (!FixedTimeEquals(presented, _token))
            {
                RegisterFailure(normalized, "token 不匹配");
                outcome = _bannedAddresses.Contains(normalized)
                    ? new AuthOutcome(AuthResult.Banned, "token 连续校验失败，该 IP 已被封禁")
                    : new AuthOutcome(AuthResult.TokenInvalid, "token 校验失败");

                stateChanged = true;
            }
            else
            {
                outcome = new AuthOutcome(AuthResult.AllowPairing, "ok");
            }

            if (outcome.IsAllowed)
            {
                _pending++;
            }
        }

        if (stateChanged)
        {
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return outcome;
    }

    /// <summary>True when <paramref name="presented"/> is a token a previous pairing already consumed.</summary>
    /// <remarks>
    /// Only the replay memory is consulted. Comparing against <see cref="TokenConsumed"/> and the
    /// <em>current</em> token would be wrong: <see cref="MarkPaired"/> rotates immediately, so the
    /// freshly issued token is by definition not consumed and must be accepted at once — otherwise the
    /// QR code on screen would be dead for the next phone.
    /// </remarks>
    private bool IsConsumedToken(string presented)
    {
        foreach (var consumed in _consumedTokens)
        {
            if (FixedTimeEquals(presented, consumed))
            {
                return true;
            }
        }

        return false;
    }

    /// <summary>
    /// Re-checks the pairing token carried inside the <c>auth_req</c> frame. The query-string token was
    /// already validated when the socket opened, but it may have been consumed by another phone in the
    /// meantime, so the check has to happen again at the moment the device key is registered.
    /// </summary>
    public AuthOutcome CheckPairingToken(string clientAddress, string? presented)
    {
        var normalized = Normalize(clientAddress);
        var stateChanged = false;
        AuthOutcome outcome;

        lock (_gate)
        {
            if (string.IsNullOrEmpty(presented))
            {
                RegisterFailure(normalized, "auth_req 缺少 token");
                stateChanged = true;
                outcome = _bannedAddresses.Contains(normalized)
                    ? new AuthOutcome(AuthResult.Banned, "token 连续校验失败，该 IP 已被封禁")
                    : new AuthOutcome(AuthResult.TokenInvalid, "auth_req 缺少 token");
            }
            else if (IsConsumedToken(presented))
            {
                outcome = new AuthOutcome(AuthResult.TokenExpired, "token 已被使用，请重新扫码");
            }
            else if (!FixedTimeEquals(presented, _token))
            {
                RegisterFailure(normalized, "auth_req token 不匹配");
                stateChanged = true;
                outcome = _bannedAddresses.Contains(normalized)
                    ? new AuthOutcome(AuthResult.Banned, "token 连续校验失败，该 IP 已被封禁")
                    : new AuthOutcome(AuthResult.TokenInvalid, "token 校验失败");
            }
            else
            {
                outcome = new AuthOutcome(AuthResult.AllowPairing, "ok");
            }
        }

        if (stateChanged)
        {
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return outcome;
    }

    /// <summary>Releases a pending slot when a socket goes away or finishes authenticating.</summary>
    public void EndPending()
    {
        lock (_gate)
        {
            if (_pending > 0)
            {
                _pending--;
            }
        }
    }

    /// <summary>
    /// Consumes the one-time token after a successful pairing and immediately issues a fresh one, so the
    /// QR code on screen stays usable for pairing the next device.
    /// </summary>
    public void MarkPaired(string clientAddress)
    {
        var normalized = Normalize(clientAddress);

        lock (_gate)
        {
            if (!_tokenConsumed)
            {
                _consumedTokens.Enqueue(_token);
                while (_consumedTokens.Count > ConsumedTokenMemory)
                {
                    _consumedTokens.Dequeue();
                }
            }

            _tokenConsumed = true;
            _token = NewToken();
            _tokenFailures.Remove(normalized);
            _log.Info("配对成功：token 已销毁并生成新 token（屏幕上的二维码可继续用于配对下一台设备）");
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Marks the session as owned by <paramref name="keyId"/> and clears that peer's failure counters.</summary>
    public void MarkAuthenticated(string keyId, string? deviceName, string? address)
    {
        var normalized = Normalize(address);

        lock (_gate)
        {
            _activeKeyId = keyId;
            _activeDeviceName = deviceName;
            _activeAddress = address;
            _authFailures.Remove(normalized);
            _tokenFailures.Remove(normalized);
            _log.Info($"设备认证通过：{deviceName ?? "未知设备"}（keyId {keyId}，来源 {normalized}）");
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Clears the active session when its socket goes away. The token is left untouched.</summary>
    public void MarkDisconnected(string? address)
    {
        var disconnected = false;

        lock (_gate)
        {
            if (_activeKeyId is not null)
            {
                disconnected = true;
                _activeKeyId = null;
                _activeDeviceName = null;
                _activeAddress = null;
            }
        }

        if (disconnected)
        {
            _log.Info($"设备已断开：{Normalize(address)}；token 保持不变（重连无需扫码）");
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>
    /// Records a failed key proof. Returns true when the peer just became banned. Kept separate from the
    /// token counter so a phone with a skewed clock, or a phone whose key store was wiped, cannot ban
    /// itself by retrying.
    /// </summary>
    public bool RegisterAuthFailure(string address, string reason)
    {
        var normalized = Normalize(address);
        bool banned;

        lock (_gate)
        {
            var failures = _authFailures.TryGetValue(normalized, out var existing) ? existing + 1 : 1;
            _authFailures[normalized] = failures;
            _log.Warn($"设备密钥认证失败 {failures}/{_options.MaxAuthFailures}：{normalized}（{reason}）");

            banned = failures >= _options.MaxAuthFailures && _bannedAddresses.Add(normalized);
            if (banned)
            {
                _log.Error($"IP {normalized} 连续 {failures} 次设备认证失败，已永久封禁");
            }
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return banned;
    }

    /// <summary>Issues a new token without touching the trust list (used by the "刷新二维码" button).</summary>
    public string RotateToken()
    {
        lock (_gate)
        {
            _token = NewToken();
            _tokenConsumed = false;
        }

        Changed?.Invoke(this, EventArgs.Empty);
        return Token;
    }

    /// <summary>
    /// Full reset of the connection-level state: new token, pending slots cleared, bans lifted.
    /// The trusted device keys live in <see cref="DeviceKeyStore"/> and are revoked separately.
    /// </summary>
    public void Reset(string reason)
    {
        lock (_gate)
        {
            _token = NewToken();
            _tokenConsumed = false;
            _consumedTokens.Clear();
            _activeKeyId = null;
            _activeDeviceName = null;
            _activeAddress = null;
            _bannedAddresses.Clear();
            _tokenFailures.Clear();
            _authFailures.Clear();
            _attempts.Clear();
            _pending = 0;
            _log.Info($"安全状态已重置（{reason}）；已解除封禁并生成新 token");
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public bool Unban(string address)
    {
        var normalized = Normalize(address);
        bool removed;

        lock (_gate)
        {
            removed = _bannedAddresses.Remove(normalized);
            _tokenFailures.Remove(normalized);
            _authFailures.Remove(normalized);
        }

        if (removed)
        {
            _log.Info($"已手动解封：{normalized}");
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return removed;
    }

    /// <summary>Records a failed token check; returns true when the ban list changed.</summary>
    private bool RegisterFailure(string address, string reason)
    {
        // Caller holds _gate.
        var failures = _tokenFailures.TryGetValue(address, out var existing) ? existing + 1 : 1;
        _tokenFailures[address] = failures;
        _log.Warn($"token 校验失败 {failures}/{_options.MaxTokenFailures}：{address}（{reason}）");

        if (failures >= _options.MaxTokenFailures && _bannedAddresses.Add(address))
        {
            _log.Error($"IP {address} 连续 {failures} 次 token 校验失败，已永久封禁");
            return true;
        }

        return false;
    }

    private bool IsRateLimited(string address)
    {
        // Caller holds _gate.
        var now = DateTimeOffset.UtcNow;

        if (!_attempts.TryGetValue(address, out var stamps))
        {
            stamps = new List<DateTimeOffset>();
            _attempts[address] = stamps;
        }

        stamps.RemoveAll(t => now - t > _options.RateLimitWindow);
        stamps.Add(now);

        if (_attempts.Count > 256)
        {
            foreach (var stale in _attempts.Where(kv => kv.Value.Count == 0).Select(kv => kv.Key).ToArray())
            {
                _attempts.Remove(stale);
            }
        }

        return stamps.Count > _options.MaxConnectionAttemptsPerWindow;
    }

    private static string Normalize(string? address)
    {
        if (string.IsNullOrWhiteSpace(address))
        {
            return "unknown";
        }

        // IPv4-mapped IPv6 (::ffff:192.168.1.5) is the same peer as 192.168.1.5.
        var value = address.Trim();
        const string mappedPrefix = "::ffff:";
        if (value.StartsWith(mappedPrefix, StringComparison.OrdinalIgnoreCase))
        {
            value = value[mappedPrefix.Length..];
        }

        return value;
    }

    /// <summary>Length-independent comparison; the token is not a secret we can leak via timing.</summary>
    private static bool FixedTimeEquals(string a, string b) =>
        System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.UTF8.GetBytes(a),
            System.Text.Encoding.UTF8.GetBytes(b));
}
