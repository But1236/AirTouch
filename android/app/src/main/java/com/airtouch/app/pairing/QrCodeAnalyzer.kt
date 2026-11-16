package com.airtouch.app.pairing

import android.os.Handler
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/**
 * CameraX 图像分析器：从预览帧里识别二维码。
 *
 * 实现要点：
 * - 只使用 Y 平面（YUV_420_888 的 plane[0]）构造灰度源；
 * - **正确处理 rowStride / pixelStride**：先把 Y 数据拷成紧密排列的 ByteArray
 *   （每行只取 width 个字节，忽略 padding），再构造 [PlanarYUVLuminanceSource]。
 *   如果不做这一步，带 stride 的相机缓冲会让图像错位，二维码永远解不出来；
 * - 解码跑在 [callbackHandler] 指向的后台线程（由 PairingActivity 提供 HandlerThread）；
 *   CameraX 的 Analyzer 回调本身也在该 Executor 上，这里再用 post 只是为了把「解码」
 *   与「图像生命周期管理」明确分开，逻辑更直白；
 * - [ImageProxy.close] 严格且仅调用一次：要么在采样时立即关闭，要么在解码结束后关闭；
 * - 使用 DecodeHintType.TRY_HARDER 提高识别率（代价是 CPU）。
 */
class QrCodeAnalyzer(
    private val callbackHandler: Handler,
    private val onDecoded: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer {

    /** 上一次已经回调过的内容，避免同一张码连续触发（也起一点节流作用） */
    @Volatile
    private var lastResult: String? = null

    /** 上一次触发时间，用于节流 */
    @Volatile
    private var lastEmitAt = 0L

    override fun analyze(image: ImageProxy) {
        if (image.planes.isEmpty()) {
            image.close()
            return
        }

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            image.close()
            return
        }

        // ---- 1. 取 Y 平面并整理成紧密排列 ----
        val yBytes: ByteArray
        val rowStride: Int
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            buffer.rewind()
            val remaining = buffer.remaining()
            if (remaining <= 0) {
                image.close()
                return
            }
            val raw = ByteArray(remaining)
            buffer.get(raw)
            yBytes = repackY(raw, width, height, rowStride, pixelStride)
        } catch (e: Exception) {
            Log.w(TAG, "读取 Y 平面失败", e)
            image.close()
            return
        }

        val cropWidth = width
        val cropHeight = height

        // 图像数据已经拷贝出来，可以立刻释放 ImageProxy（只关一次）
        image.close()

        callbackHandler.post {
            try {
                val text = decode(yBytes, cropWidth, cropHeight)
                if (text != null) {
                    emit(text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "解码异常", e)
                onError(e)
            }
        }
    }

    /**
     * 把带 stride 的 Y 平面整理为 tightly packed 数组。
     * pixelStride 理论上是 1，但按通用写法处理（!=1 时按下标取值）。
     */
    private fun repackY(raw: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val needed = rowStride * height
        // 大多数设备下 raw.size 正好是 rowStride*height，且与 width*height 相等时可直接复用
        if (pixelStride == 1 && rowStride == width && raw.size >= width * height) {
            return raw
        }
        val out = ByteArray(width * height)
        val safeRowStride = if (rowStride <= 0) width * pixelStride else rowStride
        var outIndex = 0
        var row = 0
        while (row < height) {
            val rowStart = row * safeRowStride
            var col = 0
            while (col < width) {
                val src = rowStart + col * pixelStride
                out[outIndex] = if (src in raw.indices) raw[src] else 0
                outIndex++
                col++
            }
            row++
        }
        if (needed <= 0) {
            Log.w(TAG, "可疑的 rowStride=$rowStride height=$height")
        }
        return out
    }

    private fun decode(yBytes: ByteArray, width: Int, height: Int): String? {
        // repackY() 已把 Y 平面整理成紧密排列（每行恰好 width 字节），所以 dataWidth 必须传 width：
        // 如果传相机的 rowStride（含 padding）就会与紧密缓冲不匹配，二维码永远解不出来。
        // 裁剪窗口本来就是整幅图（0,0,width,height），无需再调用 crop()。
        val source = try {
            PlanarYUVLuminanceSource(
                yBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
        } catch (e: Exception) {
            Log.w(TAG, "构造 LuminanceSource 失败", e)
            return null
        }

        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = java.lang.Boolean.TRUE
        // 只找二维码，减少无用尝试（TRY_HARDER 只影响 1D 顺序，这里显式限定格式）
        hints[DecodeHintType.POSSIBLE_FORMATS] =
            java.util.ArrayList<BarcodeFormat>(listOf(BarcodeFormat.QR_CODE))
        reader.setHints(hints)
        return try {
            val result = reader.decode(bitmap, hints)
            result.text
        } catch (e: Exception) {
            // NotFoundException 是常态，不打日志避免刷屏
            null
        } finally {
            reader.reset()
        }
    }

    private fun emit(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastResult && now - lastEmitAt < REPEAT_INTERVAL_MS) {
            return
        }
        lastResult = text
        lastEmitAt = now
        onDecoded(text)
    }

    companion object {
        private const val TAG = "QrCodeAnalyzer"
        private const val REPEAT_INTERVAL_MS = 1500L
    }
}
