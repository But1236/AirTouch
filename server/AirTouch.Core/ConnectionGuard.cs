namespace AirTouch.Core;

/// <summary>Outcome of the pre-handshake authorization performed for every new connection.</summary>
public enum AuthResult
{
    Allow,
    BadPath,
    Banned,
    RateLimited,
    IpLocked,
    Busy,
    TokenInvalid,
    TokenExpired,
}

public sealed record AuthOutcome(AuthResult Result, string Message)
{
    public bool IsAllowed => Result == AuthResult.Allow;

    /// <summary>WebSocket close code sent back to the rejected peer.</summary>
    public int CloseCode => Result switch
    {
        AuthResult.Busy => 1013,          // "try again later"
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
        AuthResult.TokenInvalid => "token_invalid",
        AuthResult.TokenExpired => "token_expired",
        _ => "ok",
    };
}

/// <summary>
/// Owns every connection-level security decision: the one-time pairing token, IP binding, the
/// single-session rule, brute-force lockout and a coarse per-IP rate limit.
/// </summary>
/// <remarks>
/// Deliberately free of networking types so the policy can be unit tested and fuzzed directly.
/// All members are safe to call from Fleck's worker threads.
/// </remarks>
public sealed class ConnectionGuard
{
    public const string WebSocketPath = "/ws";

    private readonly object _gate = new();
    private readonly AirTouchOptions _options;
    private readonly IAirTouchLog _log;
    private readonly Dictionary<string, int> _tokenFailures = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _bannedAddresses = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, List<DateTimeOffset>> _attempts = new(StringComparer.OrdinalIgnoreCase);

    private string _token;
    private bool _tokenConsumed;

    /// <summary>
    /// The last token that was actually consumed by a successful pairing.
    /// </summary>
    /// <remarks>
    /// Disconnecting rotates the token, so without remembering the consumed value a phone that drops
    /// off the Wi-Fi and reconnects with its (now stale) token would look exactly like an attacker
    /// guessing: it would be counted as a failed attempt and a couple of drops would permanently ban
    /// the legitimate device. Keeping the value lets the replay be answered with
    /// <see cref="AuthResult.TokenExpired"/> without touching the brute-force budget, which is what
    /// docs/PROTOCOL.md §4 promises. Guessing is unaffected: only this exact value is exempt.
    /// </remarks>
    private string? _lastConsumedToken;
    private string? _boundAddress;
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

    /// <summary>IP permanently allowed to talk to this server, or null while unpaired.</summary>
    public string? BoundAddress
    {
        get
        {
            lock (_gate)
            {
                return _boundAddress;
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
                return _activeAddress is not null;
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

    public static string NewToken() => Guid.NewGuid().ToString("D");

    /// <summary>
    /// Evaluates a new connection attempt. Checks run in a fixed order so the client always gets the
    /// most specific reason: path, ban, rate limit, IP binding, single-session, then the token.
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

            if (_boundAddress is not null &&
                !string.Equals(_boundAddress, normalized, StringComparison.OrdinalIgnoreCase))
            {
                _log.Warn($"拒绝未绑定 IP：{normalized}（当前绑定 {_boundAddress}）");
                return new AuthOutcome(AuthResult.IpLocked, $"本机已绑定 {_boundAddress}，拒绝其他设备接入");
            }

            if (_activeAddress is not null)
            {
                _log.Warn($"拒绝重复连接：{normalized}（已有活动连接）");
                return new AuthOutcome(AuthResult.Busy, "已有设备处于连接状态");
            }

            // The token arrives in the query string (the QR code format) or, as a fallback, in an
            // X-Auth-Token header for clients that cannot keep a query string.
            var presented = ConnectionRequest.GetToken(query, headers);

            if (string.IsNullOrEmpty(presented))
            {
                stateChanged = RegisterFailure(normalized, "缺少 token");
                outcome = new AuthOutcome(AuthResult.TokenInvalid, "缺少 token");
            }
            else if (IsConsumedToken(presented))
            {
                // A replay of the already-consumed token is not a brute-force attempt: it is the
                // normal "app reconnected after pairing" case, so it must not ban the phone. This
                // stays true after the token has been rotated on disconnect, which is the case that
                // actually happens when the phone loses Wi-Fi.
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
                outcome = new AuthOutcome(AuthResult.Allow, "ok");
            }
        }

        if (stateChanged)
        {
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return outcome;
    }

    /// <summary>
    /// True when <paramref name="presented"/> is the token that a previous pairing already consumed —
    /// either the current one (still active) or, after a disconnect rotated it, the remembered value.
    /// </summary>
    private bool IsConsumedToken(string presented)
    {
        if (_tokenConsumed && FixedTimeEquals(presented, _token))
        {
            return true;
        }

        return _lastConsumedToken is not null && FixedTimeEquals(presented, _lastConsumedToken);
    }

    /// <summary>Consumes the one-time token and binds the IP. Called once authorization succeeded.</summary>
    public void MarkConnected(string clientAddress)
    {
        var normalized = Normalize(clientAddress);

        lock (_gate)
        {
            _tokenConsumed = true;
            _lastConsumedToken = _token;
            _tokenFailures.Remove(normalized);
            _boundAddress ??= normalized;
            _activeAddress = normalized;
            _log.Info($"设备已连接：{normalized}；token 已销毁，绑定 IP = {_boundAddress}");
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>
    /// Clears the active session and issues a fresh token. The IP binding intentionally survives so
    /// the same phone can reconnect after a drop while every other device stays locked out.
    /// </summary>
    public void MarkDisconnected(string clientAddress)
    {
        var normalized = Normalize(clientAddress);
        var rotated = false;

        lock (_gate)
        {
            if (_activeAddress is not null &&
                string.Equals(_activeAddress, normalized, StringComparison.OrdinalIgnoreCase))
            {
                _activeAddress = null;
                _token = NewToken();
                _tokenConsumed = false;
                rotated = true;
                _log.Info($"设备已断开：{normalized}；已生成新 token，二维码需刷新");
            }
        }

        if (rotated)
        {
            Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>Issues a new token without touching the IP binding (used by the "刷新二维码" button).</summary>
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

    /// <summary>Full reset: unbind the IP, lift every ban, drop the failure counters, new token.</summary>
    public void Reset(string reason)
    {
        lock (_gate)
        {
            _token = NewToken();
            _tokenConsumed = false;
            _lastConsumedToken = null;
            _boundAddress = null;
            _activeAddress = null;
            _bannedAddresses.Clear();
            _tokenFailures.Clear();
            _attempts.Clear();
            _log.Info($"安全状态已重置（{reason}）；已解除 IP 绑定与封禁，并生成新 token");
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

    private static string Normalize(string address)
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
