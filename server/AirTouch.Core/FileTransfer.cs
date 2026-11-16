namespace AirTouch.Core;

public sealed record FileTransferResult(
    bool Success,
    string FileName,
    long Bytes,
    string? SavedPath,
    string Message);

/// <summary>
/// Filename and path hardening for everything that comes off the wire.
/// </summary>
/// <remarks>
/// A phone can send any string as <c>fileName</c>; the server must assume it is hostile. The rules
/// here are intentionally paranoid: strip every directory component, drop characters Windows treats
/// specially, refuse reserved device names, and finally re-verify that the resolved path is still
/// inside the configured download directory.
/// </remarks>
public static class SafeFileName
{
    private static readonly HashSet<string> ReservedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };

    private const int MaxNameLength = 180;

    public static string Sanitize(string? rawName)
    {
        var name = rawName ?? string.Empty;

        // Treat both separators as path separators regardless of platform, then keep the last
        // segment. This is what defeats "../../windows/system32/evil.dll".
        name = name.Replace('\\', '/');
        var lastSeparator = name.LastIndexOf('/');
        if (lastSeparator >= 0)
        {
            name = name[(lastSeparator + 1)..];
        }

        // Path.GetFileName is a second, platform aware pass.
        name = Path.GetFileName(name);

        var invalid = Path.GetInvalidFileNameChars();
        var builder = new System.Text.StringBuilder(name.Length);
        foreach (var ch in name)
        {
            if (char.IsControl(ch) || Array.IndexOf(invalid, ch) >= 0)
            {
                continue;
            }

            builder.Append(ch);
        }

        name = builder.ToString().Trim();

        // Windows silently drops trailing dots and spaces, which is a classic extension-smuggling
        // trick ("evil.exe." becomes "evil.exe").
        name = name.TrimEnd('.', ' ').TrimStart(' ');

        if (name is "." or "..")
        {
            name = string.Empty;
        }

        if (name.Length > MaxNameLength)
        {
            var extension = Path.GetExtension(name);
            if (extension.Length > 16)
            {
                extension = string.Empty;
            }

            name = name[..(MaxNameLength - extension.Length)] + extension;
        }

        var stem = Path.GetFileNameWithoutExtension(name);
        if (ReservedNames.Contains(stem))
        {
            name = "_" + name;
        }

        if (string.IsNullOrWhiteSpace(name) || name is "." or "..")
        {
            name = $"file_{DateTime.Now:yyyyMMdd_HHmmss}.bin";
        }

        return name;
    }

    /// <summary>True when <paramref name="candidatePath"/> resolves inside <paramref name="directory"/>.</summary>
    public static bool IsInside(string directory, string candidatePath)
    {
        var root = Path.GetFullPath(directory);
        var candidate = Path.GetFullPath(candidatePath);

        if (!root.EndsWith(Path.DirectorySeparatorChar))
        {
            root += Path.DirectorySeparatorChar;
        }

        return candidate.StartsWith(root, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Finds a non-colliding file name by appending " (1)", " (2)", … before the extension.</summary>
    public static string MakeUnique(string directory, string fileName)
    {
        var candidate = Path.Combine(directory, fileName);
        if (!File.Exists(candidate))
        {
            return candidate;
        }

        var stem = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);

        for (var index = 1; index < 10_000; index++)
        {
            candidate = Path.Combine(directory, $"{stem} ({index}){extension}");
            if (!File.Exists(candidate))
            {
                return candidate;
            }
        }

        return Path.Combine(directory, $"{stem} ({Guid.NewGuid():N}){extension}");
    }
}

/// <summary>
/// Streams one incoming file to disk. Chunks arrive as binary frames after the <c>file_header</c>
/// text frame; the transfer is written to a <c>.part</c> file and only renamed once the declared byte
/// count has arrived, so a truncated transfer never leaves a plausible-looking file behind.
/// </summary>
public sealed class IncomingFileTransfer : IDisposable
{
    private readonly FileStream _stream;
    private readonly string _partPath;
    private bool _finished;

    private IncomingFileTransfer(string fileName, long expectedSize, string partPath, FileStream stream)
    {
        FileName = fileName;
        ExpectedSize = expectedSize;
        _partPath = partPath;
        _stream = stream;
    }

    public string FileName { get; }

    public long ExpectedSize { get; }

    public long ReceivedBytes { get; private set; }

    public bool IsComplete => ReceivedBytes == ExpectedSize;

    public string PartPath => _partPath;

    public static IncomingFileTransfer? TryStart(
        string downloadDirectory,
        string? rawFileName,
        long declaredSize,
        long maxFileSize,
        out string? error)
    {
        error = null;

        if (declaredSize < 0)
        {
            error = "文件大小无效";
            return null;
        }

        if (declaredSize > maxFileSize)
        {
            error = $"文件超过上限（{declaredSize} > {maxFileSize} 字节）";
            return null;
        }

        var safeName = SafeFileName.Sanitize(rawFileName);
        var fullDirectory = Path.GetFullPath(downloadDirectory);
        Directory.CreateDirectory(fullDirectory);

        // Re-verify containment after sanitising; belt and braces.
        var target = Path.Combine(fullDirectory, safeName);
        if (!SafeFileName.IsInside(fullDirectory, target))
        {
            error = "文件名非法（疑似目录穿越）";
            return null;
        }

        var partPath = Path.Combine(fullDirectory, safeName + ".part");

        try
        {
            var drive = new DriveInfo(Path.GetPathRoot(fullDirectory) ?? fullDirectory);
            if (drive.IsReady && drive.AvailableFreeSpace < declaredSize + (1024 * 1024))
            {
                error = "磁盘剩余空间不足";
                return null;
            }
        }
        catch (Exception)
        {
            // Not fatal: fall through and let the write fail if it really cannot fit.
        }

        try
        {
            var stream = new FileStream(
                partPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                bufferSize: 64 * 1024,
                FileOptions.SequentialScan);

            return new IncomingFileTransfer(safeName, declaredSize, partPath, stream);
        }
        catch (Exception ex)
        {
            error = $"无法创建文件：{ex.Message}";
            return null;
        }
    }

    /// <summary>Appends a chunk. Returns false (with <paramref name="error"/> set) if the peer overran.</summary>
    public bool Append(byte[] buffer, int offset, int count, out string? error)
    {
        error = null;

        if (_finished)
        {
            error = "传输已结束";
            return false;
        }

        if (count <= 0)
        {
            return true;
        }

        if (ReceivedBytes + count > ExpectedSize)
        {
            error = "收到的数据超过声明大小";
            return false;
        }

        try
        {
            _stream.Write(buffer, offset, count);
            ReceivedBytes += count;
            return true;
        }
        catch (Exception ex)
        {
            error = $"写入失败：{ex.Message}";
            return false;
        }
    }

    /// <summary>Flushes and renames the <c>.part</c> file into place.</summary>
    public FileTransferResult Complete()
    {
        if (_finished)
        {
            return new FileTransferResult(false, FileName, ReceivedBytes, null, "传输已结束");
        }

        try
        {
            _stream.Flush(true);
            _stream.Dispose();
            _finished = true;

            if (ReceivedBytes != ExpectedSize)
            {
                DeletePartFile();
                return new FileTransferResult(
                    false,
                    FileName,
                    ReceivedBytes,
                    null,
                    $"字节数不符（收到 {ReceivedBytes}，声明 {ExpectedSize}）");
            }

            var directory = Path.GetDirectoryName(_partPath)!;
            var finalPath = SafeFileName.MakeUnique(directory, FileName);

            if (!SafeFileName.IsInside(directory, finalPath))
            {
                DeletePartFile();
                return new FileTransferResult(false, FileName, ReceivedBytes, null, "目标路径非法");
            }

            File.Move(_partPath, finalPath);
            return new FileTransferResult(true, Path.GetFileName(finalPath), ReceivedBytes, finalPath, "接收完成");
        }
        catch (Exception ex)
        {
            DeletePartFile();
            return new FileTransferResult(false, FileName, ReceivedBytes, null, $"保存失败：{ex.Message}");
        }
    }

    public void Abort(string reason)
    {
        if (_finished)
        {
            return;
        }

        _finished = true;
        try
        {
            _stream.Dispose();
        }
        catch (Exception)
        {
            // ignored
        }

        DeletePartFile();
    }

    public void Dispose() => Abort("disposed");

    private void DeletePartFile()
    {
        try
        {
            if (File.Exists(_partPath))
            {
                File.Delete(_partPath);
            }
        }
        catch (Exception)
        {
            // Nothing useful to do; the .part file is inert.
        }
    }
}

/// <summary>Validated handle for a file the server is about to push to the phone.</summary>
public sealed class OutgoingFileTransfer : IDisposable
{
    private OutgoingFileTransfer(string path, string fileName, long length, FileStream stream)
    {
        Path = path;
        FileName = fileName;
        Length = length;
        Stream = stream;
    }

    public string Path { get; }

    public string FileName { get; }

    public long Length { get; }

    public FileStream Stream { get; }

    public static OutgoingFileTransfer? TryOpen(string path, long maxFileSize, out string? error)
    {
        error = null;

        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            error = "文件不存在";
            return null;
        }

        try
        {
            var info = new FileInfo(path);
            if (info.Length > maxFileSize)
            {
                error = $"文件超过上限（{info.Length} > {maxFileSize} 字节）";
                return null;
            }

            var stream = new FileStream(
                info.FullName,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                bufferSize: 64 * 1024,
                FileOptions.SequentialScan);

            return new OutgoingFileTransfer(info.FullName, info.Name, info.Length, stream);
        }
        catch (Exception ex)
        {
            error = $"无法打开文件：{ex.Message}";
            return null;
        }
    }

    public void Dispose() => Stream.Dispose();
}
