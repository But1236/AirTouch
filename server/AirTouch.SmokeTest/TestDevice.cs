using System.Security.Cryptography;
using System.Text.Json;
using AirTouch.Core;

namespace AirTouch.SmokeTest;

/// <summary>
/// A stand-in for the phone: an ECDSA P-256 key pair plus the exact frames the Android client sends
/// (docs/PROTOCOL.md §1.2). Keeping this in the self-test means the whole pairing and resume flow is
/// exercised through the real wire format, not by calling the server's internals.
/// </summary>
public sealed class TestDevice : IDisposable
{
    private readonly ECDsa _key;

    public TestDevice(string name = "自检设备")
    {
        Name = name;
        _key = ECDsa.Create(ECCurve.NamedCurves.nistP256);

        var spki = _key.ExportSubjectPublicKeyInfo();
        PublicKey = AuthCrypto.Base64Url(spki);
        KeyId = AuthCrypto.KeyIdOfPublicKey(spki);
    }

    public string Name { get; set; }

    /// <summary>base64url of the X.509 SubjectPublicKeyInfo (identical to Android's getEncoded()).</summary>
    public string PublicKey { get; }

    public string KeyId { get; }

    /// <summary>
    /// Builds the <c>auth_req</c> frame. <paramref name="token"/> is only sent while pairing;
    /// <paramref name="signatureOverride"/> and <paramref name="timestampOverride"/> exist so the
    /// negative tests can send a tampered or stale proof.
    /// </summary>
    public string BuildAuthRequest(
        string sid,
        string nonce,
        string? token = null,
        long? timestampOverride = null,
        string? signatureOverride = null,
        string? publicKeyOverride = null,
        string? keyIdOverride = null)
    {
        var ts = timestampOverride ?? AuthCrypto.UnixNow();
        var keyId = keyIdOverride ?? KeyId;
        var canonical = AuthCrypto.Canonical(sid, nonce, keyId, ts);
        var signature = signatureOverride ?? AuthCrypto.Base64Url(AuthCrypto.SignForClient(_key, canonical));

        var payload = new Dictionary<string, object?>
        {
            ["type"] = "auth_req",
            ["auth_v"] = AuthCrypto.AuthVersion,
            ["keyId"] = keyId,
            ["sign"] = signature,
            ["ts"] = ts,
            ["deviceName"] = Name,
            ["appVersion"] = "9.9.9-test",
        };

        if (token is not null)
        {
            payload["token"] = token;
        }

        if (publicKeyOverride is not null || token is not null)
        {
            payload["pubKey"] = publicKeyOverride ?? PublicKey;
        }

        return JsonSerializer.Serialize(payload);
    }

    /// <summary>Signs a canonical string with a deliberately non-DER encoding (interop negative test).</summary>
    public string SignP1363(string sid, string nonce, string keyId, long ts)
    {
        var canonical = AuthCrypto.Canonical(sid, nonce, keyId, ts);
        var raw = _key.SignData(
            System.Text.Encoding.UTF8.GetBytes(canonical),
            HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);

        return AuthCrypto.Base64Url(raw);
    }

    public void Dispose() => _key.Dispose();

    /// <summary>The raw key, for the encoding tests in the self-test.</summary>
    public ECDsa GetKey() => _key;
}

/// <summary>The client half of the auth exchange, driven over a real socket.</summary>
public static class TestAuth
{
    public sealed record Challenge(string Sid, string Nonce, long ServerTime, int ExpiresIn, string Mode);

    /// <summary>Waits for the server's <c>auth_challenge</c>.</summary>
    public static Challenge? ReceiveChallenge(WssTestClient client, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            var frame = client.Receive(deadline - DateTime.UtcNow);
            if (frame is null)
            {
                return null;
            }

            if (frame.MessageType != "auth_challenge" || frame.Text is null)
            {
                continue;
            }

            using var document = JsonDocument.Parse(frame.Text);
            var root = document.RootElement;
            return new Challenge(
                GetString(root, "sid") ?? string.Empty,
                GetString(root, "nonce") ?? string.Empty,
                GetLong(root, "serverTime"),
                (int)GetLong(root, "expiresIn"),
                GetString(root, "mode") ?? string.Empty);
        }

        return null;
    }

    /// <summary>Pairs (with a token) or resumes (without) and returns the <c>welcome</c> frame.</summary>
    public static async Task<IncomingFrame?> AuthenticateAsync(
        WssTestClient client,
        TestDevice device,
        string? token,
        TimeSpan timeout)
    {
        var challenge = ReceiveChallenge(client, timeout);
        if (challenge is null)
        {
            return null;
        }

        await client.SendTextAsync(device.BuildAuthRequest(challenge.Sid, challenge.Nonce, token));
        return WaitFor(client, frame => frame.MessageType == "welcome", timeout);
    }

    public static IncomingFrame? WaitFor(WssTestClient client, Func<IncomingFrame, bool> predicate, TimeSpan timeout)
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

    private static string? GetString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static long GetLong(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number
            ? value.GetInt64()
            : 0;
}
