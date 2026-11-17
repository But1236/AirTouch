using System.Text.Json;

namespace AirTouch.Core;

/// <summary>
/// A stable, persisted identity for this server installation.
/// </summary>
/// <remarks>
/// <para>
/// The Android client pins the server certificate <em>per server id</em> instead of per IP address
/// (see docs/PROTOCOL.md §1.1). That matters because this app is used on a LAN where the PC's address
/// can change (DHCP lease, switching between Ethernet and Wi-Fi): with an address-keyed pin a changed
/// address silently looks like a brand-new server and the fingerprint check is skipped altogether.
/// </para>
/// <para>
/// The id is not a secret. It is handed out in the QR code and in <c>welcome</c>, and it is only ever
/// used as the lookup key for "which certificate fingerprint did I pin for this server".
/// </para>
/// </remarks>
public sealed class ServerIdentity
{
    public const string FileName = "airtouch-server.id";

    private const int Version = 1;

    private ServerIdentity(string id, bool wasCreated)
    {
        Id = id;
        WasCreated = wasCreated;
    }

    /// <summary>Unpadded base64url of 16 random bytes (22 characters).</summary>
    public string Id { get; }

    /// <summary>True when the identity file did not exist and was just created.</summary>
    public bool WasCreated { get; }

    public static ServerIdentity GetOrCreate(string dataDirectory, IAirTouchLog? log = null)
    {
        log ??= NullLog.Instance;
        Directory.CreateDirectory(dataDirectory);

        var path = Path.Combine(dataDirectory, FileName);

        if (File.Exists(path))
        {
            var loaded = TryLoad(path, log);
            if (loaded is not null)
            {
                return loaded;
            }

            log.Warn("服务器身份文件无法解析，将重新生成（已配对的手机需要重新扫码）");
        }

        var id = AuthCrypto.NewServerId();
        var created = new ServerIdentity(id, wasCreated: true);
        created.Save(path, log);
        return created;
    }

    private static ServerIdentity? TryLoad(string path, IAirTouchLog log)
    {
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object ||
                !root.TryGetProperty("sid", out var sidElement) ||
                sidElement.ValueKind != JsonValueKind.String)
            {
                return null;
            }

            var sid = sidElement.GetString();
            if (string.IsNullOrEmpty(sid) || !AuthCrypto.TryFromBase64Url(sid, out var bytes) || bytes.Length != 16)
            {
                return null;
            }

            log.Info($"已加载服务器身份：{sid}");
            return new ServerIdentity(sid, wasCreated: false);
        }
        catch (Exception ex)
        {
            log.Warn($"读取服务器身份失败：{ex.Message}");
            return null;
        }
    }

    private void Save(string path, IAirTouchLog log)
    {
        try
        {
            var payload = JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["version"] = Version,
                ["sid"] = Id,
                ["createdUtc"] = DateTimeOffset.UtcNow.ToString("O"),
            });

            var temp = path + ".tmp";
            File.WriteAllText(temp, payload);
            File.Move(temp, path, overwrite: true);
            log.Info($"已生成新的服务器身份：{Id}（存放于 {path}）");
        }
        catch (Exception ex)
        {
            // A missing identity file only costs the user a re-scan, so this is a warning, not a stop.
            log.Error("无法保存服务器身份，本次运行的身份在重启后会变化", ex);
        }
    }
}
