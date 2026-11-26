package com.airtouch.app.ui.redesign.camera

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX 图像分析器：从预览帧里识别二维码，解码结果按 [onDecoded] 回调。
 *
 * ## v1.7 修复：「有画面但扫不出」+「进主页几秒后闪退」是同一个 bug
 *
 * CameraX 的背压（`STRATEGY_KEEP_ONLY_LATEST`）**完全依赖 `ImageProxy.close()` 的时机**：
 * 上一帧没 close，下一帧就不会投递过来。旧实现把 Y 平面 copy 出来、立刻 `close()`，
 * 然后 `post` 到另一个 HandlerThread 上慢慢解码 —— 于是背压彻底失效：
 *
 *  - 相机 30fps 全速投递，解码线程只能做到 ~10fps（720p + TRY_HARDER 一帧几十毫秒）；
 *  - 任务队列**无界增长**，每个排队任务还各自握着一个 Y 平面 `ByteArray`；
 *  - 结果就是：① 永远在解码几秒前的旧帧，手机对着二维码也扫不出来；
 *    ② 堆积的帧缓冲吃光 Java 堆 → 进主页几秒后闪退，而且**分析分辨率越高崩得越快**
 *    （把 ImageAnalysis 抬到最高分辨率的那次「修复」让它从「扫不出」直接变成「秒闪退」）。
 *
 * 现在：先按 [minDecodeIntervalMs] 节流（被节流的帧连 copy 都不做），
 * 再**就地**解码，最后在 `finally` 里 `close()`。解码期间分析线程被占住，
 * 下一帧要么等它、要么被 KEEP_ONLY_LATEST 丢掉 —— 队列长度恒为 0，内存有上界。
 */
class QrCodeAnalyzer(
    /** 回调线程；所有回调都会 post 到这个 Handler 上，不占用分析线程 */
    private val callbackHandler: Handler,
    private val onDecoded: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val onFrame: (Int, Int) -> Unit = { _, _ -> },
    /** 解码节流间隔；0 表示每帧都尝试 */
    private val minDecodeIntervalMs: Long = DEFAULT_MIN_DECODE_INTERVAL_MS,
) : ImageAnalysis.Analyzer {

    /** 上一次已经回调过的内容，避免同一张码连续触发（也起一点节流作用） */
    @Volatile
    private var lastResult: String? = null

    /** 上一次触发时间，用于节流 */
    @Volatile
    private var lastEmitAt = 0L

    /** 上一次真正做解码的时间（仅分析线程读写） */
    private var lastDecodeAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0 || image.planes.isEmpty()) return

            val now = SystemClock.elapsedRealtime()
            if (minDecodeIntervalMs > 0 && now - lastDecodeAt < minDecodeIntervalMs) return
            lastDecodeAt = now

            val plane = image.planes[0]
            val text = QrFrameDecoder.decodeYPlane(
                buffer = plane.buffer,
                width = width,
                height = height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )

            callbackHandler.post { onFrame(width, height) }
            if (text != null) emit(text)
        } catch (t: Throwable) {
            // 绝不能把异常抛出去：analyze() 跑在 CameraX 的分析线程上，
            // 抛出去没人接就是整个进程崩掉。用 Throwable 连 OutOfMemoryError 一起挡。
            Log.w(TAG, "二维码分析异常", t)
            callbackHandler.post { onError(t) }
        } finally {
            // 唯一的 close 点，也**必须**在这里：这就是 CameraX 背压的开关。
            image.close()
        }
    }

    private fun emit(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastResult && now - lastEmitAt < REPEAT_INTERVAL_MS) {
            return
        }
        lastResult = text
        lastEmitAt = now
        Log.i(TAG, "识别到二维码：${text.take(48)}")
        callbackHandler.post { onDecoded(text) }
    }

    companion object {
        private const val TAG = "QrCodeAnalyzer"
        private const val REPEAT_INTERVAL_MS = 1500L

        /**
         * 解码节流：720p 全帧 + TRY_HARDER 一帧大约 10~40ms，
         * 约 8 次/秒已经远高于扫码需要的灵敏度，同时把 CPU / 发热压住。
         */
        private const val DEFAULT_MIN_DECODE_INTERVAL_MS = 120L
    }
}
