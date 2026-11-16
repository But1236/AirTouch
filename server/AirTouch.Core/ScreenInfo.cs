using System.Windows.Forms;

namespace AirTouch.Core;

/// <summary>
/// Primary display geometry, used for the "reset cursor to centre" command and for logging.
/// </summary>
/// <remarks>
/// The process is declared per-monitor DPI aware (see app.manifest), so these bounds are physical
/// pixels — the same coordinate space <c>SetCursorPos</c> expects.
/// </remarks>
public static class ScreenInfo
{
    public static (int Width, int Height) PrimaryScreenSize()
    {
        var bounds = Screen.PrimaryScreen?.Bounds;
        return bounds is null ? (0, 0) : (bounds.Value.Width, bounds.Value.Height);
    }

    public static (int X, int Y) PrimaryScreenCenter()
    {
        var bounds = Screen.PrimaryScreen?.Bounds;
        if (bounds is null)
        {
            return (0, 0);
        }

        return (bounds.Value.Left + (bounds.Value.Width / 2), bounds.Value.Top + (bounds.Value.Height / 2));
    }
}
