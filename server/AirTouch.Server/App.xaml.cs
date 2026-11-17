using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using AirTouch.Core;

namespace AirTouch.Server;

public partial class App : Application
{
    private AirTouchOptions? _options;
    private AirTouchLog? _log;

    /// <summary>True during <c>--smoke-test</c>: report errors instead of showing modal dialogs.</summary>
    public static bool HeadlessSelfTest { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

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
        _log?.Error("界面线程未处理的异常", e.Exception);
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
                screenWidth = status.ScreenWidth,
                screenHeight = status.ScreenHeight,
                trustedDeviceCount = status.TrustedDevices.Count,
                downloadDirectory = status.DownloadDirectory,
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
