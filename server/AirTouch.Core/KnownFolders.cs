using System.Runtime.InteropServices;

namespace AirTouch.Core;

/// <summary>
/// Resolves the well-known Windows folders we care about.
/// </summary>
/// <remarks>
/// <see cref="Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)"/> + "Downloads" is
/// only a guess: the user may have relocated the Downloads folder. <c>SHGetKnownFolderPath</c> is
/// authoritative, so we try that first and fall back to the guess.
/// </remarks>
public static class KnownFolders
{
    private static readonly Guid FolderIdDownloads = new("374DE290-123F-4565-9164-39C4925E467B");

    public static string GetDownloadsDirectory()
    {
        try
        {
            var path = GetKnownFolderPath(FolderIdDownloads);
            if (!string.IsNullOrWhiteSpace(path))
            {
                return path;
            }
        }
        catch (Exception)
        {
            // Fall through to the conventional location.
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads");
    }

    private static string? GetKnownFolderPath(Guid folderId)
    {
        var result = SHGetKnownFolderPath(folderId, 0, IntPtr.Zero, out var pathPtr);
        if (result != 0 || pathPtr == IntPtr.Zero)
        {
            return null;
        }

        try
        {
            return Marshal.PtrToStringUni(pathPtr);
        }
        finally
        {
            Marshal.FreeCoTaskMem(pathPtr);
        }
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int SHGetKnownFolderPath(
        [MarshalAs(UnmanagedType.LPStruct)] Guid rfid,
        uint dwFlags,
        IntPtr hToken,
        out IntPtr ppszPath);
}
