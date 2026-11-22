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
    private IReadOnlyList<TrustedDevice> _trustedDevices = Array.Empty<TrustedDevice>();

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
        FitToWorkArea();

        Loaded += (_, _) => StartServer();
        Closing += (_, _) => DisposeServer();
    }

    /// <summary>
    /// Keeps the whole window on screen on displays whose work area is smaller than the design size
    /// (a 4K panel at 250-300% scaling, or a small laptop at 150%): Windows will happily place a
    /// centred window partly off-screen, which clips whatever sits at the window's top edge — the QR
    /// code in this layout. The layout itself is also scrollable, so shrinking never hides anything.
    /// </summary>
    private void FitToWorkArea()
    {
        var work = SystemParameters.WorkArea;
        if (work.Height <= 0 || work.Width <= 0)
        {
            return;
        }

        MaxHeight = Math.Max(MinHeight, work.Height - 24);
        MaxWidth = Math.Max(MinWidth, work.Width - 24);

        if (Height > MaxHeight)
        {
            Height = MaxHeight;
        }

        if (Width > MaxWidth)
        {
            Width = MaxWidth;
        }

        if (work.Height < Height + 8 || work.Width < Width + 8)
        {
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = Math.Max(0, work.Left + (work.Width - Width) / 2);
            Top = Math.Max(0, work.Top + (work.Height - Height) / 2);
        }
    }

    /// <summary>Exposed for the automated self-test in <see cref="App"/>.</summary>
    public AirTouchServer Server => _server;

    /// <summary>Size of the last rendered QR PNG; non-zero proves QR generation worked.</summary>
    public int LastQrPngLength { get; private set; }

    /// <summary>Pixels per module and module count of the last rendered QR image.</summary>
    public int LastQrPixelsPerModule { get; private set; }

    public int LastQrModuleCount { get; private set; }

    /// <summary>
    /// How much room the QR code may use on screen, in device-independent pixels. Kept as a constant so
    /// the layout (a 360px-wide panel with 14px padding on both sides) and the renderer cannot drift
    /// apart silently.
    /// </summary>
    private const int QrDisplayBudgetPixels = 300;

    /// <summary>
    /// Where the QR code actually ended up on screen, plus whether it is fully inside the client area
    /// and whether it was drawn at its natural (unscaled) size.
    /// </summary>
    /// <remarks>
    /// This exists because of a real bug: the left column packed two buttons, the address box and an
    /// explanation line around the QR code, and the code ended up squeezed to roughly half the size it
    /// was generated at — the panel looked like "only the top half of the QR" was there. A layout
    /// mistake like that is invisible to every non-visual assertion, so the self-test now measures the
    /// geometry and compares the drawn size with the source bitmap.
    /// </remarks>
    public sealed record QrLayout(
        double Width,
        double Height,
        double NaturalWidth,
        double NaturalHeight,
        double Top,
        double Bottom,
        double ClientHeight,
        bool FullyVisible,
        bool NaturalSize);

    public QrLayout MeasureQrLayout()
    {
        var root = Content as FrameworkElement;
        var image = QrImage;
        if (root is null || image.Source is not BitmapSource bitmap)
        {
            return new QrLayout(0, 0, 0, 0, 0, 0, root?.ActualHeight ?? 0, false, false);
        }

        // A bitmap without explicit DPI metadata is treated as 96 dpi, i.e. 1 pixel == 1 DIP.
        var naturalWidth = bitmap.PixelWidth * 96.0 / (bitmap.DpiX > 0 ? bitmap.DpiX : 96.0);
        var naturalHeight = bitmap.PixelHeight * 96.0 / (bitmap.DpiY > 0 ? bitmap.DpiY : 96.0);

        double top;
        double left;
        try
        {
            var origin = image.TransformToAncestor(root).Transform(new Point(0, 0));
            top = origin.Y;
            left = origin.X;
        }
        catch (InvalidOperationException)
        {
            return new QrLayout(0, 0, naturalWidth, naturalHeight, 0, 0, root.ActualHeight, false, false);
        }

        var bottom = top + image.ActualHeight;
        var fullyVisible = image.ActualWidth > 0
            && image.ActualHeight > 0
            && top >= -0.5
            && left >= -0.5
            && bottom <= root.ActualHeight + 0.5;
        var naturalSize = Math.Abs(image.ActualWidth - naturalWidth) < 0.5
            && Math.Abs(image.ActualHeight - naturalHeight) < 0.5;

        return new QrLayout(
            image.ActualWidth,
            image.ActualHeight,
            naturalWidth,
            naturalHeight,
            top,
            bottom,
            root.ActualHeight,
            fullyVisible,
            naturalSize);
    }

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
            if (!App.HeadlessSelfTest)
            {
                MessageBox.Show(this, ex.Message, "启动失败", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
        catch (Exception ex)
        {
            _log.Error("启动服务时发生意外错误", ex);
            if (!App.HeadlessSelfTest)
            {
                MessageBox.Show(this, ex.Message, "启动失败", MessageBoxButton.OK, MessageBoxImage.Error);
            }
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
                ? "等待手机连接"
                : "已连接";

        TxtStatus.Foreground = status.IsRunning && status.ClientAddress is not null
            ? (System.Windows.Media.Brush)FindResource("OkBrush")
            : status.IsRunning
                ? (System.Windows.Media.Brush)FindResource("WarnBrush")
                : (System.Windows.Media.Brush)FindResource("MutedTextBrush");

        TxtClient.Text = status.ClientAddress is null
            ? "—"
            : $"{status.ClientAddress}{(string.IsNullOrWhiteSpace(status.ClientName) ? string.Empty : " · " + status.ClientName)}" +
              (string.IsNullOrWhiteSpace(status.ClientKeyId) ? string.Empty : $"（{Shorten(status.ClientKeyId)}）");

        TxtAuthMode.Text = status.ClientAuthMode switch
        {
            "pair" => "首次配对（已保存该手机公钥）",
            "resume" => "已信任设备重连（免扫码）",
            _ => "—",
        };

        TxtPending.Text = status.PendingCount.ToString();

        UpdateTrustedDevices(status);

        TxtBanned.Text = status.BannedAddresses.Count == 0
            ? "无"
            : string.Join("，", status.BannedAddresses);
        TxtDownload.Text = status.DownloadDirectory;
        TxtFingerprint.Text = status.CertificateFingerprint;
        TxtSid.Text = status.Sid;
        TxtPin.Text = status.CertificatePin;
        TxtCounters.Text =
            $"移动 {status.MoveCommands} · 点击 {status.ClickCommands} · 滚轮 {status.ScrollCommands} · 复位 {status.ResetCommands} · 按键 {status.KeyCommands} · 文本 {status.TextCommands} · 收 {FormatBytes(status.BytesReceived)} / 发 {FormatBytes(status.BytesSent)}";
        TxtInject.Text = status.SendInputFailures + status.KeyInputFailures == 0
            ? "0（正常）"
            : $"光标 {status.SendInputFailures} 次 / 键盘 {status.KeyInputFailures} 次（Windows 拒绝了注入，光标与按键会「不动」）";

        BtnToggle.Content = status.IsRunning ? "停止服务" : "启动服务";
        BtnRefreshToken.IsEnabled = status.IsRunning;
        BtnApplyPort.IsEnabled = !_sending;
        BtnReset.IsEnabled = true;
        BtnSendFile.IsEnabled = status.IsRunning && status.ClientAddress is not null && !_sending;
        BtnSaveQr.IsEnabled = _qrPayload is not null;
        BtnCopyUrl.IsEnabled = _qrPayload is not null;
        BtnRevokeDevice.IsEnabled = _trustedDevices.Count > 0 && TrustedList.SelectedIndex >= 0;
        BtnRevokeAll.IsEnabled = _trustedDevices.Count > 0;

        UpdateQrCode(status);

        Title = status.ClientAddress is null
            ? "AirTouch 手机遥控服务端"
            : $"AirTouch 手机遥控服务端 — 已连接 {status.ClientAddress}";
    }

    private void UpdateTrustedDevices(ServerStatus status)
    {
        var selectedKey = TrustedList.SelectedIndex >= 0 && TrustedList.SelectedIndex < _trustedDevices.Count
            ? _trustedDevices[TrustedList.SelectedIndex].KeyId
            : null;

        // Only rebuild the list when it actually changed: reassigning ItemsSource on every status
        // update would clear the user's selection (and flicker) several times per second.
        var same = _trustedDevices.Count == status.TrustedDevices.Count;
        if (same)
        {
            for (var i = 0; i < _trustedDevices.Count; i++)
            {
                if (!string.Equals(_trustedDevices[i].KeyId, status.TrustedDevices[i].KeyId, StringComparison.Ordinal) ||
                    !string.Equals(_trustedDevices[i].Name, status.TrustedDevices[i].Name, StringComparison.Ordinal) ||
                    _trustedDevices[i].SessionCount != status.TrustedDevices[i].SessionCount)
                {
                    same = false;
                    break;
                }
            }
        }

        if (same)
        {
            return;
        }

        _trustedDevices = status.TrustedDevices;

        TrustedList.Items.Clear();
        foreach (var device in _trustedDevices)
        {
            var active = string.Equals(device.KeyId, status.ClientKeyId, StringComparison.Ordinal) ? " ← 当前连接" : string.Empty;
            TrustedList.Items.Add($"{device.Display} · 连接 {device.SessionCount} 次 · 最近 {device.LastSeenUtc.ToLocalTime():MM-dd HH:mm}{active}");
        }

        if (selectedKey is not null)
        {
            var index = _trustedDevices.ToList().FindIndex(d => string.Equals(d.KeyId, selectedKey, StringComparison.Ordinal));
            if (index >= 0)
            {
                TrustedList.SelectedIndex = index;
            }
        }
    }

    private static string Shorten(string keyId) =>
        keyId.Length <= 8 ? keyId : keyId[..8];

    private void UpdateQrCode(ServerStatus status)
    {
        if (string.Equals(_qrPayload, status.Url, StringComparison.Ordinal))
        {
            return;
        }

        try
        {
            // Rendered for a concrete on-screen budget at a whole number of pixels per module, then
            // displayed 1:1 (Stretch=None) — see QrCodeRenderer.RenderPngForWidth.
            var png = QrCodeRenderer.RenderPngForWidth(status.Url, QrDisplayBudgetPixels, out var pixelsPerModule, out var modules);
            LastQrPngLength = png.Length;
            LastQrPixelsPerModule = pixelsPerModule;
            LastQrModuleCount = modules;

            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(png);
            image.EndInit();
            image.Freeze();

            QrImage.Source = image;
            TxtUrl.Text = status.Url;
            _qrPayload = status.Url;
            _log.Info($"二维码已更新（{png.Length} 字节 PNG，{modules} 模块 × {pixelsPerModule} 像素 = {image.PixelWidth}px）");
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
            _log.Info("本机 IP 已切换，二维码已更新（已配对的手机不受影响）");
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
        _log.Info("已生成新的配对令牌（已信任的手机无需重新扫码）");
        UpdateUi();
    }

    private void OnResetClick(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(
            this,
            "将断开当前设备、清除所有封禁记录，并生成新的配对令牌。\n\n" +
            "已保存的信任设备不会被删除（要删除请用「撤销选中设备」）。确定继续？",
            "断开并重置",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Question);

        if (confirm != MessageBoxResult.OK)
        {
            return;
        }

        _server.ResetSecurity("用户手动重置");
        UpdateUi();
    }

    private void OnRevokeDeviceClick(object sender, RoutedEventArgs e)
    {
        var index = TrustedList.SelectedIndex;
        if (index < 0 || index >= _trustedDevices.Count)
        {
            return;
        }

        var device = _trustedDevices[index];
        var confirm = MessageBox.Show(
            this,
            $"撤销后这台手机必须重新扫码才能连接：\n\n{device.Display}\n\n确定继续？",
            "撤销设备信任",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Question);

        if (confirm != MessageBoxResult.OK)
        {
            return;
        }

        _server.RevokeDevice(device.KeyId);
        TrustedList.SelectedIndex = -1;
        UpdateUi();
    }

    private void OnRevokeAllClick(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(
            this,
            $"将删除全部 {_trustedDevices.Count} 台信任设备，之后每台手机都需要重新扫码配对。确定继续？",
            "清除全部信任",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Warning);

        if (confirm != MessageBoxResult.OK)
        {
            return;
        }

        _server.RevokeAllDevices();
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

    private void OnCopyPinClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(_server.CertificatePin);
            _log.Info("证书指纹固定值已复制到剪贴板");
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
