namespace AirTouch.Core;

/// <summary>
/// Runtime configuration for an <see cref="AirTouchServer"/> instance.
/// </summary>
/// <remarks>
/// Every path is overridable through an environment variable so that automated tests can keep all
/// state inside a scratch directory instead of touching the real user profile.
/// </remarks>
public sealed class AirTouchOptions
{
    public const string DataDirEnvVar = "AIRTOUCH_DATA_DIR";
    public const string DownloadDirEnvVar = "AIRTOUCH_DOWNLOAD_DIR";
    public const string PortEnvVar = "AIRTOUCH_PORT";
    public const string AddressEnvVar = "AIRTOUCH_ADDRESS";

    /// <summary>Directory holding the persisted self-signed certificate and logs.</summary>
    public required string DataDirectory { get; init; }

    /// <summary>Directory that received files are written to.</summary>
    public required string DownloadDirectory { get; init; }

    /// <summary>TCP port the WSS listener binds to.</summary>
    public int Port { get; init; } = DefaultPort;

    /// <summary>Hard upper bound for a single incoming or outgoing file.</summary>
    public long MaxFileSize { get; init; } = 512L * 1024 * 1024;

    /// <summary>File transfer chunk size; must match the client (64 KiB per the protocol).</summary>
    public int ChunkSize { get; init; } = 64 * 1024;

    /// <summary>Interval between server-initiated WebSocket pings.</summary>
    public TimeSpan PingInterval { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>Silence after which the peer is considered dead and the socket is dropped.</summary>
    public TimeSpan IdleTimeout { get; init; } = TimeSpan.FromSeconds(90);

    /// <summary>How many consecutive token failures from one IP trigger a permanent ban.</summary>
    public int MaxTokenFailures { get; init; } = 3;

    /// <summary>How many consecutive device-key failures from one IP trigger a permanent ban.</summary>
    /// <remarks>
    /// Higher than <see cref="MaxTokenFailures"/> on purpose: a failed key proof can also come from a
    /// phone with a badly skewed clock or a wiped key store, and those retry in a loop. Soft failures
    /// (stale timestamp, unknown key) are answered with a clear error code and never counted here.
    /// </remarks>
    public int MaxAuthFailures { get; init; } = 5;

    /// <summary>How long an unauthenticated socket may wait for its <c>auth_req</c> before it is closed.</summary>
    public TimeSpan AuthTimeout { get; init; } = TimeSpan.FromSeconds(12);

    /// <summary>Maximum number of sockets allowed to sit in the unauthenticated state at the same time.</summary>
    public int MaxPendingSockets { get; init; } = 4;

    /// <summary>Allowed clock difference between the phone and this PC when checking the signed timestamp.</summary>
    public TimeSpan AuthClockSkew { get; init; } = TimeSpan.FromSeconds(120);

    /// <summary>Connection attempts allowed from one IP inside <see cref="RateLimitWindow"/>.</summary>
    public int MaxConnectionAttemptsPerWindow { get; init; } = 20;

    /// <summary>Sliding window used by the connection rate limiter.</summary>
    public TimeSpan RateLimitWindow { get; init; } = TimeSpan.FromSeconds(10);

    /// <summary>Friendly name reported to the phone in the <c>welcome</c> frame.</summary>
    public string ServerName { get; init; } = Environment.MachineName;

    /// <summary>
    /// IPv4 address advertised in the QR code. When null the first local address with a default
    /// gateway is used (see <see cref="LocalAddressProvider"/>).
    /// </summary>
    public string? AdvertisedAddress { get; init; }

    /// <summary>
    /// True (the default and the only supported production mode) serves <c>wss://</c> with the
    /// self-signed certificate. Setting it to false serves plain <c>ws://</c>; that exists only as a
    /// test seam for hosts that cannot serve TLS, is not reachable from the UI or from any
    /// environment variable, and must never be used on a LAN.
    /// </summary>
    public bool UseTls { get; init; } = true;

    public const int DefaultPort = 8443;

    public static AirTouchOptions CreateDefault()
    {
        var dataDir = Environment.GetEnvironmentVariable(DataDirEnvVar);
        if (string.IsNullOrWhiteSpace(dataDir))
        {
            dataDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "AirTouch");
        }

        var downloadDir = Environment.GetEnvironmentVariable(DownloadDirEnvVar);
        if (string.IsNullOrWhiteSpace(downloadDir))
        {
            downloadDir = KnownFolders.GetDownloadsDirectory();
        }

        var port = DefaultPort;
        var portText = Environment.GetEnvironmentVariable(PortEnvVar);
        if (int.TryParse(portText, out var parsedPort) && parsedPort is > 0 and <= 65535)
        {
            port = parsedPort;
        }

        var address = Environment.GetEnvironmentVariable(AddressEnvVar);

        return new AirTouchOptions
        {
            DataDirectory = Path.GetFullPath(dataDir),
            DownloadDirectory = Path.GetFullPath(downloadDir),
            Port = port,
            AdvertisedAddress = string.IsNullOrWhiteSpace(address) ? null : address,
        };
    }
}
