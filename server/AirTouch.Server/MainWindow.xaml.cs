using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using AirTouch.Core;

namespace AirTouch.Server;

public partial class MainWindow : Window
{
    private readonly AirTouchLog _log;
    private readonly Win32MouseController _mouse;
    private readonly ObservableCollection<LogEntry> _logEntries = new();

    private AirTouchOptions _options;
    private AirTouchServer _server;
    private string? _qrPayload;
    private bool _sending;
    private bool _suppressAddressEvent;

    public MainWindow(AirTouchOptions options, AirTouchLog log)
    {
        _options = options;
        _log = log;
        _mouse = new Win32MouseController(log);

        InitializeComponent();

        _server = CreateServer(_options);
        AttachServer(_server);

        LogList.ItemsSource = _logEntries;
        foreach (var entry in _log.Snapshot())
        {
            _logEntries.Add(entry);
        }

        _log.EntryWritten += OnLogEntryWritten;

        PopulateAddresses();
        TxtPort.Text = _options.Port.ToString();
        TxtDownload.Text = _options.DownloadDirectory;
        UpdateUi();

        Loaded += (_, _) => StartServer();
        Closing += (_, _) => DisposeServer();
    }

    /// <summary>Exposed for the automated self-test in <see cref="App"/>.</summary>
    public AirTouchServer Server => _server;

    /// <summary>Size of the last rendered QR PNG; non-zero proves QR generation worked.</summary>
    public int LastQrPngLength { get; private set; }

    private AirTouchServer CreateServer(AirTouchOptions options) =>
        new(options, _mouse, _log);

    private void AttachServer(AirTouchServer server)
    {
        server.StatusChanged += OnServerStatusChanged;
        server.FileReceived += OnFileReceived;
        server.FileSendProgressChanged += OnFileSendProgress;
    }

    private void DetachServer(AirTouchServer server)
    {
        server.StatusChanged -= OnServerStatusChanged;
        server.FileReceived -= OnFileReceived;
        server.FileSendProgressChanged -= OnFileSendProgress;
    }

    private void DisposeServer()
    {
        DetachServer(_server);
        _server.Dispose();
        _log.EntryWritten -= OnLogEntryWritten;
    }

    private void PopulateAddresses()
    {
        _suppressAddressEvent = true;
        try
        {
            CmbAddress.Items.Clear();

            var candidates = LocalAddressProvider.GetCandidates();
            foreach (var candidate in candidates)
            {
                CmbAddress.Items.Add(candidate.ToString());
            }

            if (candidates.Count == 0)
            {
                CmbAddress.Items.Add("未检测到局域网 IPv4 地址");
                CmbAddress.SelectedIndex = 0;
                CmbAddress.IsEnabled = false;
                return;
            }

            CmbAddress.IsEnabled = true;

            var preferred = _options.AdvertisedAddress ?? candidates[0].Address;
            var index = candidates.ToList().FindIndex(c => c.Address == preferred);
            CmbAddress.SelectedIndex = index >= 0 ? index : 0;

            _server.SetAdvertisedAddress(candidates[CmbAddress.SelectedIndex].Address);
        }
        finally
        {
            _suppressAddressEvent = false;
        }
    }

    private void StartServer()
    {
        try
        {
            SyncAdvertisedAddress();
            _server.Start();
            var (width, height) = ScreenInfo.PrimaryScreenSize();
            _log.Info($"输入控制就绪：SendInput 目标主屏 {width}×{height}，复位中心 {ScreenInfo.PrimaryScreenCenter()}");
        }
        catch (AirTouchServerException ex)
        {
            _log.Error(ex.Message, ex.InnerException);
            MessageBox.Show(this, ex.Message, "启动失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        catch (Exception ex)
        {
            _log.Error("启动服务时发生意外错误", ex);
            MessageBox.Show(this, ex.Message, "启动失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }

        UpdateUi();
    }

    private void StopServer()
    {
        _server.Stop();
        UpdateUi();
    }

    private void SyncAdvertisedAddress()
    {
        if (CmbAddress.SelectedItem is not string text || !CmbAddress.IsEnabled)
        {
            return;
        }

        var address = text.Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
        if (!string.IsNullOrWhiteSpace(address))
        {
            _server.SetAdvertisedAddress(address);
        }
    }

    private void OnServerStatusChanged(object? sender, ServerStatus status) =>
        Dispatcher.BeginInvoke(new Action(UpdateUi));

    private void OnFileReceived(object? sender, FileTransferResult result) =>
        Dispatcher.BeginInvoke(new Action(() =>
        {
            TxtSendStatus.Text = result.Success
                ? $"已接收 {result.FileName}（{FormatBytes(result.Bytes)}）→ {result.SavedPath}"
                : $"接收 {result.FileName} 失败：{result.Message}";
        }));

    private void OnFileSendProgress(object? sender, FileSendProgress progress) =>
        Dispatcher.BeginInvoke(new Action(() =>
        {
            SendProgress.Value = progress.Percent;
            TxtSendStatus.Text = $"正在发送 {progress.FileName}：{FormatBytes(progress.SentBytes)} / {FormatBytes(progress.TotalBytes)}（{progress.Percent:0.0}%）";
        }));

    private void OnLogEntryWritten(object? sender, LogEntry entry) =>
        Dispatcher.BeginInvoke(new Action(() =>
        {
            _logEntries.Add(entry);
            while (_logEntries.Count > 400)
            {
                _logEntries.RemoveAt(0);
            }

            LogList.ScrollIntoView(entry);
        }));

    private void UpdateUi()
    {
        var status = _server.GetStatus();

        TxtStatus.Text = !status.IsRunning
            ? "未启动"
            : status.ClientAddress is null
                ? "等待手机扫码连接"
                : "已连接";

        TxtStatus.Foreground = status.IsRunning && status.ClientAddress is not null
            ? (System.Windows.Media.Brush)FindResource("OkBrush")
            : status.IsRunning
                ? (System.Windows.Media.Brush)FindResource("WarnBrush")
                : (System.Windows.Media.Brush)FindResource("MutedTextBrush");

        TxtBound.Text = status.BoundAddress ?? "—（尚未绑定，首个连接成功的设备将被锁定）";
        TxtClient.Text = status.ClientAddress is null
            ? "—"
            : $"{status.ClientAddress}{(string.IsNullOrWhiteSpace(status.ClientName) ? string.Empty : " · " + status.ClientName)}";
        TxtBanned.Text = status.BannedAddresses.Count == 0
            ? "无"
            : string.Join("，", status.BannedAddresses);
        TxtDownload.Text = status.DownloadDirectory;
        TxtFingerprint.Text = status.CertificateFingerprint;
        TxtCounters.Text =
            $"移动 {status.MoveCommands} · 点击 {status.ClickCommands} · 滚轮 {status.ScrollCommands} · 复位 {status.ResetCommands} · 收 {FormatBytes(status.BytesReceived)} / 发 {FormatBytes(status.BytesSent)}";

        BtnToggle.Content = status.IsRunning ? "停止服务" : "启动服务";
        BtnRefreshToken.IsEnabled = status.IsRunning;
        BtnApplyPort.IsEnabled = !_sending;
        BtnReset.IsEnabled = true;
        BtnSendFile.IsEnabled = status.IsRunning && status.ClientAddress is not null && !_sending;
        BtnSaveQr.IsEnabled = _qrPayload is not null;
        BtnCopyUrl.IsEnabled = _qrPayload is not null;

        UpdateQrCode(status);

        Title = status.ClientAddress is null
            ? "AirTouch 手机遥控服务端"
            : $"AirTouch 手机遥控服务端 — 已连接 {status.ClientAddress}";
    }

    private void UpdateQrCode(ServerStatus status)
    {
        if (string.Equals(_qrPayload, status.Url, StringComparison.Ordinal))
        {
            return;
        }

        try
        {
            var png = QrCodeRenderer.RenderPng(status.Url, 8);
            LastQrPngLength = png.Length;

            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(png);
            image.EndInit();
            image.Freeze();

            QrImage.Source = image;
            TxtUrl.Text = status.Url;
            _qrPayload = status.Url;
            _log.Info($"二维码已更新（{png.Length} 字节 PNG）");
        }
        catch (Exception ex)
        {
            _log.Error("生成二维码失败", ex);
        }
    }

    private void OnToggleServerClick(object sender, RoutedEventArgs e)
    {
        if (_server.IsRunning)
        {
            StopServer();
        }
        else
        {
            StartServer();
        }
    }

    private void OnAddressChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_suppressAddressEvent)
        {
            return;
        }

        SyncAdvertisedAddress();

        if (_server.IsRunning)
        {
            _log.Info("本机 IP 已切换，二维码已更新");
        }

        UpdateUi();
    }

    private void OnApplyPortClick(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(TxtPort.Text.Trim(), out var port) || port is < 1024 or > 65535)
        {
            MessageBox.Show(this, "端口必须是 1024 - 65535 之间的整数。", "端口无效", MessageBoxButton.OK, MessageBoxImage.Warning);
            TxtPort.Text = _options.Port.ToString();
            return;
        }

        if (port == _options.Port && _server.IsRunning)
        {
            _log.Info("端口未变化，无需重启");
            return;
        }

        try
        {
            var address = _server.AdvertisedAddress;
            DetachServer(_server);
            _server.Dispose();

            _options = new AirTouchOptions
            {
                DataDirectory = _options.DataDirectory,
                DownloadDirectory = _options.DownloadDirectory,
                Port = port,
                AdvertisedAddress = address,
            };

            _server = CreateServer(_options);
            AttachServer(_server);

            _server.Start();
            _log.Info($"服务已在端口 {port} 上重启");
        }
        catch (Exception ex)
        {
            _log.Error("按新端口重启失败", ex);
            MessageBox.Show(this, ex.Message, "重启失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }

        UpdateUi();
    }

    private void OnRefreshTokenClick(object sender, RoutedEventArgs e)
    {
        _server.RotateToken();
        _log.Info("已生成新的配对令牌（IP 绑定保持不变）");
        UpdateUi();
    }

    private void OnResetClick(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(
            this,
            "将断开当前设备、解除 IP 绑定、清除所有封禁记录，并生成新的配对令牌。确定继续？",
            "解除绑定并重置",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Question);

        if (confirm != MessageBoxResult.OK)
        {
            return;
        }

        _server.ResetSecurity("用户手动重置");
        UpdateUi();
    }

    private async void OnSendFileClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择要发送到手机的文件",
            Filter = "所有文件|*.*",
            CheckFileExists = true,
        };

        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        _sending = true;
        SendProgress.Value = 0;
        TxtSendStatus.Text = $"正在发送 {Path.GetFileName(dialog.FileName)}…";
        UpdateUi();

        try
        {
            var result = await _server.SendFileAsync(dialog.FileName);
            TxtSendStatus.Text = result.Success
                ? $"已发送 {result.FileName}（{FormatBytes(result.Bytes)}）"
                : $"发送失败：{result.Message}";
        }
        catch (Exception ex)
        {
            _log.Error("发送文件时出错", ex);
            TxtSendStatus.Text = $"发送失败：{ex.Message}";
        }
        finally
        {
            _sending = false;
            UpdateUi();
        }
    }

    private void OnOpenDownloadClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(_options.DownloadDirectory);
            Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_options.DownloadDirectory}\"") { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            _log.Error("打开接收目录失败", ex);
        }
    }

    private void OnCopyUrlClick(object sender, RoutedEventArgs e)
    {
        if (_qrPayload is null)
        {
            return;
        }

        try
        {
            Clipboard.SetText(_qrPayload);
            _log.Info("连接链接已复制到剪贴板");
        }
        catch (Exception ex)
        {
            _log.Error("复制到剪贴板失败", ex);
        }
    }

    private void OnCopyFingerprintClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(_server.CertificateFingerprint);
            _log.Info("证书指纹已复制到剪贴板");
        }
        catch (Exception ex)
        {
            _log.Error("复制到剪贴板失败", ex);
        }
    }

    private void OnSaveQrClick(object sender, RoutedEventArgs e)
    {
        if (_qrPayload is null)
        {
            return;
        }

        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Title = "保存配对二维码",
            Filter = "PNG 图片|*.png",
            FileName = "airtouch-pairing.png",
        };

        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        try
        {
            File.WriteAllBytes(dialog.FileName, QrCodeRenderer.RenderPng(_qrPayload, 8));
            _log.Info($"二维码已保存到 {dialog.FileName}");
        }
        catch (Exception ex)
        {
            _log.Error("保存二维码失败", ex);
        }
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024)
        {
            return $"{bytes} B";
        }

        if (bytes < 1024 * 1024)
        {
            return $"{bytes / 1024.0:0.0} KiB";
        }

        if (bytes < 1024L * 1024 * 1024)
        {
            return $"{bytes / 1024.0 / 1024:0.00} MiB";
        }

        return $"{bytes / 1024.0 / 1024 / 1024:0.00} GiB";
    }
}
