using System.Text.Json;

namespace AirTouch.Core;

public enum MessageKind
{
    Unknown = 0,
    Move,
    Click,
    Scroll,
    Reset,
    Key,
    Text,
    FileHeader,
    FileEnd,
    Hello,
    Ping,
    Pong,
    AuthReq,
}

/// <summary>A decoded control frame. Unknown frames decode to <see cref="MessageKind.Unknown"/>.</summary>
public sealed class ControlMessage
{
    public MessageKind Kind { get; init; } = MessageKind.Unknown;

    public string RawType { get; init; } = string.Empty;

    public int Dx { get; init; }

    public int Dy { get; init; }

    public int Amount { get; init; }

    public MouseButton Button { get; init; } = MouseButton.Left;

    // ---- key (docs/PROTOCOL.md §2.6) ----

    /// <summary>Win32 virtual-key code of a <c>key</c> frame, clamped to <see cref="Protocol.MaxVirtualKey"/>.</summary>
    public int VirtualKey { get; init; }

    /// <summary><c>true</c> = KEY_DOWN, <c>false</c> = KEY_UP.</summary>
    public bool KeyDown { get; init; }

    /// <summary>
    /// Explicit <c>KEYEVENTF_EXTENDEDKEY</c> override; <c>null</c> means "let the controller decide from
    /// the virtual-key code". Only the frame author can know that the numeric keypad's Enter is meant.
    /// </summary>
    public bool? KeyExtended { get; init; }

    // ---- text (docs/PROTOCOL.md §2.7) ----

    /// <summary>Text of a <c>text</c> frame, truncated to <see cref="Protocol.MaxTextLength"/> code units.</summary>
    public string Text { get; init; } = string.Empty;

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

    /// <summary>Highest Win32 virtual-key code; anything larger is not a virtual key and is clamped away.</summary>
    public const int MaxVirtualKey = 255;

    /// <summary>
    /// Longest text a <c>text</c> frame may inject, in UTF-16 code units; the rest is truncated away
    /// (docs/PROTOCOL.md §2.7). A single frame is a burst of input, not a bulk transfer.
    /// </summary>
    public const int MaxTextLength = 256;

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

            if (type.Equals("key", StringComparison.OrdinalIgnoreCase))
            {
                return new ControlMessage
                {
                    Kind = MessageKind.Key,
                    RawType = type,
                    VirtualKey = Math.Clamp(GetInt(root, "vk"), 0, MaxVirtualKey),
                    // 缺省为「抬起」而不是「按下」：抬起一个没按下的键是无害的空操作，而缺省按下
                    // 会在电脑上留下一个永远不松开的按键（每个后续输入都变成快捷键）。
                    KeyDown = GetBool(root, "down", fallback: false),
                    KeyExtended = GetOptionalBool(root, "ext"),
                };
            }

            if (type.Equals("text", StringComparison.OrdinalIgnoreCase))
            {
                // s 必须是真字符串：缺字段、null 或给成数字都不算「要输入的文本」。这里不退回空串而
                // 是整帧忽略，是因为「手机发错了一个字段」与「手机想输入空文本」是两回事，静默按后者
                // 处理只会让客户端的一个拼写错误看起来像注入成功。
                if (!root.TryGetProperty("s", out var textElement) || textElement.ValueKind != JsonValueKind.String)
                {
                    return null;
                }

                return new ControlMessage
                {
                    Kind = MessageKind.Text,
                    RawType = type,
                    Text = ClampText(textElement.GetString()),
                };
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

            return new ControlMessage { Kind = MessageKind.Unknown, RawType = type };
        }
    }

    /// <summary>
    /// Truncates a <c>text</c> payload to <see cref="MaxTextLength"/> UTF-16 code units, never leaving a
    /// high surrogate without its low half (docs/PROTOCOL.md §2.7).
    /// </summary>
    private static string ClampText(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }

        if (text.Length <= MaxTextLength)
        {
            return text;
        }

        var length = MaxTextLength;

        // 第 256 个码元正好是高代理项时（emoji 这类补充平面字符的第一个码元）再多丢一个：它的低
        // 代理项会被切掉，留下的是一个无效的孤立高代理项，注入到前台窗口只会变成一个乱码方块。
        if (char.IsHighSurrogate(text[length - 1]))
        {
            length--;
        }

        return text[..length];
    }

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
        string sid,
        int displayHz,
        string? keyId,
        string authMode) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "welcome",
            ["protocolVersion"] = Version,
            ["serverVersion"] = serverVersion,
            ["serverIp"] = serverAddress,
            ["sid"] = sid,
            ["displayHz"] = displayHz,
            ["keyId"] = keyId,
            ["authMode"] = authMode,
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

    /// <summary>
    /// Reads a boolean field. JSON <c>true</c>/<c>false</c>, the strings <c>"true"</c>/<c>"false"</c> and
    /// numbers (0 = false, anything else = true) are all accepted; anything else yields
    /// <paramref name="fallback"/> (protocol §2.3: unparsable values never reject a frame).
    /// </summary>
    private static bool GetBool(JsonElement root, string name, bool fallback)
    {
        if (!root.TryGetProperty(name, out var element))
        {
            return fallback;
        }

        return element.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => GetInt(root, name) != 0,
            JsonValueKind.String => ParseBoolText(element.GetString()),
            _ => fallback,
        };
    }

    /// <summary>Like <see cref="GetBool"/> but keeps "field absent" distinguishable from an explicit false.</summary>
    private static bool? GetOptionalBool(JsonElement root, string name) =>
        root.TryGetProperty(name, out _) ? GetBool(root, name, fallback: false) : null;

    private static bool ParseBoolText(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return false;
        }

        if (text.Equals("true", StringComparison.OrdinalIgnoreCase) || text == "1")
        {
            return true;
        }

        return !text.Equals("false", StringComparison.OrdinalIgnoreCase)
            && text != "0"
            && double.TryParse(text, out var number)
            && number != 0;
    }
}
