package com.airtouch.app.ui.redesign.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.EnumMap

/**
 * 二维码解码核心：**纯 JVM 实现，刻意不引用任何 `android.*` 类**。
 *
 * 拆出来的唯一目的是可离线自测：相机相关的 `QrCodeAnalyzer` 必须跑在真机上，
 * 但「Y 平面 → 亮度矩阵 → zxing → 文本」这一段是纯算法，可以拿合成帧在 JVM 里跑断言。
 * 这样在没有数据线 / 没有 logcat 的情况下也能证明解码链路本身是通的。
 *
 * 输入约定（CameraX `ImageAnalysis` 默认输出 `YUV_420_888`）：
 *  - 只取 plane[0]（Y 平面）；
 *  - Y 平面每行可能有 padding，所以必须按 [rowStride] / [pixelStride] 重新紧密排列；
 *  - 重新排列之后每行**恰好** width 字节，因此 `PlanarYUVLuminanceSource` 的
 *    `dataWidth` 必须传 width 而不是 rowStride —— 传错就会与紧密缓冲错位，二维码永远解不出来。
 */
object QrFrameDecoder {

    /**
     * 直接从 Y 平面缓冲区解出二维码文本；解不出（绝大多数帧）返回 null。
     *
     * @param buffer      `image.planes[0].buffer`
     * @param width       图像宽（像素）
     * @param height      图像高（像素）
     * @param rowStride   行跨度，含 padding
     * @param pixelStride 像素跨度，Y 平面通常为 1
     */
    fun decodeYPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): String? {
        val y = repackY(buffer, width, height, rowStride, pixelStride) ?: return null
        return decodeLuminance(y, width, height)
    }

    /** 把带 stride 的 Y 平面整理成 tightly packed（每行恰好 width 字节）的数组。 */
    fun repackY(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val limit = buffer.limit()
        if (limit <= 0) return null

        val ps = if (pixelStride <= 0) 1 else pixelStride
        val rs = if (rowStride <= 0) width * ps else rowStride
        val out = ByteArray(width * height)

        if (ps == 1) {
            // 最常见：Y 平面 pixelStride == 1，整行 memcpy 即可。
            // 用 duplicate() 是为了不影响调用方 buffer 的 position（CameraX 之后还要 close）。
            val dup = buffer.duplicate()
            var row = 0
            var outIndex = 0
            while (row < height) {
                val start = row * rs
                if (start < limit) {
                    val n = minOf(width, limit - start)
                    dup.position(start)
                    dup.get(out, outIndex, n)
                }
                // start >= limit：这一行越界，保持 0。source 之外的区域保持全黑，
                // 既不会让 zxing 误判，也不会因为越界读抛 IndexOutOfBounds。
                outIndex += width
                row++
            }
            return out
        }

        var outIndex = 0
        var row = 0
        while (row < height) {
            val rowStart = row * rs
            var col = 0
            while (col < width) {
                val src = rowStart + col * ps
                out[outIndex] = if (src >= 0 && src < limit) buffer.get(src) else 0
                outIndex++
                col++
            }
            row++
        }
        return out
    }

    /**
     * 紧密排列的亮度平面 → 文本。
     *
     * 提示集说明：
     *  - `TRY_HARDER`：手持、轻微失焦时多花点 CPU 也要扫出来；
     *  - `ALSO_INVERTED`：深色主题下服务端渲染的是「强调色模块 + 黑底」，
     *    遇到反相或强反光时正相解码会失败，这一项让它自动重试反相；
     *  - `POSSIBLE_FORMATS = QR_CODE`：配对码一定是 QR，排除其它码制可以省掉大量无用搜索。
     */
    fun decodeLuminance(y: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || y.size < width * height) return null

        val source = try {
            // dataWidth 必须等于紧密缓冲的每行字节数（也就是 width）。
            PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
        hints[DecodeHintType.ALSO_INVERTED] = java.lang.Boolean.TRUE
        hints[DecodeHintType.POSSIBLE_FORMATS] = arrayListOf(BarcodeFormat.QR_CODE)
        reader.setHints(hints)

        return try {
            reader.decode(bitmap, hints).text
        } catch (e: Exception) {
            // NotFoundException / FormatException / ChecksumException 都是常态：
            // 一秒钟里绝大多数帧都没有二维码，这里绝不打日志也不上抛。
            null
        } finally {
            reader.reset()
        }
    }
}
