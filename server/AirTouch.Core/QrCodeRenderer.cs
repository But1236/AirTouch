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
}
