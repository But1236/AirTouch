using QRCoder;

namespace AirTouch.Core;

/// <summary>Renders the pairing URL as a PNG QR code (via QRCoder's System.Drawing-free renderer).</summary>
public static class QrCodeRenderer
{
    /// <summary>Error correction level M keeps the code small while tolerating a scuffed phone camera.</summary>
    private const QRCodeGenerator.ECCLevel Level = QRCodeGenerator.ECCLevel.M;

    public static byte[] RenderPng(string payload, int pixelsPerModule = 8)
    {
        if (string.IsNullOrWhiteSpace(payload))
        {
            throw new ArgumentException("QR 内容不能为空", nameof(payload));
        }

        pixelsPerModule = Math.Clamp(pixelsPerModule, 2, 24);

        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, Level);
        var code = new PngByteQRCode(data);
        return code.GetGraphic(pixelsPerModule);
    }

    /// <summary>
    /// Renders the code at the <em>largest whole number</em> of pixels per module that still fits into
    /// <paramref name="maxPixels"/>.
    /// </summary>
    /// <remarks>
    /// Why this exists: the window used to render a fixed 8 px/module PNG and then let WPF scale it down
    /// to whatever space was left. The v2 pairing URL is much longer than v1's, so the symbol gained
    /// modules, and a downscaled QR both loses resolution and — depending on the leftover room — can be
    /// squeezed to well under its intended size. Rendering at an exact integer scale and displaying it
    /// 1:1 keeps every module square and identically sized, which is what a phone camera needs.
    /// </remarks>
    public static byte[] RenderPngForWidth(string payload, int maxPixels, out int pixelsPerModule, out int moduleCount)
    {
        if (string.IsNullOrWhiteSpace(payload))
        {
            throw new ArgumentException("QR 内容不能为空", nameof(payload));
        }

        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, Level);

        // PngByteQRCode renders exactly ModuleMatrix.Count modules wide (no extra quiet zone), which is
        // also what GetGraphic(pixelsPerModule) returns: the PNG edge is Count × pixelsPerModule.
        // The on-screen quiet zone comes from the white border the window draws around the image.
        moduleCount = data.ModuleMatrix.Count;
        pixelsPerModule = Math.Clamp(maxPixels / Math.Max(1, moduleCount), 2, 24);

        var code = new PngByteQRCode(data);
        return code.GetGraphic(pixelsPerModule);
    }
}
