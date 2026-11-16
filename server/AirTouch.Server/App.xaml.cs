using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Threading;
using AirTouch.Core;

namespace AirTouch.Server;

public partial class App : Application
{
    private AirTouchOptions? _options;
    private AirTouchLog? _log;

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
            var report = new
            {
                ok = status.IsRunning,
                isRunning = status.IsRunning,
                url = status.Url,
                token = status.Token,
                port = status.Port,
                advertisedAddress = status.AdvertisedAddress,
                certificateFingerprint = status.CertificateFingerprint,
                downloadDirectory = status.DownloadDirectory,
                qrBytes = window.LastQrPngLength,
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
}
