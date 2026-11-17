using System.Runtime.InteropServices;
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

    /// <summary>
    /// Current refresh rate of the primary display in Hz (0 when it cannot be determined).
    /// </summary>
    /// <remarks>
    /// This is the perceptual ceiling for cursor smoothness: the desktop composites the pointer at most
    /// once per refresh, so a client streaming 144 frames/s looks like 60 steps/s on a 60 Hz panel —
    /// with uneven sampling on top, which reads as judder rather than smooth motion. Sending the number
    /// to the phone turns "看起来不够流畅" into a measurable comparison.
    /// </remarks>
    public static int PrimaryScreenRefreshHz()
    {
        try
        {
            var mode = new DevMode { Size = (short)Marshal.SizeOf<DevMode>() };
            return EnumDisplaySettings(null, EnumCurrentSettings, ref mode)
                ? Math.Max(0, mode.DisplayFrequency)
                : 0;
        }
        catch (Exception)
        {
            return 0;
        }
    }

    private const int EnumCurrentSettings = -1;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DevMode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string DeviceName;

        public short SpecVersion;
        public short DriverVersion;
        public short Size;
        public short DriverExtra;
        public int Fields;
        public int PositionX;
        public int PositionY;
        public int DisplayOrientation;
        public int DisplayFixedOutput;
        public short Color;
        public short Duplex;
        public short YResolution;
        public short TTOption;
        public short Collate;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string FormName;

        public short LogPixels;
        public int BitsPerPel;
        public int PelsWidth;
        public int PelsHeight;
        public int DisplayFlags;
        public int DisplayFrequency;
        public int ICMMethod;
        public int ICMIntent;
        public int MediaType;
        public int DitherType;
        public int Reserved1;
        public int Reserved2;
        public int PanningWidth;
        public int PanningHeight;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumDisplaySettings(string? deviceName, int modeNum, ref DevMode devMode);
}
