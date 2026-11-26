using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using AirTouch.Core;
using Microsoft.Win32;

namespace AirTouch.Server;

public enum ThemeMode
{
    Dark,
    Light,
    System,
}

public partial class App : Application
{
    private AirTouchOptions? _options;
    private AirTouchLog? _log;
    private static ResourceDictionary? _themeDictionary;

    public static ThemeMode CurrentTheme { get; private set; } = ThemeMode.Dark;
    public static string CurrentAccent { get; private set; } = "amber";

    public static bool IsLightEffective => CurrentTheme switch
    {
        ThemeMode.Light => true,
        ThemeMode.Dark => false,
        _ => IsSystemLight(),
    };

    private static bool IsSystemLight()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int value && value == 1;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Swaps the theme color dictionary; every style uses DynamicResource, so controls repaint live.</summary>
    public static void SetTheme(ThemeMode mode)
    {
        CurrentTheme = mode;
        var light = IsLightEffective;
        var source = new Uri(light ? "Themes/Light.xaml" : "Themes/Dark.xaml", UriKind.Relative);
        var dictionary = new ResourceDictionary { Source = source };

        var merged = Current.Resources.MergedDictionaries;
        if (_themeDictionary is not null)
        {
            merged.Remove(_themeDictionary);
        }

        _themeDictionary = dictionary;
        merged.Add(dictionary);
        ApplyAccent();
    }

    public static void SetAccent(string accent)
    {
        CurrentAccent = accent;
        ApplyAccent();
    }

    private static void ApplyAccent()
    {
        // Light mode needs a darker amber to keep text and icon contrast on the soft white base.
        var hex = (CurrentAccent, IsLightEffective) switch
        {
            ("amber", true) => "#FFD97706",
            ("amber", false) => "#FFF5B841",
            ("cyan", _) => "#FF38BDF8",
            ("mint", _) => "#FF34D399",
            ("coral", _) => "#FFFB7185",
            ("violet", _) => "#FFA78BFA",
            _ => "#FFF5B841",
        };

        var color = (Color)ColorConverter.ConvertFromString(hex);
        Current.Resources["AccentColor"] = color;
        // Replace the brush object itself so every DynamicResource AccentBrush consumer re-resolves.
        Current.Resources["AccentBrush"] = new SolidColorBrush(color);
    }

    /// <summary>True during <c>--smoke-test</c>: report errors instead of showing modal dialogs.</summary>
    public static bool HeadlessSelfTest { get; private set; }

    /// <summary>
    /// 兜底：这台机器上 WPF 的硬件渲染/上屏路径画不出任何东西 —— 窗口 client area 在屏幕上是
    /// 整片纯白（实测 pure white ≈92%、暗像素只有 0.4%，连一个最小 WPF 空窗口也一样），
    /// 而同一个进程用 <c>RenderTargetBitmap</c>（软件光栅化）渲染出来的图是完整正确的：
    /// 二维码、面板、文字都在。也就是说视觉树没问题，坏的只是上屏那一步。
    /// 界面很轻（几块卡片 + 一张二维码 + 一个日志列表），软件渲染完全够用，
    /// 所以直接强制软件渲染。需要在这台机器上做硬件路径对比时，设 <c>AIRTOUCH_FORCE_HW=1</c> 可关掉它。
    /// </summary>
    private static void EnsureRenderable()
    {
        try
        {
            if (string.Equals(
                    Environment.GetEnvironmentVariable("AIRTOUCH_FORCE_HW"),
                    "1",
                    StringComparison.Ordinal))
            {
                return;
            }

            RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;
        }
        catch
        {
            // 设置渲染模式失败绝不能影响服务本体：最坏情况就是回到原来的空白窗口。
        }
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        EnsureRenderable();
        SetTheme(ThemeMode.Dark);

        _options = AirTouchOptions.CreateDefault();
        _log = new AirTouchLog(_options.DataDirectory);

        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            _log?.Error("未处理的后台异常", args.ExceptionObject as Exception);

        var smokeTest = e.Args.Any(arg =>
            string.Equals(arg, "--smoke-test", StringComparison.OrdinalIgnoreCase));

        // A self-test run must never park on a modal dialog: if the listener cannot start (for example
        // because this process may not write to the user's key container) the run has to fail loudly
        // and exit instead of waiting forever for someone to click OK.
        HeadlessSelfTest = smokeTest;

        var window = new MainWindow(_options, _log);
        MainWindow = window;
        window.Show();

        if (smokeTest)
        {
            RunSmokeTest(window);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        base.OnExit(e);
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        _log?.Error("界面线程未处理的异常", new Exception(e.Exception.ToString()));
        if (HeadlessSelfTest)
        {
            Shutdown(1);
            return;
        }

        MessageBox.Show(
            $"发生未处理的异常：\n{e.Exception.Message}\n\n详情见日志文件：{_options?.DataDirectory}",
            "AirTouch 服务端",
            MessageBoxButton.OK,
            MessageBoxImage.Error);

        e.Handled = true;
    }

    /// <summary>
    /// Headless-ish verification mode used by tools and CI: bring the real window and the real WSS
    /// listener up, wait a few seconds, write a JSON report and exit with a non-zero code on failure.
    /// </summary>
    private void RunSmokeTest(MainWindow window)
    {
        var reportPath = Path.Combine(_options!.DataDirectory, "wpf-smoke-test.json");

        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();

            var status = window.Server.GetStatus();
            var qr = window.MeasureQrLayout();

            // A picture of the real window: layout regressions (a clipped QR code, an overlapped
            // button) are invisible to numeric assertions, so the self-test leaves an artefact a
            // human can look at. Failure to render must never fail the run.
            var windowPngBytes = 0;
            try
            {
                windowPngBytes = CaptureWindow(window, Path.Combine(_options!.DataDirectory, "wpf-window.png"));
            }
            catch (Exception ex)
            {
                _log.Error("自检截图失败", ex);
            }

            var report = new
            {
                ok = status.IsRunning,
                isRunning = status.IsRunning,
                url = status.Url,
                token = status.Token,
                port = status.Port,
                advertisedAddress = status.AdvertisedAddress,
                certificateFingerprint = status.CertificateFingerprint,
                certificatePin = status.CertificatePin,
                sid = status.Sid,
                protocolVersion = Protocol.Version,
                trustedDeviceCount = status.TrustedDevices.Count,
                downloadDirectory = status.DownloadDirectory,
                // 双指缩放的后端（真触控注入 / Ctrl+滚轮回退）与探测结论，供自检报告留证。
                zoomTouchAvailable = status.ZoomTouchAvailable,
                zoomUnavailableReason = status.ZoomUnavailableReason,
                zoomPreference = status.ZoomPreference,
                zoomBackend = status.ZoomBackend,
                zoomMaximumTouches = status.ZoomMaximumTouches,
                zoomDigitizer = status.ZoomDigitizer,
                zoomProbedAt = status.ZoomProbedAt.ToString("O"),
                // 界面真的带上了三选一（不是只把状态写在报告里）：自检会断言这一项等于 3。
                zoomModeChoices = window.ZoomModeChoiceCount,
                zoomModeSelected = window.SelectedZoomPreference,
                touchCommands = status.TouchCommands,
                scrollCommands = status.ScrollCommands,
                qrBytes = window.LastQrPngLength,
                qrPixelsPerModule = window.LastQrPixelsPerModule,
                qrModuleCount = window.LastQrModuleCount,
                qrWidth = qr.Width,
                qrHeight = qr.Height,
                qrNaturalWidth = qr.NaturalWidth,
                qrNaturalHeight = qr.NaturalHeight,
                qrNaturalSize = qr.NaturalSize,
                qrTop = qr.Top,
                qrBottom = qr.Bottom,
                qrClientHeight = qr.ClientHeight,
                qrFullyVisible = qr.FullyVisible,
                windowPngBytes,
                log = _log!.Snapshot().Select(entry => entry.ToString()).ToArray(),
            };

            try
            {
                Directory.CreateDirectory(_options.DataDirectory);
                File.WriteAllText(reportPath, JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
            }
            catch (Exception ex)
            {
                _log.Error("写入自检报告失败", ex);
            }

            window.Close();
            Shutdown(status.IsRunning ? 0 : 1);
        };

        timer.Start();
    }

    /// <summary>Renders the live window (its client area) to a PNG and returns the file size.</summary>
    private static int CaptureWindow(Window window, string path)
    {
        if (window.Content is not FrameworkElement root)
        {
            return 0;
        }

        var width = (int)Math.Ceiling(root.ActualWidth);
        var height = (int)Math.Ceiling(root.ActualHeight);
        if (width <= 0 || height <= 0)
        {
            return 0;
        }

        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(root);

        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));

        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        using var stream = File.Create(path);
        encoder.Save(stream);

        return (int)new FileInfo(path).Length;
    }
}
