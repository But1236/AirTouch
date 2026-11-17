using System.Text.Json;

namespace AirTouch.Core;

public enum MessageKind
{
    Unknown = 0,
    Move,
    MoveTo,
    Click,
    Scroll,
    Reset,
    FileHeader,
    FileEnd,
    Hello,
    Ping,
    Pong,
    AuthReq,
    DiagBegin,
    DiagReport,
}

/// <summary>The client's own measurement of one communication test run (docs/PROTOCOL.md §2.3).</summary>
public sealed record ClientDiagReport(
    string TestKind,
    string Mode,
    int Planned,
    int Sent,
    int FirstSeq,
    int LastSeq,
    long ElapsedMs,
    int TargetHz,
    int CircleMs,
    int MaxSendGapMs);

/// <summary>A decoded control frame. Unknown frames decode to <see cref="MessageKind.Unknown"/>.</summary>
public sealed class ControlMessage
{
    public MessageKind Kind { get; init; } = MessageKind.Unknown;

    public string RawType { get; init; } = string.Empty;

    public int Dx { get; init; }

    public int Dy { get; init; }

    /// <summary>Absolute target of a <c>moveto</c> frame (primary screen pixels).</summary>
    public int X { get; init; }

    public int Y { get; init; }

    public int Amount { get; init; }

    /// <summary>Optional diagnostic sequence number carried by <c>move</c>/<c>moveto</c> frames.</summary>
    public int? Seq { get; init; }

    public MouseButton Button { get; init; } = MouseButton.Left;

    public string? FileName { get; init; }

    public long FileSize { get; init; }

    public string? FileId { get; init; }

    public string? DeviceName { get; init; }

    public string? AppVersion { get; init; }

    // ---- auth_req (docs/PROTOCOL.md §1.2) ----

    public int AuthVersion { get; init; }

    public string? Token { get; init; }

    public string? KeyId { get; init; }

    public string? PublicKey { get; init; }

    public string? Signature { get; init; }

    public long Timestamp { get; init; }

    // ---- diag_begin / diag_report ----

    public ClientDiagReport? Diag { get; init; }
}

/// <summary>
/// Wire format helpers. Field names here are frozen by docs/PROTOCOL.md and must stay in sync with
/// the Android client.
/// </summary>
public static class Protocol
{
    public const string WebSocketPath = "/ws";

    /// <summary>Protocol revision implemented by this build (docs/PROTOCOL.md).</summary>
    public const int Version = 2;

    /// <summary>Bounds from the protocol document; values outside are clamped, not rejected.</summary>
    public const int MaxMoveStep = 4096;

    public const int MaxScrollAmount = 120;

    /// <summary>Hard cap on the absolute target of a <c>moveto</c>; anything larger is clamped.</summary>
    public const int MaxAbsoluteCoordinate = 32767;

    /// <summary>
    /// Builds the pairing URL that goes into the QR code. <paramref name="sid"/> and
    /// <paramref name="pin"/> let a freshly scanned phone verify the certificate <em>before</em> the
    /// first byte of application data (no trust-on-first-use window), and let it key its pin to the
    /// server identity instead of the IP address. <paramref name="useTls"/> is always true in production.
    /// </summary>
    public static string BuildUrl(
        string address,
        int port,
        string? token = null,
        string? sid = null,
        string? pin = null,
        bool useTls = true)
    {
        var scheme = useTls ? "wss" : "ws";
        var url = $"{scheme}://{address}:{port}{WebSocketPath}?token={token}";
        if (!string.IsNullOrEmpty(sid))
        {
            url += $"&sid={sid}";
        }

        if (!string.IsNullOrEmpty(pin))
        {
            url += $"&fp={pin}";
        }

        return url;
    }

    /// <summary>Parses a client text frame. Returns null when the payload is not a JSON object.</summary>
    public static ControlMessage? Parse(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return null;
        }

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json);
        }
        catch (JsonException)
        {
            return null;
        }

        using (document)
        {
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return null;
            }

            var type = GetString(root, "type") ?? string.Empty;

            if (type.Equals("move", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.Move,
                    RawType = type,
                    Dx = Math.Clamp(GetInt(root, "dx"), -MaxMoveStep, MaxMoveStep),
                    Dy = Math.Clamp(GetInt(root, "dy"), -MaxMoveStep, MaxMoveStep),
                    Seq = GetOptionalInt(root, "seq"),
                };
            }

            if (type.Equals("moveto", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.MoveTo,
                    RawType = type,
                    X = Math.Clamp(GetInt(root, "x"), -MaxAbsoluteCoordinate, MaxAbsoluteCoordinate),
                    Y = Math.Clamp(GetInt(root, "y"), -MaxAbsoluteCoordinate, MaxAbsoluteCoordinate),
                    Seq = GetOptionalInt(root, "seq"),
                };
            }

            if (type.Equals("click", StringComparison.OrdinalIgnoreCase))
            {
                var button = GetString(root, "button");
                return new ControlMessage
                {
                    Kind = MessageKind.Click,
                    RawType = type,
                    Button = string.Equals(button, "right", StringComparison.OrdinalIgnoreCase)
                        ? MouseButton.Right
                        : MouseButton.Left,
                };
            }

            if (type.Equals("scroll", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.Scroll,
                    RawType = type,
                    Amount = Math.Clamp(GetInt(root, "amount"), -MaxScrollAmount, MaxScrollAmount),
                };
            }

            if (type.Equals("reset", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage { Kind = MessageKind.Reset, RawType = type };
            }

            if (type.Equals("file_header", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.FileHeader,
                    RawType = type,
                    FileName = GetString(root, "fileName"),
                    FileSize = GetLong(root, "fileSize"),
                    FileId = GetString(root, "fileId"),
                };
            }

            if (type.Equals("file_end", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.FileEnd,
                    RawType = type,
                    FileId = GetString(root, "fileId"),
                };
            }

            if (type.Equals("hello", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.Hello,
                    RawType = type,
                    DeviceName = GetString(root, "deviceName"),
                    AppVersion = GetString(root, "appVersion"),
                };
            }

            if (type.Equals("ping", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage { Kind = MessageKind.Ping, RawType = type };
            }

            if (type.Equals("pong", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage { Kind = MessageKind.Pong, RawType = type };
            }

            if (type.Equals("auth_req", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.AuthReq,
                    RawType = type,
                    AuthVersion = GetInt(root, "auth_v"),
                    Token = GetString(root, "token"),
                    KeyId = GetString(root, "keyId"),
                    PublicKey = GetString(root, "pubKey"),
                    Signature = GetString(root, "sign"),
                    Timestamp = GetLong(root, "ts"),
                    DeviceName = GetString(root, "deviceName"),
                    AppVersion = GetString(root, "appVersion"),
                };
            }

            if (type.Equals("diag_begin", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.DiagBegin,
                    RawType = type,
                    Diag = ReadDiag(root),
                };
            }

            if (type.Equals("diag_report", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.DiagReport,
                    RawType = type,
                    Diag = ReadDiag(root),
                };
            }

            return new ControlMessage { Kind = MessageKind.Unknown, RawType = type };
        }
    }

    private static ClientDiagReport ReadDiag(JsonElement root) =>
        new(
            TestKind: GetString(root, "testKind") ?? "circle",
            Mode: GetString(root, "mode") ?? "abs",
            Planned: GetInt(root, "planned"),
            Sent: GetInt(root, "sent"),
            FirstSeq: GetInt(root, "firstSeq"),
            LastSeq: GetInt(root, "lastSeq"),
            ElapsedMs: GetLong(root, "elapsedMs"),
            TargetHz: GetInt(root, "targetHz"),
            CircleMs: GetInt(root, "circleMs"),
            MaxSendGapMs: GetInt(root, "maxSendGapMs"));

    /// <summary>Server → client: the single-use nonce the client must sign (docs/PROTOCOL.md §1.2).</summary>
    public static string BuildAuthChallenge(string sid, string nonce, long serverTime, int expiresInSeconds, string mode) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "auth_challenge",
            ["auth_v"] = AuthCrypto.AuthVersion,
            ["sid"] = sid,
            ["nonce"] = nonce,
            ["serverTime"] = serverTime,
            ["expiresIn"] = expiresInSeconds,
            ["mode"] = mode,
        });

    public static string BuildWelcome(
        string serverVersion,
        string serverAddress,
        int port,
        string sid,
        long maxFileSize,
        int chunkSize,
        int screenWidth,
        int screenHeight,
        int displayHz,
        string? deviceName,
        string? keyId,
        string authMode,
        int trustedDeviceCount) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "welcome",
            ["protocolVersion"] = Version,
            ["serverVersion"] = serverVersion,
            ["serverIp"] = serverAddress,
            ["port"] = port,
            ["sid"] = sid,
            ["maxFileSize"] = maxFileSize,
            ["chunkSize"] = chunkSize,
            ["screenWidth"] = screenWidth,
            ["screenHeight"] = screenHeight,
            ["displayHz"] = displayHz,
            ["deviceName"] = deviceName,
            ["keyId"] = keyId,
            ["authMode"] = authMode,
            ["trustedCount"] = trustedDeviceCount,
        });

    /// <summary>Server → client: what the PC measured during a communication test.</summary>
    public static string BuildDiagReport(
        string testKind,
        string mode,
        int planned,
        int received,
        int firstSeq,
        int lastSeq,
        int lost,
        int duplicated,
        int outOfOrder,
        int maxGapMs,
        int meanGapMs,
        long durationMs,
        int sendInputFailures,
        int startX,
        int startY,
        int endX,
        int endY,
        int screenWidth,
        int screenHeight,
        int cursorUpdates,
        int cursorPollHz,
        int cursorMaxGapMs,
        int displayHz) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "diag_report",
            ["testKind"] = testKind,
            ["mode"] = mode,
            ["planned"] = planned,
            ["received"] = received,
            ["firstSeq"] = firstSeq,
            ["lastSeq"] = lastSeq,
            ["lost"] = lost,
            ["duplicated"] = duplicated,
            ["outOfOrder"] = outOfOrder,
            ["maxGapMs"] = maxGapMs,
            ["meanGapMs"] = meanGapMs,
            ["durationMs"] = durationMs,
            ["sendInputFailures"] = sendInputFailures,
            ["startX"] = startX,
            ["startY"] = startY,
            ["endX"] = endX,
            ["endY"] = endY,
            ["screenWidth"] = screenWidth,
            ["screenHeight"] = screenHeight,
            // 光标在测试期间"真正动了几次"（GetCursorPos 采样），以及屏幕刷新率。
            // targetHz 与 received 都是"发送/到达"侧的数字，只有这两个是"看得见"侧的数字。
            ["cursorUpdates"] = cursorUpdates,
            ["cursorPollHz"] = cursorPollHz,
            ["cursorMaxGapMs"] = cursorMaxGapMs,
            ["displayHz"] = displayHz,
        });

    public static string BuildFileHeader(string fileName, long fileSize, string fileId) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "file_header",
            ["fileName"] = fileName,
            ["fileSize"] = fileSize,
            ["fileId"] = fileId,
        });

    public static string BuildFileEnd(string fileId) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "file_end",
            ["fileId"] = fileId,
        });

    public static string BuildFileAck(string fileName, bool ok, long bytes, string? savedPath, string message) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "file_ack",
            ["fileName"] = fileName,
            ["status"] = ok ? "ok" : "error",
            ["bytes"] = bytes,
            ["savedPath"] = savedPath,
            ["message"] = message,
        });

    public static string BuildError(string code, string message) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "error",
            ["code"] = code,
            ["message"] = message,
        });

    public static string BuildPong() =>
        Serialize(new Dictionary<string, object?> { ["type"] = "pong" });

    /// <summary>Serialises a message object, dropping null values so optional fields stay optional.</summary>
    private static string Serialize(Dictionary<string, object?> fields)
    {
        var payload = new Dictionary<string, object?>(fields.Count);
        foreach (var pair in fields)
        {
            if (pair.Value is not null)
            {
                payload[pair.Key] = pair.Value;
            }
        }

        return JsonSerializer.Serialize(payload);
    }

    private static string? GetString(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var element))
        {
            return null;
        }

        return element.ValueKind switch
        {
            JsonValueKind.String => element.GetString(),
            JsonValueKind.Number => element.GetRawText(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => null,
        };
    }

    /// <summary>Reads an integer field, tolerating numbers, numeric strings and floats. Unparsable = 0.</summary>
    private static long GetLong(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var element))
        {
            return 0;
        }

        switch (element.ValueKind)
        {
            case JsonValueKind.Number:
                if (element.TryGetInt64(out var number))
                {
                    return number;
                }

                return element.TryGetDouble(out var asDouble) ? (long)asDouble : 0;

            case JsonValueKind.String:
                var text = element.GetString();
                if (long.TryParse(text, out var parsed))
                {
                    return parsed;
                }

                return double.TryParse(text, out var parsedDouble) ? (long)parsedDouble : 0;

            default:
                return 0;
        }
    }

    private static int GetInt(JsonElement root, string name)
    {
        var value = GetLong(root, name);
        return value switch
        {
            > int.MaxValue => int.MaxValue,
            < int.MinValue => int.MinValue,
            _ => (int)value,
        };
    }

    /// <summary>Like <see cref="GetInt"/> but keeps "field absent" distinguishable from an explicit 0.</summary>
    private static int? GetOptionalInt(JsonElement root, string name) =>
        root.TryGetProperty(name, out _) ? GetInt(root, name) : null;
}
