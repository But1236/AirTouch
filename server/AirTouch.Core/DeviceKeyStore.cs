using System.Text.Json;

namespace AirTouch.Core;

/// <summary>A phone whose public key this PC has agreed to trust (docs/PROTOCOL.md §4).</summary>
public sealed record TrustedDevice(
    string KeyId,
    string PublicKey,
    string Name,
    string? LastAddress,
    DateTimeOffset FirstSeenUtc,
    DateTimeOffset LastSeenUtc,
    int SessionCount)
{
    /// <summary>Short form for the UI: <c>Pixel 7 · a1b2c3d4</c>.</summary>
    public string Display => string.IsNullOrWhiteSpace(Name)
        ? KeyId
        : $"{Name} · {KeyId}";
}

/// <summary>
/// Persistent list of trusted phone public keys. Only public keys are stored — this PC holds nothing
/// that could be used to impersonate a phone.
/// </summary>
/// <remarks>
/// The file is a public document (no secrets), written atomically (temp file + rename) so a crash
/// during a pairing cannot leave a half-written JSON file behind. All access is serialized; the store
/// is touched from Fleck worker threads and from the WPF dispatcher.
/// </remarks>
public sealed class DeviceKeyStore
{
    public const string FileName = "airtouch-devices.json";

    /// <summary>Upper bound on stored devices; pairing a 17th device evicts the least recently seen.</summary>
    public const int MaxDevices = 16;

    private const int Version = 1;

    private readonly object _gate = new();
    private readonly string _path;
    private readonly IAirTouchLog _log;
    private readonly Dictionary<string, TrustedDevice> _devices = new(StringComparer.Ordinal);

    private DeviceKeyStore(string path, IAirTouchLog log)
    {
        _path = path;
        _log = log;
    }

    /// <summary>Raised (outside the lock) whenever the trust list changes.</summary>
    public event EventHandler? Changed;

    public static DeviceKeyStore Load(string dataDirectory, IAirTouchLog? log = null)
    {
        var effectiveLog = log ?? NullLog.Instance;
        Directory.CreateDirectory(dataDirectory);
        var store = new DeviceKeyStore(Path.Combine(dataDirectory, FileName), effectiveLog);
        store.LoadFromDisk();
        return store;
    }

    public IReadOnlyList<TrustedDevice> All
    {
        get
        {
            lock (_gate)
            {
                return _devices.Values
                    .OrderByDescending(d => d.LastSeenUtc)
                    .ToArray();
            }
        }
    }

    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _devices.Count;
            }
        }
    }

    public TrustedDevice? Find(string keyId)
    {
        if (string.IsNullOrEmpty(keyId))
        {
            return null;
        }

        lock (_gate)
        {
            return _devices.TryGetValue(keyId, out var device) ? device : null;
        }
    }

    /// <summary>
    /// Adds a device (pairing) or updates the stored name/address of an existing one. Evicts the least
    /// recently seen device when the list is full.
    /// </summary>
    public TrustedDevice Upsert(string keyId, string publicKey, string name, string? address)
    {
        TrustedDevice device;

        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;

            if (_devices.TryGetValue(keyId, out var existing))
            {
                device = existing with
                {
                    PublicKey = publicKey,
                    Name = string.IsNullOrWhiteSpace(name) ? existing.Name : name,
                    LastAddress = address ?? existing.LastAddress,
                    LastSeenUtc = now,
                };
            }
            else
            {
                if (_devices.Count >= MaxDevices)
                {
                    var oldest = _devices.Values.OrderBy(d => d.LastSeenUtc).First();
                    _devices.Remove(oldest.KeyId);
                    _log.Warn($"信任设备已达上限 {MaxDevices}，已移除最久未使用的设备：{oldest.Display}");
                }

                device = new TrustedDevice(
                    KeyId: keyId,
                    PublicKey: publicKey,
                    Name: string.IsNullOrWhiteSpace(name) ? "Android 设备" : name,
                    LastAddress: address,
                    FirstSeenUtc: now,
                    LastSeenUtc: now,
                    SessionCount: 0);
            }

            _devices[keyId] = device;
        }

        Save();
        Changed?.Invoke(this, EventArgs.Empty);
        return device;
    }

    /// <summary>Records a successful session (updates last-seen, address and the session counter).</summary>
    public TrustedDevice? Touch(string keyId, string? address)
    {
        TrustedDevice updated;

        lock (_gate)
        {
            if (!_devices.TryGetValue(keyId, out var existing))
            {
                return null;
            }

            updated = existing with
            {
                LastSeenUtc = DateTimeOffset.UtcNow,
                LastAddress = address ?? existing.LastAddress,
                SessionCount = existing.SessionCount + 1,
            };

            _devices[keyId] = updated;
        }

        Save();
        return updated;
    }

    /// <summary>Removes one device. Returns true when something was actually removed.</summary>
    public bool Revoke(string keyId)
    {
        bool removed;

        lock (_gate)
        {
            removed = _devices.Remove(keyId);
        }

        if (removed)
        {
            Save();
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return removed;
    }

    /// <summary>Removes every trusted device. Returns how many were removed.</summary>
    public int RevokeAll()
    {
        int count;

        lock (_gate)
        {
            count = _devices.Count;
            _devices.Clear();
        }

        if (count > 0)
        {
            Save();
            Changed?.Invoke(this, EventArgs.Empty);
        }

        return count;
    }

    private void LoadFromDisk()
    {
        if (!File.Exists(_path))
        {
            return;
        }

        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(_path));
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object ||
                !root.TryGetProperty("devices", out var devices) ||
                devices.ValueKind != JsonValueKind.Array)
            {
                _log.Warn($"信任设备文件格式无法识别，已忽略：{_path}");
                return;
            }

            var loaded = 0;
            foreach (var element in devices.EnumerateArray())
            {
                var device = TryReadDevice(element);
                if (device is null)
                {
                    continue;
                }

                lock (_gate)
                {
                    _devices[device.KeyId] = device;
                }

                loaded++;
            }

            _log.Info($"已加载 {loaded} 台信任设备（{_path}）");
        }
        catch (Exception ex)
        {
            _log.Error($"读取信任设备列表失败（将以空列表启动）：{ex.Message}");
        }
    }

    private static TrustedDevice? TryReadDevice(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        var keyId = GetString(element, "keyId");
        var publicKey = GetString(element, "publicKey");
        if (string.IsNullOrEmpty(keyId) || string.IsNullOrEmpty(publicKey))
        {
            return null;
        }

        // Reject a tampered/incorrect key id instead of trusting a mismatched pair later.
        if (!AuthCrypto.TryFromBase64Url(publicKey, out var spki) ||
            !string.Equals(AuthCrypto.KeyIdOfPublicKey(spki), keyId, StringComparison.Ordinal))
        {
            return null;
        }

        return new TrustedDevice(
            KeyId: keyId,
            PublicKey: publicKey,
            Name: GetString(element, "name") ?? "Android 设备",
            LastAddress: GetString(element, "lastAddress"),
            FirstSeenUtc: GetTime(element, "firstSeenUtc"),
            LastSeenUtc: GetTime(element, "lastSeenUtc"),
            SessionCount: (int)GetLong(element, "sessionCount"));
    }

    private void Save()
    {
        List<TrustedDevice> snapshot;
        lock (_gate)
        {
            snapshot = _devices.Values.OrderBy(d => d.FirstSeenUtc).ToList();
        }

        try
        {
            var devices = snapshot.Select(d => new Dictionary<string, object?>
            {
                ["keyId"] = d.KeyId,
                ["publicKey"] = d.PublicKey,
                ["name"] = d.Name,
                ["lastAddress"] = d.LastAddress,
                ["firstSeenUtc"] = d.FirstSeenUtc.ToString("O"),
                ["lastSeenUtc"] = d.LastSeenUtc.ToString("O"),
                ["sessionCount"] = d.SessionCount,
            }).ToArray();

            var payload = JsonSerializer.Serialize(
                new Dictionary<string, object?> { ["version"] = Version, ["devices"] = devices },
                new JsonSerializerOptions { WriteIndented = true });

            var temp = _path + ".tmp";
            File.WriteAllText(temp, payload);
            File.Move(temp, _path, overwrite: true);
        }
        catch (Exception ex)
        {
            _log.Error($"保存信任设备列表失败：{ex.Message}");
        }
    }

    private static string? GetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static long GetLong(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Number &&
        value.TryGetInt64(out var parsed)
            ? parsed
            : 0;

    private static DateTimeOffset GetTime(JsonElement element, string name)
    {
        var text = GetString(element, name);
        return DateTimeOffset.TryParse(text, out var parsed) ? parsed : DateTimeOffset.UtcNow;
    }
}
