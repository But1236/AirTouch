using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using AirTouch.Core;

namespace AirTouch.SmokeTest;

public sealed record IncomingFrame(
    WebSocketMessageType Kind,
    string? Text,
    byte[]? Binary,
    int? CloseCode,
    string? CloseStatus)
{
    public bool IsClose => Kind == WebSocketMessageType.Close;

    public string? ErrorCode => Text is null ? null : TryReadType(Text, "error") ? TestJson.GetString(Text, "code") : null;

    public string? MessageType => Text is null ? null : TryReadType(Text, null) ? TestJson.GetString(Text, "type") : null;

    private static bool TryReadType(string json, string? expected)
    {
        try
        {
            using var document = System.Text.Json.JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != System.Text.Json.JsonValueKind.Object)
            {
                return false;
            }

            if (!document.RootElement.TryGetProperty("type", out var typeElement))
            {
                return false;
            }

            var type = typeElement.GetString();
            return expected is null || string.Equals(type, expected, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
    }
}

/// <summary>
/// A real TLS WebSocket client used to exercise the server exactly like the phone would.
/// </summary>
/// <remarks>
/// The certificate callback validates the server certificate's SHA-256 fingerprint instead of
/// accepting anything, which also proves that the self-signed certificate is well formed and
/// reachable through SslStream.
/// </remarks>
public sealed class WssTestClient : IAsyncDisposable
{
    private readonly ClientWebSocket _socket = new();
    private readonly BlockingCollection<IncomingFrame> _frames = new();
    private readonly CancellationTokenSource _cts = new();
    private Task? _pump;
    private string? _fingerprintError;

    public string? CloseStatus { get; private set; }

    public int? CloseCode { get; private set; }

    public string? FingerprintError => _fingerprintError;

    public static WssTestClient Create(string? expectedFingerprint)
    {
        var client = new WssTestClient();

        client._socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
        {
            if (certificate is null)
            {
                client._fingerprintError = "服务器未提供证书";
                return false;
            }

            if (expectedFingerprint is null)
            {
                return true;
            }

            using var asX509 = new X509Certificate2(certificate);
            var actual = CertificateManager.FingerprintOf(asX509);
            if (string.Equals(actual, expectedFingerprint, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }

            client._fingerprintError = $"证书指纹不匹配：期望 {expectedFingerprint}，实际 {actual}";
            return false;
        };

        // Exercise the server's OnPing -> SendPong path too.
        client._socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(5);
        return client;
    }

    public async Task ConnectAsync(string url, TimeSpan timeout)
    {
        using var timeoutCts = new CancellationTokenSource(timeout);
        try
        {
            await _socket.ConnectAsync(new Uri(url), timeoutCts.Token);
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException)
        {
            throw new InvalidOperationException(
                $"连接 {url} 失败：{ex.Message}{(_fingerprintError is null ? string.Empty : "（" + _fingerprintError + "）")}",
                ex);
        }

        _pump = Task.Run(PumpAsync);
    }

    public Task SendTextAsync(string json) =>
        _socket.SendAsync(Encoding.UTF8.GetBytes(json), WebSocketMessageType.Text, true, CancellationToken.None);

    public Task SendBinaryAsync(byte[] payload) =>
        _socket.SendAsync(payload, WebSocketMessageType.Binary, true, CancellationToken.None);

    /// <summary>Reads the next frame, or null when nothing arrived inside the timeout.</summary>
    public IncomingFrame? Receive(TimeSpan timeout) =>
        _frames.TryTake(out var frame, timeout) ? frame : null;

    /// <summary>
    /// Collects frames until an <c>error</c> text frame or a close arrives, so tests can assert on the
    /// server's rejection reason.
    /// </summary>
    public (string? ErrorCode, string? ErrorMessage, int? CloseCode) ReceiveRejection(TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        string? errorCode = null;
        string? errorMessage = null;
        int? closeCode = null;

        while (DateTime.UtcNow < deadline)
        {
            var remaining = deadline - DateTime.UtcNow;
            if (remaining <= TimeSpan.Zero)
            {
                break;
            }

            var frame = Receive(remaining);
            if (frame is null)
            {
                break;
            }

            if (frame.IsClose)
            {
                closeCode = frame.CloseCode;
                break;
            }

            if (frame.ErrorCode is not null)
            {
                errorCode = frame.ErrorCode;
                errorMessage = TestJson.GetString(frame.Text!, "message");
            }
        }

        return (errorCode, errorMessage, closeCode);
    }

    public async Task CloseAsync()
    {
        try
        {
            if (_socket.State == WebSocketState.Open)
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", cts.Token);
            }
        }
        catch (Exception)
        {
            // The server may already have closed the socket.
        }
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            _cts.Cancel();
            _socket.Dispose();

            if (_pump is not null)
            {
                await Task.WhenAny(_pump, Task.Delay(500));
            }
        }
        catch (Exception)
        {
            // ignored
        }
        finally
        {
            _frames.Dispose();
            _cts.Dispose();
        }
    }

    private async Task PumpAsync()
    {
        var buffer = new byte[256 * 1024];
        var accumulated = new MemoryStream();

        try
        {
            while (!_cts.IsCancellationRequested)
            {
                accumulated.SetLength(0);

                WebSocketReceiveResult result;
                do
                {
                    result = await _socket.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        CloseCode = (int?)result.CloseStatus;
                        CloseStatus = result.CloseStatusDescription;
                        _frames.Add(new IncomingFrame(
                            WebSocketMessageType.Close,
                            null,
                            null,
                            (int?)result.CloseStatus,
                            result.CloseStatusDescription));
                        return;
                    }

                    accumulated.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                var payload = accumulated.ToArray();

                _frames.Add(new IncomingFrame(
                    result.MessageType,
                    result.MessageType == WebSocketMessageType.Text ? Encoding.UTF8.GetString(payload) : null,
                    result.MessageType == WebSocketMessageType.Binary ? payload : null,
                    null,
                    null));
            }
        }
        catch (OperationCanceledException)
        {
            // normal shutdown
        }
        catch (Exception ex)
        {
            _frames.Add(new IncomingFrame(WebSocketMessageType.Close, null, null, null, ex.Message));
        }
    }
}
