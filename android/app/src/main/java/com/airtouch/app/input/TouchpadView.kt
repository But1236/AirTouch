package com.airtouch.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 触摸板自定义 View。
 *
 * 所有坐标换算都以 **dp** 为单位（内部用 density 换算），因此灵敏度与屏幕密度无关。
 *
 * 手势 → 协议指令（协议 §2.1）：
 * - 单指拖动            → [Listener.onMove]（`move`，dx/dy 右下为正）
 *   dx = (x - lastX) / density * BASE_SENSITIVITY * sensitivity，四舍五入取整，
 *   **余数累积**到 pendingDx/pendingDy，因此慢速拖动不会因为取整而丢步。
 * - 单指轻点（<200ms 且位移 < 12dp） → [Listener.onClick]("left")
 * - 双指拖动            → [Listener.onScroll]，按两指平均 Y 的位移累积，
 *   每满 ~40dp 产生 1 个单位滚轮。
 *   **符号约定**：手指向下拖（屏幕 Y 增大）应该让内容向下滚，即 Windows 滚轮的
 *   负方向；协议 §2.1 明确「正数上滚，负数下滚」，所以这里取负号：
 *   amount = -round(accumulatedScrollUnits)。即手指下滑 → amount < 0 → 下滚。
 * - 双指轻点            → [Listener.onClick]("right")（体验优化，协议允许 click button=right）
 * - 滚轮模式开启时：单指拖动改为发 `scroll`（同样按 40dp 一个单位、下滑为负）。
 *
 * View 自身：
 * - `isClickable = true` / `isFocusable = true`，保证触摸事件能到达；
 * - 轻量 onDraw 画网格与中心十字，让触摸区在深色主题下可见（不做动画，避免无谓重绘）。
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), View.OnTouchListener {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onClick(button: String)
        fun onScroll(amount: Int)
    }

    var listener: Listener? = null

    /** 灵敏度倍数（0.5x ~ 3.0x，由 SeekBar 控制） */
    var sensitivity: Float = 1.0f
        set(value) {
            field = if (value.isNaN()) 1.0f else value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
            // 刻意不重置余数累加器：灵敏度是连续量，改动时保留余数更符合直觉
        }

    /** 滚轮模式：单指拖动 = 滚动 */
    var scrollMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // 模式切换时重置手势状态，避免上一个手势的余数被错误解释
                resetGestureState()
            }
        }

    private val density: Float = resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(0x22, 0xFF, 0xFF, 0xFF)
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(0x55, 0xFF, 0xFF, 0xFF)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(0x66, 0x4C, 0x8D, 0xFF)
    }

    // ---- 手势状态 ----
    private var active = false
    private var pointerCountInGesture = 0
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var totalMoveDp = 0f
    private var twoFingerCandidate = false
    private var twoFingerDownTime = 0L
    private var twoFingerMoveDp = 0f
    private var twoFingerLastAvgY = 0f

    /** move 的小数余数累加器（dp 换算后的像素小数） */
    private var pendingDx = 0f
    private var pendingDy = 0f

    /** scroll 的小数余数累加器（单位：滚轮格） */
    private var pendingScroll = 0f

    init {
        isClickable = true
        isFocusable = true
        setOnTouchListener(this)
    }

    // ---------------- 触摸处理 ----------------

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                pointerCountInGesture = 1
                downTime = event.eventTime
                lastX = event.x
                lastY = event.y
                totalMoveDp = 0f
                twoFingerCandidate = false
                twoFingerMoveDp = 0f
                resetRemainders()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // 从单指切到双指：重置基准，避免把单指的位移算进滚动
                    pointerCountInGesture = 2
                    twoFingerCandidate = true
                    twoFingerDownTime = event.eventTime
                    twoFingerMoveDp = 0f
                    twoFingerLastAvgY = averageY(event)
                    totalMoveDp = 0f
                    resetRemainders()
                } else {
                    pointerCountInGesture = event.pointerCount
                    twoFingerCandidate = false
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 有一根手指抬起：结束双指手势，但保留 candidate 以便在 ACTION_UP 判定双指轻点
                if (event.pointerCount == 2) {
                    // 双指轻点的容差判定：抬起时累计位移仍要小于阈值
                    if (twoFingerMoveDp > TAP_MAX_MOVE_DP) {
                        twoFingerCandidate = false
                    }
                }
                pointerCountInGesture = event.pointerCount - 1
                resetRemainders()
                // 重设单指基准，避免抬起瞬间产生跳变
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                if (event.pointerCount >= 2) {
                    handleTwoFingerMove(event)
                } else if (event.pointerCount == 1) {
                    handleOneFingerMove(event)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasTwoFingerTap = twoFingerCandidate &&
                    event.eventTime - twoFingerDownTime < TAP_MAX_DURATION_MS &&
                    twoFingerMoveDp < TAP_MAX_MOVE_DP

                if (wasTwoFingerTap) {
                    listener?.onClick(ButtonCodes.RIGHT)
                } else if (pointerCountInGesture <= 1) {
                    val duration = event.eventTime - downTime
                    if (duration < TAP_MAX_DURATION_MS && totalMoveDp < TAP_MAX_MOVE_DP) {
                        listener?.onClick(ButtonCodes.LEFT)
                    }
                }
                resetGestureState()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetGestureState()
                return true
            }
        }
        return false
    }

    private fun handleOneFingerMove(event: MotionEvent) {
        // 关键修复（v2）：Android 会把短时间内的多个 MOVE 采样**合并**成一个事件
        // （UI 线程一忙就合并得更多），只读 event.x/event.y 会把中间采样点整段丢掉，
        // 现象就是「手指一直在动、光标却一顿一顿」。遍历历史采样点逐个累加，
        // 光标轨迹才和手指一致，且总的位移量不会因为帧率下降而变少。
        val history = event.historySize
        if (history > 0) {
            for (h in 0 until history) {
                processOneFingerPoint(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
            }
        }
        processOneFingerPoint(event.x, event.y)
    }

    private fun processOneFingerPoint(x: Float, y: Float) {
        val rawDxPx = x - lastX
        val rawDyPx = y - lastY
        lastX = x
        lastY = y

        val dxDp = rawDxPx / density
        val dyDp = rawDyPx / density
        totalMoveDp += hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat()

        val scale = BASE_SENSITIVITY * sensitivity
        if (scrollMode) {
            // 滚轮模式：只取纵向分量，40dp = 1 格；手指下滑 → 负 amount（下滚）
            pendingScroll += -dyDp / SCROLL_DP_PER_UNIT * sensitivity
            val whole = pendingScroll.toInt()
            if (whole != 0) {
                pendingScroll -= whole.toFloat()
                listener?.onScroll(whole)
            }
        } else {
            pendingDx += dxDp * scale
            pendingDy += dyDp * scale
            val ix = pendingDx.roundToInt()
            val iy = pendingDy.roundToInt()
            if (ix != 0 || iy != 0) {
                pendingDx -= ix.toFloat()
                pendingDy -= iy.toFloat()
                listener?.onMove(ix, iy)
            }
        }
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        // 与单指同理：合并事件里的历史采样点也要逐个吃掉，否则双指滚动同样会顿
        val history = event.historySize
        if (history > 0) {
            for (h in 0 until history) {
                processTwoFingerPoint(averageYAt(event, h))
            }
        }
        processTwoFingerPoint(averageY(event))
    }

    private fun processTwoFingerPoint(avgY: Float) {
        val dyPx = avgY - twoFingerLastAvgY
        twoFingerLastAvgY = avgY

        val dyDp = dyPx / density
        twoFingerMoveDp += abs(dyDp)
        if (twoFingerMoveDp > TAP_MAX_MOVE_DP) {
            twoFingerCandidate = false
        }

        // 双指滚动：与滚轮模式相同的换算与符号（40dp = 1 格，下滑为负 = 下滚）
        pendingScroll += -dyDp / SCROLL_DP_PER_UNIT * sensitivity
        val whole = pendingScroll.toInt()
        if (whole != 0) {
            pendingScroll -= whole.toFloat()
            listener?.onScroll(whole)
        }
    }

    private fun averageY(event: MotionEvent): Float = averageYAt(event, -1)

    /**
     * 取前两根手指平均 Y。
     * @param historicalIndex -1 表示取当前值，>= 0 表示取第 index 个历史采样点
     */
    private fun averageYAt(event: MotionEvent, historicalIndex: Int): Float {
        var sum = 0f
        val n = if (event.pointerCount > 2) 2 else event.pointerCount
        if (n <= 0) return 0f
        for (i in 0 until n) {
            sum += if (historicalIndex < 0) event.getY(i)
            else event.getHistoricalY(i, historicalIndex)
        }
        return sum / n
    }

    private fun resetRemainders() {
        pendingDx = 0f
        pendingDy = 0f
        pendingScroll = 0f
    }

    private fun resetGestureState() {
        active = false
        pointerCountInGesture = 0
        totalMoveDp = 0f
        twoFingerCandidate = false
        twoFingerMoveDp = 0f
        resetRemainders()
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 网格
        val step = GRID_STEP_DP * density
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }

        // 中心十字
        val cx = w / 2f
        val cy = h / 2f
        val arm = 14f * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)

        // 边框
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRect(inset, inset, w - inset, h - inset, borderPaint)
    }

    /** 按钮名常量，和协议 §2.1 的 button 取值一致 */
    object ButtonCodes {
        const val LEFT = "left"
        const val RIGHT = "right"
    }

    companion object {
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 3.0f

        /** 基准灵敏度：1dp 手指位移对应 1.4 个屏幕像素（Windows 指针），再乘以用户灵敏度 */
        const val BASE_SENSITIVITY = 1.4f

        /** 双指/滚轮模式下，竖向每 40dp 记 1 格滚轮 */
        const val SCROLL_DP_PER_UNIT = 40f

        /** 轻点判定：按下到抬起不超过 200ms */
        const val TAP_MAX_DURATION_MS = 200L

        /** 轻点判定：总位移不超过 12dp */
        const val TAP_MAX_MOVE_DP = 12f

        private const val GRID_STEP_DP = 32f
    }
}
