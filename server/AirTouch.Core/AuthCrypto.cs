using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace AirTouch.Core;

/// <summary>
/// The application-layer authentication primitives shared by the server, the self-test client and the
/// Android app (see docs/PROTOCOL.md §1.2).
/// </summary>
/// <remarks>
/// <para>
/// Everything here is deliberately tiny and byte-exact, because a mismatch between two languages in
/// any of these details produces a signature that simply never verifies:
/// </para>
/// <list type="bullet">
///   <item>curve: NIST P-256 (secp256r1) — the only curve the Android Keystore can hold;</item>
///   <item>public key encoding: X.509 SubjectPublicKeyInfo DER (Android <c>PublicKey.getEncoded()</c>,
///     .NET <c>AsymmetricAlgorithm.ExportSubjectPublicKeyInfo()</c>);</item>
///   <item>signature encoding: ASN.1 DER (RFC 3279) as produced by Java's
///     <c>SHA256withECDSA</c>; .NET must therefore verify with
///     <see cref="DSASignatureFormat.Rfc3279DerSequence"/> instead of its IEEE-P1363 default;</item>
///   <item>the signed bytes are <see cref="Canonical"/>: a fixed LF-joined ASCII line, never JSON.</item>
/// </list>
/// <para>
/// The signed payload always contains the server's single-use <c>nonce</c>, so a captured frame can
/// never be replayed (docs/PROTOCOL.md §1.2 step 3).
/// </para>
/// </remarks>
public static class AuthCrypto
{
    /// <summary>First line of the canonical string; changing it invalidates every stored device key.</summary>
    public const string Domain = "AirTouch-Auth-v1";

    /// <summary>Value of the <c>auth_v</c> field; the wire version of the auth exchange.</summary>
    public const int AuthVersion = 1;

    /// <summary>Number of SHA-256 bytes kept for key ids and certificate fingerprints (128 bits).</summary>
    public const int FingerprintBytes = 16;

    private const string Base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /// <summary>Unpadded base64url (RFC 4648 §5), used for every binary field on the wire.</summary>
    public static string Base64Url(ReadOnlySpan<byte> data)
    {
        var text = Convert.ToBase64String(data);
        var builder = new StringBuilder(text.Length);
        foreach (var c in text)
        {
            if (c == '=')
            {
                continue;
            }

            builder.Append(c switch
            {
                '+' => '-',
                '/' => '_',
                _ => c,
            });
        }

        return builder.ToString();
    }

    /// <summary>Decodes unpadded base64url; returns false instead of throwing on malformed input.</summary>
    public static bool TryFromBase64Url(string? text, out byte[] bytes)
    {
        bytes = Array.Empty<byte>();
        if (string.IsNullOrEmpty(text))
        {
            return false;
        }

        foreach (var c in text)
        {
            if (!Base64UrlAlphabet.Contains(c, StringComparison.Ordinal))
            {
                return false;
            }
        }

        var padded = text.Replace('-', '+').Replace('_', '/');
        switch (padded.Length % 4)
        {
            case 2:
                padded += "==";
                break;
            case 3:
                padded += "=";
                break;
            case 1:
                return false;
        }

        try
        {
            bytes = Convert.FromBase64String(padded);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    /// <summary>Decodes unpadded base64url; throws on malformed input (test and fixture helpers).</summary>
    public static byte[] FromBase64UrlOrThrow(string text) =>
        TryFromBase64Url(text, out var bytes)
            ? bytes
            : throw new FormatException($"不是合法的 base64url：{text}");

    /// <summary>Truncated SHA-256 of <paramref name="data"/>, base64url encoded (key ids, pins).</summary>
    public static string Fingerprint(ReadOnlySpan<byte> data)
    {
        Span<byte> digest = stackalloc byte[32];
        SHA256.HashData(data, digest);
        return Base64Url(digest[..FingerprintBytes]);
    }

    /// <summary>Key id of a device public key: <c>base64url(SHA-256(SPKI)[0..16])</c>.</summary>
    public static string KeyIdOfPublicKey(ReadOnlySpan<byte> subjectPublicKeyInfo) =>
        Fingerprint(subjectPublicKeyInfo);

    /// <summary>Certificate pin sent in the QR code: <c>base64url(SHA-256(SPKI)[0..16])</c>.</summary>
    public static string PinOfCertificate(X509Certificate2 certificate) =>
        Fingerprint(ExportSubjectPublicKeyInfo(certificate));

    /// <summary>SPKI DER of the certificate's public key (works for RSA and ECDSA certificates).</summary>
    public static byte[] ExportSubjectPublicKeyInfo(X509Certificate2 certificate)
    {
        using var rsa = certificate.GetRSAPublicKey();
        if (rsa is not null)
        {
            return rsa.ExportSubjectPublicKeyInfo();
        }

        using var ecdsa = certificate.GetECDsaPublicKey()
            ?? throw new CryptographicException("证书公钥既不是 RSA 也不是 ECDSA");

        return ecdsa.ExportSubjectPublicKeyInfo();
    }

    /// <summary>
    /// The exact bytes a client signs. ASCII, LF separated, no trailing newline, no JSON:
    /// <c>"AirTouch-Auth-v1\n{sid}\n{nonce}\n{keyId}\n{ts}"</c>.
    /// </summary>
    public static string Canonical(string sid, string nonce, string keyId, long unixSeconds) =>
        string.Concat(Domain, "\n", sid, "\n", nonce, "\n", keyId, "\n", unixSeconds.ToString());

    /// <summary>Imports an X.509 SubjectPublicKeyInfo blob as an ECDSA key; false when malformed.</summary>
    public static bool TryImportPublicKey(byte[] subjectPublicKeyInfo, out ECDsa? key)
    {
        key = null;

        if (subjectPublicKeyInfo.Length is 0 or > 512)
        {
            return false;
        }

        ECDsa? ecdsa = null;
        try
        {
            ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(subjectPublicKeyInfo, out var read);
            if (read != subjectPublicKeyInfo.Length)
            {
                ecdsa.Dispose();
                return false;
            }

            // The device identity is P-256 only: reject anything else instead of silently accepting a
            // curve the Android Keystore cannot hold.
            if (ecdsa.KeySize != 256)
            {
                ecdsa.Dispose();
                return false;
            }

            key = ecdsa;
            return true;
        }
        catch (CryptographicException)
        {
            ecdsa?.Dispose();
            return false;
        }
    }

    /// <summary>
    /// Verifies an ASN.1 DER ECDSA/P-256/SHA-256 signature over <paramref name="canonical"/>.
    /// </summary>
    public static bool VerifySignature(byte[] subjectPublicKeyInfo, string canonical, byte[] derSignature)
    {
        if (derSignature.Length is 0 or > 256)
        {
            return false;
        }

        if (!TryImportPublicKey(subjectPublicKeyInfo, out var key) || key is null)
        {
            return false;
        }

        using (key)
        {
            try
            {
                return key.VerifyData(
                    Encoding.UTF8.GetBytes(canonical),
                    derSignature,
                    HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence);
            }
            catch (CryptographicException)
            {
                return false;
            }
        }
    }

    /// <summary>
    /// Produces the signature a client would send. Only the self-test client and the committed test
    /// vectors use this; the production path never signs on the server.
    /// </summary>
    public static byte[] SignForClient(ECDsa key, string canonical) =>
        key.SignData(
            Encoding.UTF8.GetBytes(canonical),
            HashAlgorithmName.SHA256,
            DSASignatureFormat.Rfc3279DerSequence);

    public static long UnixNow() => DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    /// <summary>Random 32-byte nonce, base64url encoded — the challenge the client must sign.</summary>
    public static string NewNonce() => Base64Url(RandomNumberGenerator.GetBytes(32));

    /// <summary>Random 16-byte server identity, base64url encoded.</summary>
    public static string NewServerId() => Base64Url(RandomNumberGenerator.GetBytes(16));
}
