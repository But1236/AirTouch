namespace AirTouch.Core;

public enum LogLevel
{
    Info,
    Warn,
    Error,
}

public sealed record LogEntry(DateTimeOffset Timestamp, LogLevel Level, string Message)
{
    public override string ToString() =>
        $"[{Timestamp:HH:mm:ss}] {Level switch
        {
            LogLevel.Info => "信息",
            LogLevel.Warn => "警告",
            _ => "错误",
        }}  {Message}";
}

/// <summary>Minimal logging seam so the core library does not depend on a UI.</summary>
public interface IAirTouchLog
{
    void Info(string message);
    void Warn(string message);
    void Error(string message, Exception? exception = null);
}

public sealed class NullLog : IAirTouchLog
{
    public static readonly NullLog Instance = new();

    private NullLog()
    {
    }

    public void Info(string message)
    {
    }

    public void Warn(string message)
    {
    }

    public void Error(string message, Exception? exception = null)
    {
    }
}

/// <summary>
/// Thread-safe in-memory log. The WPF window subscribes to <see cref="EntryWritten"/> and appends
/// lines on the dispatcher thread. Also mirrors everything to a rolling text file next to the
/// certificate, which is what you want when debugging a pairing failure on someone else's PC.
/// </summary>
public sealed class AirTouchLog : IAirTouchLog
{
    private readonly object _gate = new();
    private readonly List<LogEntry> _entries = new();
    private readonly int _maxEntries;
    private readonly string? _filePath;

    public AirTouchLog(string? directory = null, int maxEntries = 500)
    {
        _maxEntries = maxEntries;

        if (!string.IsNullOrWhiteSpace(directory))
        {
            try
            {
                Directory.CreateDirectory(directory);
                _filePath = Path.Combine(directory, "airtouch.log");
            }
            catch (Exception)
            {
                _filePath = null;
            }
        }
    }

    public event EventHandler<LogEntry>? EntryWritten;

    public IReadOnlyList<LogEntry> Snapshot()
    {
        lock (_gate)
        {
            return _entries.ToArray();
        }
    }

    public void Info(string message) => Write(LogLevel.Info, message);

    public void Warn(string message) => Write(LogLevel.Warn, message);

    public void Error(string message, Exception? exception = null) =>
        Write(LogLevel.Error, exception is null ? message : $"{message} :: {exception.GetType().Name}: {exception.Message}");

    private void Write(LogLevel level, string message)
    {
        var entry = new LogEntry(DateTimeOffset.Now, level, message);

        lock (_gate)
        {
            _entries.Add(entry);
            if (_entries.Count > _maxEntries)
            {
                _entries.RemoveRange(0, _entries.Count - _maxEntries);
            }

            if (_filePath is not null)
            {
                try
                {
                    File.AppendAllText(_filePath, entry + Environment.NewLine);
                }
                catch (Exception)
                {
                    // Logging must never take the server down.
                }
            }
        }

        EntryWritten?.Invoke(this, entry);
    }
}
