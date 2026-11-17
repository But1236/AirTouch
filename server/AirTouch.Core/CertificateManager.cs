using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace AirTouch.Core;

/// <summary>The self-signed TLS certificate the WSS listener presents to the phone.</summary>
public sealed class ServerCertificate
{
    public required X509Certificate2 Certificate { get; init; }

    /// <summary>SHA-256 fingerprint, uppercase hex separated by colons, for out-of-band verification.</summary>
    public required string Fingerprint { get; init; }

    public required bool WasCreated { get; init; }

    /// <summary>
    /// True when the private key is only in process memory (<see cref="X509KeyStorageFlags.EphemeralKeySet"/>).
    /// Schannel cannot serve TLS with such a key — see <see cref="CertificateManager"/>.
    /// </summary>
    public bool IsEphemeralKey { get; init; }

    public DateTimeOffset NotAfter => Certificate.NotAfter;

    public string Subject => Certificate.Subject;
}

/// <summary>
/// Creates and persists the self-signed certificate used for <c>wss://</c>.
/// </summary>
/// <remarks>
/// The certificate lives in the data directory as a PFX. Its random password is DPAPI-protected for
/// the current user, so simply copying the PFX off the machine is not enough to impersonate the
/// server. The certificate is reused across restarts: the Android client pins its SHA-256 on first
/// use, so silently regenerating it would break every paired phone.
/// </remarks>
public static class CertificateManager
{
    private const string PfxFileName = "airtouch-server.pfx";
    private const string SecretFileName = "airtouch-server.key";
    private const int RenewWhenDaysRemaining = 30;

    /// <summary>
    /// Import flags for the PFX.
    /// </summary>
    /// <remarks>
    /// Schannel needs the private key in a real key container, so <c>UserKeySet</c> is the only mode
    /// that can actually serve TLS. If writing to the user's key container is denied by policy we fall
    /// back to an in-memory ephemeral key so the rest of
    /// the app still starts — but <see cref="AirTouchServer.Start"/> then refuses to open a TLS
    /// listener rather than pretending to be secure. See <see cref="ServerCertificate.IsEphemeralKey"/>.
    /// </remarks>
    private const X509KeyStorageFlags PrimaryImportFlags =
        X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet;

    private const X509KeyStorageFlags FallbackImportFlags =
        X509KeyStorageFlags.Exportable | X509KeyStorageFlags.EphemeralKeySet;

    public static ServerCertificate GetOrCreate(
        string dataDirectory,
        IEnumerable<string> localAddresses,
        IAirTouchLog? log = null)
    {
        log ??= NullLog.Instance;
        Directory.CreateDirectory(dataDirectory);

        var pfxPath = Path.Combine(dataDirectory, PfxFileName);
        var secretPath = Path.Combine(dataDirectory, SecretFileName);

        if (File.Exists(pfxPath) && File.Exists(secretPath))
        {
            var existing = TryLoad(pfxPath, secretPath, log);
            if (existing is not null)
            {
                log.Info($"已加载现有服务器证书（有效期至 {existing.NotAfter:yyyy-MM-dd}，指纹 {existing.Fingerprint}）");
                return existing;
            }

            log.Warn("现有证书无法使用，将重新生成");
        }

        var created = Create(dataDirectory, pfxPath, secretPath, localAddresses, log);
        return created;
    }

    private static ServerCertificate? TryLoad(string pfxPath, string secretPath, IAirTouchLog log)
    {
        try
        {
            var password = SecretStore.Load(secretPath);
            if (password is null)
            {
                return null;
            }

            var certificate = Import(File.ReadAllBytes(pfxPath), password, out var ephemeral, log);

            if (!certificate.HasPrivateKey)
            {
                log.Warn("证书文件缺少私钥");
                certificate.Dispose();
                return null;
            }

            if (certificate.NotAfter < DateTime.Now.AddDays(RenewWhenDaysRemaining))
            {
                log.Warn($"证书将在 {certificate.NotAfter:yyyy-MM-dd} 过期，重新生成");
                certificate.Dispose();
                return null;
            }

            return new ServerCertificate
            {
                Certificate = certificate,
                Fingerprint = FingerprintOf(certificate),
                WasCreated = false,
                IsEphemeralKey = ephemeral,
            };
        }
        catch (Exception ex)
        {
            log.Error("读取证书失败", ex);
            return null;
        }
    }

    private static ServerCertificate Create(
        string dataDirectory,
        string pfxPath,
        string secretPath,
        IEnumerable<string> localAddresses,
        IAirTouchLog log)
    {
        using var rsa = RSA.Create(2048);

        var subject = new X500DistinguishedName("CN=AirTouch Server, O=AirTouch");
        var request = new CertificateRequest(subject, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
            critical: true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, // serverAuth
            critical: false));

        var sanBuilder = new SubjectAlternativeNameBuilder();
        sanBuilder.AddDnsName("localhost");
        sanBuilder.AddDnsName("airtouch.local");
        sanBuilder.AddIpAddress(IPAddress.Loopback);
        sanBuilder.AddIpAddress(IPAddress.IPv6Loopback);

        var extraAddresses = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "127.0.0.1" };
        foreach (var address in localAddresses)
        {
            if (IPAddress.TryParse(address, out var parsed) && extraAddresses.Add(parsed.ToString()))
            {
                sanBuilder.AddIpAddress(parsed);
            }
        }

        request.CertificateExtensions.Add(sanBuilder.Build());
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));

        // Backdate a day to tolerate clock skew between the PC and the phone.
        var notBefore = DateTimeOffset.Now.AddDays(-1);
        var notAfter = notBefore.AddYears(2);

        using var selfSigned = request.CreateSelfSigned(notBefore, notAfter);

        var password = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var pfxBytes = selfSigned.Export(X509ContentType.Pfx, password);

        File.WriteAllBytes(pfxPath, pfxBytes);
        SecretStore.Save(secretPath, password);

        var certificate = Import(pfxBytes, password, out var ephemeral, log);

        var result = new ServerCertificate
        {
            Certificate = certificate,
            Fingerprint = FingerprintOf(certificate),
            WasCreated = true,
            IsEphemeralKey = ephemeral,
        };

        log.Info($"已生成新的自签名证书：有效期 {notAfter:yyyy-MM-dd}，指纹 {result.Fingerprint}");
        log.Info($"证书存放于 {dataDirectory}（删除 {PfxFileName} 可强制重新生成，但手机需重新配对）");

        return result;
    }

    /// <summary>
    /// Imports the PFX, preferring a real key container and degrading to an in-memory key only when
    /// the platform refuses the former.
    /// </summary>
    private static X509Certificate2 Import(byte[] pfxBytes, string password, out bool ephemeral, IAirTouchLog log)
    {
        try
        {
            ephemeral = false;
            return new X509Certificate2(pfxBytes, password, PrimaryImportFlags);
        }
        catch (CryptographicException ex)
        {
            ephemeral = true;
            log.Error(
                "无法把证书私钥写入用户密钥容器，已退回内存临时密钥（Schannel 不支持临时密钥，TLS 将不可用）：" + ex.Message);
            return new X509Certificate2(pfxBytes, password, FallbackImportFlags);
        }
    }

    public static string FingerprintOf(X509Certificate2 certificate)
    {
        var hash = certificate.GetCertHash(HashAlgorithmName.SHA256);
        var builder = new StringBuilder(hash.Length * 3);
        for (var i = 0; i < hash.Length; i++)
        {
            if (i > 0)
            {
                builder.Append(':');
            }

            builder.Append(hash[i].ToString("X2"));
        }

        return builder.ToString();
    }
}

/// <summary>
/// Stores the PFX password. Format: a one byte marker (0x01 = DPAPI protected, 0x00 = plain) followed
/// by the payload. DPAPI is preferred; if it is unavailable we still keep the server usable and say so
/// in the log, since refusing to start would be worse than a weak file permission.
/// </summary>
internal static class SecretStore
{
    private const byte MarkerDpapi = 0x01;
    private const byte MarkerPlain = 0x00;

    public static void Save(string path, string secret)
    {
        var bytes = Encoding.UTF8.GetBytes(secret);

        try
        {
            var protectedBytes = ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser);
            var payload = new byte[protectedBytes.Length + 1];
            payload[0] = MarkerDpapi;
            protectedBytes.CopyTo(payload, 1);
            File.WriteAllBytes(path, payload);
        }
        catch (Exception)
        {
            var payload = new byte[bytes.Length + 1];
            payload[0] = MarkerPlain;
            bytes.CopyTo(payload, 1);
            File.WriteAllBytes(path, payload);
        }
    }

    public static string? Load(string path)
    {
        if (!File.Exists(path))
        {
            return null;
        }

        var payload = File.ReadAllBytes(path);
        if (payload.Length < 2)
        {
            return null;
        }

        var body = payload[1..];

        if (payload[0] == MarkerDpapi)
        {
            var plain = ProtectedData.Unprotect(body, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plain);
        }

        return Encoding.UTF8.GetString(body);
    }
}
