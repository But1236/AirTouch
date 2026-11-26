using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using AirTouch.Core;

namespace AirTouch.Server;

public partial class MainWindow : Window
{
    private readonly AirTouchLog _log;
    private readonly Win32MouseController _mouse;
    private readonly ObservableCollection<LogEntry> _logEntries = new();
    private readonly ObservableCollection<TrustedDevice> _trustedDevices = new();
    private readonly DispatcherTimer _toastTimer = new() { Interval = TimeSpan.FromSeconds(2.0) };
    private readonly RadioButton[] _accentRadios;

    private AirTouchOptions _options;
    private AirTouchServer _server;
    private string? _qrPayload;
    private string? _qrRenderKey;
    private bool _sending;
    private bool _suppressAddressEvent;
    private bool _suppressZoomModeEvent;
    private bool _suppressThemeEvent;
    private bool _suppressAccentEvent;
    private IReadOnlyList<TrustedDevice> _trustedSnapshot = Array.Empty<TrustedDevice>();
    private bool _pulsing;

    // 「丢文件进 push 目录就自动发到手机」：省掉「手点发送文件 → 在对话框里翻文件」两步。
    // AI 每次改完 APK 想直接推到手机时用这条路径（也方便任何人脚本化地推文件）。
    private readonly DispatcherTimer _pushTimer = new() { Interval = TimeSpan.FromSeconds(2.0) };
    private readonly Dictionary<string, long> _pushSeen = new();

    private string PushDirectory => System.IO.Path.Combine(_options.DataDirectory, "push");

    public MainWindow(AirTouchOptions options, AirTouchLog log)
    {
        _options = options;
        _log = log;
        _mouse = new Win32MouseController(log);

        InitializeComponent();
        _accentRadios = new[] { AccentAmber, AccentCyan, AccentMint, AccentCoral, AccentViolet };

        _server = CreateServer(_options);
        AttachServer(_server);

        TrustedList.ItemsSource = _trustedDevices;
        ConsoleList.ItemsSource = _logEntries;
        LogList.ItemsSource = _logEntries;
        foreach (var entry in _log.Snapshot())
        {
            _logEntries.Add(entry);
        }

        _log.EntryWritten += OnLogEntryWritten;
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer.Stop();
            var fade = new DoubleAnimation(1, 0, TimeSpan.FromSeconds(0.3))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn },
            };
            fade.Completed += (_, _) => Toast.Visibility = Visibility.Collapsed;
            Toast.BeginAnimation(UIElement.OpacityProperty, fade);
        };

        PopulateAddresses();
        PopulateZoomModes();
        TxtPort.Text = _options.Port.ToString();
        TxtDownloadDir.Text = _options.DownloadDirectory;
        SyncThemeControls();
        SyncAccentControls();
        UpdateUi();

        _pushTimer.Tick += (_, _) => TryPushWatchedFile();
        _pushTimer.Start();

        KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape)
            {
                CloseOverlays();
            }
        };
        Loaded += (_, _) => StartServer();
        Closing += (_, _) => DisposeServer();
    }

    /// <summary>Exposed for the automated self-test in <see cref="App"/>.</summary>
    public AirTouchServer Server => _server;

    public int LastQrPngLength { get; private set; }

    public int LastQrPixelsPerModule { get; private set; }

    public int LastQrModuleCount { get; private set; }

    public int ZoomModeChoiceCount => CmbZoomMode.Items.Count;

    public string SelectedZoomPreference =>
        CmbZoomMode.SelectedItem is ZoomModeChoice choice ? choice.Mode.ToWire() : "—";

    /// <summary>228px at 4 px/module for the ~57-module v2 URL — fits the 244px tray with its 8px quiet zone.</summary>
    private const int QrDisplayBudgetPixels = 240;

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

    private AirTouchServer CreateServer(AirTouchOptions options) => new(options, _mouse, _log);

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

    private sealed record ZoomModeChoice(ZoomMode Mode, string Label)
    {
        public override string ToString() => Label;
    }

    private void PopulateZoomModes()
    {
        _suppressZoomModeEvent = true;
        try
        {
            CmbZoomMode.Items.Add(new ZoomModeChoice(ZoomMode.Auto, "自动检测"));
            CmbZoomMode.Items.Add(new ZoomModeChoice(ZoomMode.NativeTouch, "原生触控注入"));
            CmbZoomMode.Items.Add(new ZoomModeChoice(ZoomMode.WheelFallback, "Ctrl + 高精度滚轮"));
            CmbZoomMode.SelectedIndex = 0;
        }
        finally
        {
            _suppressZoomModeEvent = false;
        }
    }

    private void SyncZoomModeSelection(string preference)
    {
        var index = preference switch
        {
            "native" => 1,
            "wheel" => 2,
            _ => 0,
        };

        if (CmbZoomMode.SelectedIndex == index)
        {
            return;
        }

        _suppressZoomModeEvent = true;
        try
        {
            CmbZoomMode.SelectedIndex = index;
        }
        finally
        {
            _suppressZoomModeEvent = false;
        }
    }

    private static string DescribeZoomPreference(string preference) => preference switch
    {
        "native" => "原生触控注入",
        "wheel" => "Ctrl + 高精度滚轮",
        _ => "自动检测",
    };

    private void OnZoomModeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressZoomModeEvent || CmbZoomMode.SelectedItem is not ZoomModeChoice choice)
        {
            return;
        }

        try
        {
            _server.SetZoomMode(choice.Mode);
            _log.Info($"已把缩放偏好设为「{choice.Label}」");
        }
        catch (Exception ex)
        {
            _log.Error("设置缩放偏好失败", ex);
        }

        UpdateUi();
    }

    private void OnZoomRefreshClick(object sender, RoutedEventArgs e)
    {
        try
        {
            var status = _server.RefreshZoomCapability();
            _log.Info(
                $"触控能力重新检测：SM_MAXIMUMTOUCHES={status.Capability.MaximumTouches}，" +
                $"SM_DIGITIZER={status.Capability.Digitizer}，当前后端={status.Backend.ToWire()}");
            ShowToast("触控能力已重新检测");
        }
        catch (Exception ex)
        {
            _log.Error("重新检测触控能力失败", ex);
        }

        UpdateUi();
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
            if (result.Success)
            {
                ShowToast($"已接收 {result.FileName}");
            }
        }));

    private void OnFileSendProgress(object? sender, FileSendProgress progress) =>
        Dispatcher.BeginInvoke(new Action(() =>
        {
            SendProgress.Value = progress.Percent;
        }));

    private void OnLogEntryWritten(object? sender, LogEntry entry) =>
        Dispatcher.BeginInvoke(new Action(() =>
        {
            _logEntries.Add(entry);
            while (_logEntries.Count > 400)
            {
                _logEntries.RemoveAt(0);
            }

            ConsoleList.ScrollIntoView(entry);
            LogList.ScrollIntoView(entry);
        }));

    private void UpdateUi()
    {
        var status = _server.GetStatus();
        var connected = status.ClientAddress is not null;

        StatusText.Text = connected
            ? $"{status.ClientName ?? status.ClientAddress}{(string.IsNullOrWhiteSpace(status.ClientAddress) ? string.Empty : " · " + status.ClientAddress)}"
            : "等待手机接入...";
        SetPulse(connected);

        TxtBanned.Text = status.BannedAddresses.Count == 0 ? "无" : string.Join("，", status.BannedAddresses);
        TxtDownload.Text = status.DownloadDirectory;
        TxtFingerprint.Text = status.CertificateFingerprint;
        TxtSid.Text = status.Sid;
        TxtPin.Text = status.CertificatePin;

        TxtZoom.Text = status.ZoomBackend == "native_touch"
            ? "已激活原生触控栈（InjectTouchInput）：捏合直接注入两个真实触点"
            : status.ZoomTouchAvailable
                ? "滚轮兼容模式（Ctrl+高精度滚轮）——触控注入可用，但偏好设成了「Ctrl + 高精度滚轮」"
                : "未检测到触控硬件，双指缩放走 Ctrl + 高精度滚轮兼容模式";

        TxtZoomHint.Text =
            $"SM_MAXIMUMTOUCHES={status.ZoomMaximumTouches}，SM_DIGITIZER={status.ZoomDigitizer}" +
            $"（{status.ZoomProbedAt:HH:mm:ss} 检测，偏好「{DescribeZoomPreference(status.ZoomPreference)}」）" +
            (status.ZoomTouchAvailable
                ? string.Empty
                : "——本机没有触控数字化仪，插上触控设备后点「重新检测」即可切换");

        SyncZoomModeSelection(status.ZoomPreference);
        UpdateTrustedDevices(status);

        BtnRefreshToken.IsEnabled = status.IsRunning;
        BtnApplyPort.IsEnabled = !_sending;
        BtnSendFile.IsEnabled = status.IsRunning && connected && !_sending;
        BtnCopyUrl.IsEnabled = _qrPayload is not null;
        BtnRevokeAll.IsEnabled = _trustedDevices.Count > 0;

        UpdateQrCode(status);

        Title = connected
            ? $"AirTouch 服务端 — 已连接 {status.ClientAddress}"
            : "AirTouch 服务端";
    }

    private void SetPulse(bool connected)
    {
        if (connected && !_pulsing)
        {
            StatusDot.SetResourceReference(Shape.FillProperty, "AccentBrush");
            StatusDot.Effect = new DropShadowEffect
            {
                Color = (Color)ColorConverter.ConvertFromString("#F5B841"),
                ShadowDepth = 0,
                BlurRadius = 8,
                Opacity = 0.9,
            };
            StatusDot.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1.0, 0.3, TimeSpan.FromSeconds(0.85))
            {
                AutoReverse = true,
                RepeatBehavior = RepeatBehavior.Forever,
                EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut },
            });
            _pulsing = true;
        }
        else if (!connected && _pulsing)
        {
            StatusDot.BeginAnimation(UIElement.OpacityProperty, null);
            StatusDot.Opacity = 1;
            StatusDot.SetResourceReference(Shape.FillProperty, "MutedBrush");
            StatusDot.Effect = null;
            _pulsing = false;
        }
    }

    private void UpdateTrustedDevices(ServerStatus status)
    {
        var incoming = status.TrustedDevices;
        var same = _trustedSnapshot.Count == incoming.Count;
        if (same)
        {
            for (var i = 0; i < incoming.Count; i++)
            {
                if (!string.Equals(_trustedSnapshot[i].KeyId, incoming[i].KeyId, StringComparison.Ordinal) ||
                    !string.Equals(_trustedSnapshot[i].Name, incoming[i].Name, StringComparison.Ordinal) ||
                    _trustedSnapshot[i].SessionCount != incoming[i].SessionCount ||
                    _trustedSnapshot[i].LastSeenUtc != incoming[i].LastSeenUtc)
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

        _trustedSnapshot = incoming;
        _trustedDevices.Clear();
        foreach (var device in incoming)
        {
            _trustedDevices.Add(device);
        }

        TrustedTitle.Text = $"已信任设备 ({incoming.Count})";
        EmptyDevices.Visibility = incoming.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static string EffectiveAccentHex() => (App.CurrentAccent, App.IsLightEffective) switch
    {
        ("amber", true) => "#D97706",
        ("amber", false) => "#F5B841",
        ("cyan", _) => "#38BDF8",
        ("mint", _) => "#34D399",
        ("coral", _) => "#FB7185",
        ("violet", _) => "#A78BFA",
        _ => "#F5B841",
    };

    private void RefreshQrStyle()
    {
        // XAML's IsChecked on the default theme tab fires before the constructor assigns _server.
        if (_server is null || QrImage is null)
        {
            return;
        }

        UpdateQrCode(_server.GetStatus());
    }

    private void UpdateQrCode(ServerStatus status)
    {
        var accent = EffectiveAccentHex();
        var renderKey = $"{status.Url}|{accent}|{(App.IsLightEffective ? "L" : "D")}";
        if (string.Equals(_qrRenderKey, renderKey, StringComparison.Ordinal))
        {
            return;
        }

        try
        {
            // Dark theme: accent-colored modules on a black field. Light theme: classic dark-on-white.
            var png = App.IsLightEffective
                ? QrCodeRenderer.RenderPngForWidth(status.Url, QrDisplayBudgetPixels,
                    out var pixelsPerModule, out var modules, "#000000", "#FFFFFF")
                : QrCodeRenderer.RenderPngForWidth(status.Url, QrDisplayBudgetPixels,
                    out pixelsPerModule, out modules, accent, "#000000");
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
            _qrPayload = status.Url;
            _qrRenderKey = renderKey;
            _log.Info($"二维码已更新（{png.Length} 字节 PNG，{modules} 模块 × {pixelsPerModule} 像素 = {image.PixelWidth}px）");
        }
        catch (Exception ex)
        {
            _log.Error("生成二维码失败", ex);
        }
    }

    // --------------------------- theme & settings ---------------------------

    private void SyncThemeControls()
    {
        if (ThemeIcon is null || ThemeDark is null || ThemeLight is null || ThemeSystem is null)
        {
            return;
        }

        _suppressThemeEvent = true;
        try
        {
            ThemeDark.IsChecked = App.CurrentTheme == ThemeMode.Dark;
            ThemeLight.IsChecked = App.CurrentTheme == ThemeMode.Light;
            ThemeSystem.IsChecked = App.CurrentTheme == ThemeMode.System;
        }
        finally
        {
            _suppressThemeEvent = false;
        }

        // Dark mode shows a sun (click to go light); light mode shows a moon (click to go dark).
        ThemeIcon.SetResourceReference(System.Windows.Shapes.Path.DataProperty, App.IsLightEffective ? "IcoMoon" : "IcoSun");
    }

    private void SyncAccentControls()
    {
        _suppressAccentEvent = true;
        try
        {
            foreach (var radio in _accentRadios)
            {
                radio.IsChecked = string.Equals(radio.Tag as string, App.CurrentAccent, StringComparison.Ordinal);
            }
        }
        finally
        {
            _suppressAccentEvent = false;
        }
    }

    private void OnToggleThemeClick(object sender, RoutedEventArgs e)
    {
        App.SetTheme(App.IsLightEffective ? ThemeMode.Dark : ThemeMode.Light);
        SyncThemeControls();
        RefreshQrStyle();
    }

    private void OnThemeChoiceChecked(object sender, RoutedEventArgs e)
    {
        // IsChecked="True" in XAML fires this event before InitializeComponent assigns the fields.
        if (_suppressThemeEvent || ThemeDark is null || ThemeIcon is null)
        {
            return;
        }

        var mode = ReferenceEquals(sender, ThemeDark)
            ? ThemeMode.Dark
            : ReferenceEquals(sender, ThemeLight)
                ? ThemeMode.Light
                : ThemeMode.System;

        App.SetTheme(mode);
        SyncThemeControls();
        RefreshQrStyle();
    }

    private void OnAccentChecked(object sender, RoutedEventArgs e)
    {
        if (_suppressAccentEvent || sender is not RadioButton radio || radio.Tag is not string accent)
        {
            return;
        }

        App.SetAccent(accent);
        RefreshQrStyle();
    }

    private void OnSettingsTabChecked(object sender, RoutedEventArgs e)
    {
        // The XAML sets IsChecked on the first tab, which raises Checked before InitializeComponent
        // has assigned the panel fields. Ignore that early event; SyncSettingsTabs runs afterwards.
        if (AppearancePanel is null || ControlPanel is null || NetworkPanel is null)
        {
            return;
        }

        AppearancePanel.Visibility = ReferenceEquals(sender, TabAppearance) ? Visibility.Visible : Visibility.Collapsed;
        ControlPanel.Visibility = ReferenceEquals(sender, TabControl) ? Visibility.Visible : Visibility.Collapsed;
        NetworkPanel.Visibility = ReferenceEquals(sender, TabNetwork) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnOpenSettingsClick(object sender, RoutedEventArgs e)
    {
        SyncThemeControls();
        SyncAccentControls();
        OverlayDim.Visibility = Visibility.Visible;
        SettingsModal.Visibility = Visibility.Visible;
        ConsoleExpanded.Visibility = Visibility.Collapsed;
    }

    private void OnCloseOverlayClick(object sender, RoutedEventArgs e) => CloseOverlays();

    private void OnOverlayClick(object sender, MouseButtonEventArgs e) => CloseOverlays();

    private void CloseOverlays()
    {
        OverlayDim.Visibility = Visibility.Collapsed;
        SettingsModal.Visibility = Visibility.Collapsed;
    }

    private void OnClearConsoleClick(object sender, RoutedEventArgs e)
    {
        _logEntries.Clear();
        ShowToast("控制台消息已清空");
    }

    private void OnExpandConsoleClick(object sender, RoutedEventArgs e)
    {
        OverlayDim.Visibility = Visibility.Collapsed;
        SettingsModal.Visibility = Visibility.Collapsed;
        ConsoleExpanded.Visibility = Visibility.Visible;
        if (_logEntries.Count > 0)
        {
            LogList.ScrollIntoView(_logEntries[^1]);
        }
    }

    private void OnCloseConsoleExpandedClick(object sender, RoutedEventArgs e) =>
        ConsoleExpanded.Visibility = Visibility.Collapsed;

    private void ShowToast(string message)
    {
        Toast.BeginAnimation(UIElement.OpacityProperty, null);
        Toast.Opacity = 1;
        ToastText.Text = message;
        Toast.Visibility = Visibility.Visible;
        _toastTimer.Stop();
        _toastTimer.Start();
    }

    // ------------------------------- actions -------------------------------

    private void OnAddressChanged(object sender, SelectionChangedEventArgs e)
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

        RestartServer(port: port, message: $"服务已在端口 {port} 重启");
        UpdateUi();
    }

    /// <summary>Rebuilds the server when a setting that lives in AirTouchOptions changes.</summary>
    private void RestartServer(string? downloadDirectory = null, int? port = null, string message = "设置已生效")
    {
        try
        {
            var address = _server.AdvertisedAddress;
            DetachServer(_server);
            _server.Dispose();

            _options = new AirTouchOptions
            {
                DataDirectory = _options.DataDirectory,
                DownloadDirectory = downloadDirectory ?? _options.DownloadDirectory,
                Port = port ?? _options.Port,
                AdvertisedAddress = address,
            };

            _server = CreateServer(_options);
            AttachServer(_server);
            _server.Start();
            _log.Info(message);
            ShowToast(message);
        }
        catch (Exception ex)
        {
            _log.Error("应用设置失败", ex);
            if (!App.HeadlessSelfTest)
            {
                MessageBox.Show(this, ex.Message, "应用设置失败", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }

    private void OnBrowseDownloadClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "选择接收目录",
        };
        var current = TxtDownloadDir.Text.Trim();
        if (current.Length > 0 && Directory.Exists(current))
        {
            dialog.InitialDirectory = current;
        }

        if (dialog.ShowDialog(this) == true)
        {
            TxtDownloadDir.Text = dialog.FolderName;
        }
    }

    private void OnApplyDownloadClick(object sender, RoutedEventArgs e)
    {
        var input = TxtDownloadDir.Text.Trim();
        if (input.Length == 0)
        {
            MessageBox.Show(this, "请填写接收目录。", "接收目录", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            var path = System.IO.Path.GetFullPath(input);
            Directory.CreateDirectory(path);
            TxtDownloadDir.Text = path;
            RestartServer(downloadDirectory: path, message: $"接收目录已改为 {path}");
        }
        catch (Exception ex)
        {
            _log.Error("设置接收目录失败", ex);
            if (!App.HeadlessSelfTest)
            {
                MessageBox.Show(this, ex.Message, "设置接收目录失败", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        UpdateUi();
    }

    private void OnRefreshTokenClick(object sender, RoutedEventArgs e)
    {
        _server.RotateToken();
        _log.Info("已生成新的配对令牌（已信任的手机无需重新扫码）");
        ShowToast("已刷新配对二维码");
        UpdateUi();
    }

    private void OnResetClick(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(
            this,
            "将断开当前设备、清除所有封禁记录，并生成新的配对令牌。\n\n" +
            "已保存的信任设备不会被删除（要删除请点设备右侧的 ×）。确定继续？",
            "断开并重置",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Question);

        if (confirm != MessageBoxResult.OK)
        {
            return;
        }

        _server.ResetSecurity("用户手动重置");
        ShowToast("已断开并重置");
        UpdateUi();
    }

    private void OnRevokeItemClick(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is not TrustedDevice device)
        {
            return;
        }

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
        ShowToast("已移除信任设备");
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
        ShowToast("已清除全部信任设备");
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
        UpdateUi();

        try
        {
            var result = await _server.SendFileAsync(dialog.FileName);
            if (!result.Success)
            {
                ShowToast($"发送失败：{result.Message}");
            }
            if (result.Success)
            {
                ShowToast($"已发送 {result.FileName}");
            }
        }
        catch (Exception ex)
        {
            _log.Error("发送文件时出错", ex);
            ShowToast($"发送失败：{ex.Message}");
        }
        finally
        {
            _sending = false;
            UpdateUi();
        }
    }

    /// <summary>
    /// 每 2 秒看一次 push 目录，把「已经复制完」的第一个文件推到手机，成功后挪到 push\sent。
    /// 大小连续两轮不变才算复制完（否则会把写到一半的文件发出去）；一次只发一个，
    /// 手机没连接时安静地等（文件留在原地，连上就自动发）。日志里能看到「开始发送文件 …」。
    /// </summary>
    private async void TryPushWatchedFile()
    {
        if (_sending)
        {
            return;
        }

        string dir;
        try
        {
            dir = PushDirectory;
            Directory.CreateDirectory(dir);
        }
        catch (Exception ex)
        {
            _log.Warn($"建立推送目录失败：{ex.Message}");
            return;
        }

        string[] files;
        try
        {
            files = Directory.GetFiles(dir);
        }
        catch (Exception ex)
        {
            _log.Warn($"读取推送目录失败：{ex.Message}");
            return;
        }

        foreach (var file in files)
        {
            var name = System.IO.Path.GetFileName(file);
            long size;
            try
            {
                size = new FileInfo(file).Length;
            }
            catch
            {
                continue;
            }

            if (size <= 0)
            {
                continue;
            }

            if (!_pushSeen.TryGetValue(name, out var last) || last != size)
            {
                _pushSeen[name] = size;
                continue;
            }

            _pushSeen.Remove(name);
            _sending = true;
            SendProgress.Value = 0;
            UpdateUi();
            try
            {
                var result = await _server.SendFileAsync(file);
                if (result.Success)
                {
                    var sentDir = System.IO.Path.Combine(dir, "sent");
                    try
                    {
                        Directory.CreateDirectory(sentDir);
                        File.Move(file, System.IO.Path.Combine(sentDir, name), overwrite: true);
                    }
                    catch (Exception ex)
                    {
                        _log.Warn($"已发送但归档失败：{name}（{ex.Message}）");
                    }

                    ShowToast($"已发送 {result.FileName}");
                }
                else if (!result.Message.Contains("没有已连接"))
                {
                    _log.Warn($"推送 {name} 失败：{result.Message}（下一轮重试）");
                }
            }
            catch (Exception ex)
            {
                _log.Error($"推送 {name} 出错", ex);
            }
            finally
            {
                _sending = false;
                UpdateUi();
            }

            return;
        }
    }

    private void OnOpenDownloadClick(object sender, RoutedEventArgs e)    {
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
            ShowToast("已复制 WSS 链接");
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
            ShowToast("已复制证书指纹");
        }
        catch (Exception ex)
        {
            _log.Error("复制指纹失败", ex);
        }
    }

    private void OnCopyPinClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(_server.CertificatePin);
            _log.Info("证书指纹固定值已复制到剪贴板");
            ShowToast("已复制固定值 fp");
        }
        catch (Exception ex)
        {
            _log.Error("复制固定值失败", ex);
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
            ShowToast("二维码已保存");
        }
        catch (Exception ex)
        {
            _log.Error("保存二维码失败", ex);
        }
    }
}
