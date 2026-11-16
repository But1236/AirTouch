using System.Text.Json;

namespace AirTouch.Core;

public enum MessageKind
{
    Unknown = 0,
    Move,
    Click,
    Scroll,
    Reset,
    FileHeader,
    FileEnd,
    Hello,
    Ping,
    Pong,
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

    public string? FileName { get; init; }

    public long FileSize { get; init; }

    public string? FileId { get; init; }

    public string? DeviceName { get; init; }

    public string? AppVersion { get; init; }
}

/// <summary>
/// Wire format helpers. Field names here are frozen by docs/PROTOCOL.md and must stay in sync with
/// the Android client.
/// </summary>
public static class Protocol
{
    public const string WebSocketPath = "/ws";

    /// <summary>Bounds from the protocol document; values outside are clamped, not rejected.</summary>
    public const int MaxMoveStep = 4096;

    public const int MaxScrollAmount = 120;

    /// <summary>
    /// Builds the pairing URL that goes into the QR code / <c>welcome</c> frame. <paramref name="useTls"/>
    /// is always true in production; the plaintext form exists only for local diagnostics.
    /// </summary>
    public static string BuildUrl(string address, int port, string token, bool useTls = true) =>
        $"{(useTls ? "wss" : "ws")}://{address}:{port}{WebSocketPath}?token={token}";

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

            return new ControlMessage { Kind = MessageKind.Unknown, RawType = type };
        }
    }

    public static string BuildWelcome(string serverVersion, string serverAddress, int port, string? boundAddress, long maxFileSize, int chunkSize) =>
        Serialize(new Dictionary<string, object?>
        {
            ["type"] = "welcome",
            ["serverVersion"] = serverVersion,
            ["serverIp"] = serverAddress,
            ["port"] = port,
            ["boundIp"] = boundAddress,
            ["maxFileSize"] = maxFileSize,
            ["chunkSize"] = chunkSize,
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
}
