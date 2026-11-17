package com.airtouch.app.control

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.airtouch.app.net.AirTouchClient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「画圆测试」的发送端（协议 §2.4）。
 *
 * 用途：手机一边动一边卡的时候，光看光标分不清是**手机没发**、**Wi-Fi 丢包**还是
 * **Windows 拒绝了输入注入**。这个测试把可控的已知轨迹按固定频率发出去，PC 端用 seq 统计
 * 丢失/重复/乱序/到达间隔，手机上再对比「我发了多少 / 电脑收到多少」，三个原因立刻分开。
 *
 * 轨迹按用户的要求设计（两个圆各自贴住三条边）：
 * - 半径 `r = 屏幕高/2`；
 * - 圆 1 圆心 `(r, 高/2)`：同时与 **左边界、上边界、下边界** 相切；
 * - 圆 2 圆心 `(宽-r, 高/2)`：同时与 **右边界、上边界、下边界** 相切。
 *
 * 两种模式：
 * - 绝对（`moveto` → PC 端 SetCursorPos）：不受 Windows 指针加速影响，轨迹与手机算出来的
 *   完全一致，适合判断「有没有丢帧/卡顿」；
 * - 相对（`move`）：走正常触摸板的路径，会叠加指针加速，适合对比「同样的数据走两条通路」。
 *
 * 线程：整个发送循环跑在专用的 [HandlerThread] 上（不是主线程、也不是 OkHttp 线程），
 * 所以界面卡顿不会连带把测试节奏搞乱 —— 这也是「手机动光标不动」的一个关键修复点。
 */
class CircleTestRunner(
    private val client: AirTouchClient,
    private val listener: Listener
) {

    interface Listener {
        fun onCircleTestStarted(planned: Int, mode: String, targetHz: Int, circleMs: Int, width: Int, height: Int)

        fun onCircleTestProgress(circle: Int, sent: Int, planned: Int, actualHz: Double, maxSendGapMs: Int)

        fun onCircleTestFinished(
            sent: Int,
            planned: Int,
            elapsedMs: Long,
            maxSendGapMs: Int,
            firstSeq: Int,
            lastSeq: Int
        )

        fun onCircleTestAborted(reason: String)
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val token = Any()

    private var planned = 0
    private var samplesPerCircle = 0
    private var targetHz = 0
    private var circleMs = 0
    private var mode = "abs"
    private var width = 0
    private var height = 0

    private var seq = 0
    private var sent = 0
    private var circleIndex = 0
    private var startUptimeMs = 0L
    private var lastSendNanos = 0L
    private var maxSendGapMs = 0
    private var aborted = false

    private var lastTargetX = 0.0
    private var lastTargetY = 0.0
    private var remainderX = 0.0
    private var remainderY = 0.0

    private val tick = object : Runnable {
        override fun run() = tickOnce()
    }

    /**
     * 开始测试。
     * @param mode `abs`（绝对定位 moveto）或 `rel`（相对位移 move）
     * @param targetHz 目标频率（30–240）
     * @param circleMs 每圈耗时（毫秒）
     * @param width 电脑主屏宽度（来自 welcome）
     * @param height 电脑主屏高度（来自 welcome）
     */
    fun start(mode: String, targetHz: Int, circleMs: Int, width: Int, height: Int) {
        if (isRunning) return

        this.mode = if (mode == "rel") "rel" else "abs"
        this.targetHz = targetHz.coerceIn(30, 240)
        this.circleMs = circleMs.coerceIn(400, 20_000)
        this.width = if (width > 0) width else 1920
        this.height = if (height > 0) height else 1080

        samplesPerCircle = maxOf(16, (this.circleMs / 1000.0 * this.targetHz).roundToInt())
        planned = samplesPerCircle * 2

        seq = 0
        sent = 0
        circleIndex = 0
        maxSendGapMs = 0
        aborted = false
        lastSendNanos = 0L
        remainderX = 0.0
        remainderY = 0.0

        val startPoint = pointAt(0)
        lastTargetX = startPoint.first
        lastTargetY = startPoint.second

        // 让 PC 端知道测试开始（它会开始按 seq 记账、记录起始光标位置）
        client.sendDiagBegin("circle", this.mode, planned, this.targetHz, this.circleMs)

        val ht = HandlerThread("airtouch-circle-test")
        ht.start()
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        isRunning = true
        startUptimeMs = SystemClock.uptimeMillis()
        listener.onCircleTestStarted(planned, this.mode, this.targetHz, this.circleMs, this.width, this.height)

        h.postAtTime(tick, token, startUptimeMs)
    }

    /** 用户中途停止 / 界面不可见 / 连接断开时调用。不会发 diag_report。 */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        aborted = true
        handler?.removeCallbacksAndMessages(token)
        thread?.quitSafely()
        handler = null
        thread = null
    }

    /** 圆上第 [index] 个采样点（两点一线，1 圈 → 2 圈连续画）。 */
    private fun pointAt(index: Int): Pair<Double, Double> {
        val circle = if (index < samplesPerCircle) 0 else 1
        val local = index - circle * samplesPerCircle
        val t = local.toDouble() / samplesPerCircle
        val angle = 2.0 * PI * t
        // 半径 = 高/2，圆心贴在左/右边缘 → 圆同时相切于上、下以及左（或右）边界
        val r = height / 2.0
        val cx = if (circle == 0) r else width - r
        val cy = height / 2.0
        return Pair(cx + r * cos(angle), cy + r * sin(angle))
    }

    private fun tickOnce() {
        if (!isRunning) return

        val nowNanos = System.nanoTime()
        if (lastSendNanos != 0L) {
            val gapMs = ((nowNanos - lastSendNanos) / 1_000_000L).toInt()
            if (gapMs > maxSendGapMs) maxSendGapMs = gapMs
        }
        lastSendNanos = nowNanos

        val index = sent
        val point = pointAt(index)
        val ok = if (mode == "abs") {
            client.sendMoveTo(point.first.roundToInt(), point.second.roundToInt(), seq + 1)
        } else {
            // 相对模式：把浮点轨迹累加成整数步长，余量留到下一帧，避免「慢速移动被四舍五入吃掉」
            remainderX += point.first - lastTargetX
            remainderY += point.second - lastTargetY
            lastTargetX = point.first
            lastTargetY = point.second
            val stepX = remainderX.roundToInt()
            val stepY = remainderY.roundToInt()
            remainderX -= stepX
            remainderY -= stepY
            client.sendMove(stepX, stepY, seq + 1)
        }

        if (!ok) {
            // 发送失败（连接断了）：直接中止，由 Activity 的断线回调负责提示
            finish(abortedByConnection = true)
            return
        }

        seq++
        sent++
        circleIndex = if (sent <= samplesPerCircle) 0 else 1

        if (sent % 10 == 0 || sent == planned) {
            val elapsed = (SystemClock.uptimeMillis() - startUptimeMs).coerceAtLeast(1L)
            listener.onCircleTestProgress(circleIndex, sent, planned, sent * 1000.0 / elapsed, maxSendGapMs)
        }

        if (sent >= planned) {
            finish(abortedByConnection = false)
            return
        }

        // 用「绝对时间点」而不是每次 delay(period)，避免误差累积导致实际频率越跑越低
        val nextAt = startUptimeMs + ((sent) * 1000.0 / targetHz).toLong()
        handler?.postAtTime(tick, token, nextAt)
    }

    private fun finish(abortedByConnection: Boolean) {
        val sentCount = sent
        val elapsedMs = (SystemClock.uptimeMillis() - startUptimeMs).coerceAtLeast(0L)
        val firstSeq = if (sentCount > 0) 1 else 0
        val lastSeq = seq
        val gap = maxSendGapMs
        val wasAborted = aborted

        isRunning = false
        handler?.removeCallbacksAndMessages(token)
        thread?.quitSafely()
        handler = null
        thread = null

        if (abortedByConnection) {
            listener.onCircleTestAborted("连接已断开，测试中止（已发 $sentCount 帧）")
            return
        }

        if (wasAborted) {
            listener.onCircleTestAborted("测试已取消")
            return
        }

        // 把手机侧的自测数据交回 PC，PC 会把两边数据合起来给出结论
        client.sendDiagReport(
            testKind = "circle",
            mode = mode,
            sent = sentCount,
            planned = planned,
            firstSeq = firstSeq,
            lastSeq = lastSeq,
            elapsedMs = elapsedMs,
            targetHz = targetHz,
            circleMs = circleMs,
            maxSendGapMs = gap
        )

        listener.onCircleTestFinished(sentCount, planned, elapsedMs, gap, firstSeq, lastSeq)
    }
}
