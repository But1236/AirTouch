package com.airtouch.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.airtouch.app.R

/**
 * 一字滑条：只剩左右两个方向的滑条（取代原来的十字摇杆条）。
 *
 * ## 为什么砍掉上下
 * 原来的 `-+-` 十字条要做四个方向，竖向只有几毫米可分配 —— 想点左右却滑出了上下是高频误触，
 * 而且「十字」这个形状本身也在暗示四个方向都值得按。现在纵向**完全不参与判定**：
 * 手指在滑条上怎么上下蹭都不会产生任何事件，只有横向位置决定按的是 ← 还是 →。
 * 竖屏因此**没有上下方向键**（按用户要求把功能栏里的 ↑/↓ 键帽也去掉了）；要按上下就用横屏
 * 104 键里的方向区。
 *
 * ## 三种操作方式（与原来一致）
 * | 操作 | 行为 |
 * | --- | --- |
 * | 点左半 / 右半 | **立刻**触发该方向 |
 * | 按住不放 | 首次立刻触发，[REPEAT_START_DELAY_MS] 后按 [REPEAT_INTERVAL_MS] 连发 |
 * | 按住横向划过中线 | 方向跟着切过去，并立刻补一次触发 |
 *
 * ## 中线滞回
 * 换方向必须越过中线 [CENTER_HYSTERESIS_DP] 才算数。没有这段滞回的话，手指停在中线上抖动
 * 会左右反复切换，而每次切换都要「立刻补一次触发」—— 那就变成了一串完全失控的方向键。
 *
 * ## 与键盘的关系
 * 本 View **不认识键码**：它只产出「方向」，方向 → 虚拟键码的映射在
 * [KeyboardInputHandler] 里。这样滑条可以脱离键盘单独复用。
 */
class OneLineDPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Direction { LEFT, RIGHT }

    interface Listener {
        /** 开始持续触发某个方向（这一次是立即触发，调用方应立刻发一次按键） */
        fun onDirectionStart(direction: Direction)

        /** 持续触发的中间节拍 */
        fun onDirectionRepeat(direction: Direction)

        /** 该方向结束（抬手 / 换方向 / 手势取消） */
        fun onDirectionEnd(direction: Direction)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    /** sp → px；用 applyDimension 而不是已废弃的 `displayMetrics.scaledDensity`（见 KeyboardView） */
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val colorTrack = color(R.color.at_key_bg_modifier)
    private val colorTrackActive = color(R.color.at_key_bg_pressed)
    private val colorThumb = color(R.color.at_key_bg)
    private val colorThumbActive = color(R.color.at_key_bg_locked)
    private val colorStroke = color(R.color.at_button_stroke)
    private val colorGlyph = color(R.color.at_text_dim)
    private val colorGlyphActive = color(R.color.at_on_primary)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = colorStroke
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
        color = colorGlyph
    }

    private val outerRect = RectF()
    private val activeRect = RectF()
    private val thumbRect = RectF()

    /** 当前正在触发的方向；null 表示没有 */
    private var activeDirection: Direction? = null

    private var gestureActive = false

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatTicked = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val direction = activeDirection ?: return
            if (!repeatTicked) {
                // 连发开始的信号：手感上必须能分辨「按了一下」和「开始连发」
                repeatTicked = true
                haptic(HapticFeedbackConstants.LONG_PRESS)
            }
            listener?.onDirectionRepeat(direction)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    // ---------------- 绘制 ----------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = if (wMode == MeasureSpec.UNSPECIFIED) dp(200f).toInt() else MeasureSpec.getSize(widthMeasureSpec)
        val h = if (hMode == MeasureSpec.UNSPECIFIED) dp(40f).toInt() else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    /** 滑轨的粗细：矮胖的条上不能画出一根牙签，所以同时受高度和宽度约束 */
    private val trackThickness: Float
        get() = minOf(height * 0.36f, dp(16f))

    /** 把手的直径：比滑轨粗一圈，看上去才「抓得住」 */
    private val thumbSize: Float
        get() = minOf(height * 0.72f, dp(30f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val inset = dp(1.5f)
        outerRect.set(inset, inset, width - inset, height - inset)

        val cx = outerRect.centerX()
        val cy = outerRect.centerY()
        val half = trackThickness / 2f
        val radius = dp(6f)

        // 底板整条铺满：它同时是「触摸区就在这一行」的视觉提示
        fillPaint.color = colorTrack
        canvas.drawRoundRect(outerRect, dp(8f), dp(8f), fillPaint)

        // 激活的那半条用强调色点亮，一眼能看出现在按的是哪边
        val active = activeDirection
        if (active != null) {
            fillPaint.color = colorTrackActive
            activeRect.set(
                if (active == Direction.LEFT) outerRect.left else cx,
                cy - half,
                if (active == Direction.LEFT) cx else outerRect.right,
                cy + half,
            )
            canvas.drawRoundRect(activeRect, radius, radius, fillPaint)
        }

        // 中线上的把手：一字滑条的「把手」必须只有横向含义，所以画成圆角方块而不是十字
        val thumb = thumbSize
        fillPaint.color = if (active != null) colorThumbActive else colorThumb
        thumbRect.set(cx - thumb / 2f, cy - thumb / 2f, cx + thumb / 2f, cy + thumb / 2f)
        canvas.drawRoundRect(thumbRect, dp(7f), dp(7f), fillPaint)

        canvas.drawRoundRect(outerRect, dp(8f), dp(8f), strokePaint)

        val baselineOffset = -(glyphPaint.ascent() + glyphPaint.descent()) / 2f
        glyphPaint.textSize = sp(13f)
        glyphPaint.color = if (active == Direction.LEFT) colorGlyphActive else colorGlyph
        canvas.drawText("←", (outerRect.left + cx - thumb / 2f) / 2f, cy + baselineOffset, glyphPaint)
        glyphPaint.color = if (active == Direction.RIGHT) colorGlyphActive else colorGlyph
        canvas.drawText("→", (cx + thumb / 2f + outerRect.right) / 2f, cy + baselineOffset, glyphPaint)
    }

    // ---------------- 触摸 ----------------

    /**
     * 按「横向位置」判断方向；**完全不看 y**（这正是误触的根治办法）。
     *
     * 已经有方向时用滞回：越过中线还要再多走 [CENTER_HYSTERESIS_DP] 才允许换边。
     */
    private fun directionAt(x: Float): Direction {
        val cx = width / 2f
        val hysteresis = dp(CENTER_HYSTERESIS_DP)
        return when (activeDirection) {
            Direction.LEFT -> if (x > cx + hysteresis) Direction.RIGHT else Direction.LEFT
            Direction.RIGHT -> if (x < cx - hysteresis) Direction.LEFT else Direction.RIGHT
            else -> if (x < cx) Direction.LEFT else Direction.RIGHT
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureActive = true
                // 点到哪一半就立刻触发哪一边，不需要先滑出阈值
                activate(directionAt(event.x))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureActive) return true
                val target = directionAt(event.x)
                if (target !== activeDirection) {
                    // 换方向：立刻补一次触发，横划因此是「连续」的
                    activate(target)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                deactivate()
                gestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun activate(direction: Direction) {
        stopRepeat()
        activeDirection = direction
        repeatTicked = false
        invalidate()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        // 首次触发由 onDirectionStart 承担（调用方在那里立刻发一次按键），随后进入连发节奏
        listener?.onDirectionStart(direction)
        repeatHandler.postDelayed(repeatRunnable, REPEAT_START_DELAY_MS)
    }

    private fun deactivate() {
        val direction = activeDirection ?: return
        stopRepeat()
        activeDirection = null
        invalidate()
        listener?.onDirectionEnd(direction)
    }

    private fun stopRepeat() {
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    private fun haptic(feedback: Int) {
        try {
            performHapticFeedback(feedback)
        } catch (e: RuntimeException) {
            // 没有震动器 / 关闭触感反馈时不要影响方向键本身
        }
    }

    override fun onDetachedFromWindow() {
        deactivate()
        gestureActive = false
        super.onDetachedFromWindow()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    companion object {
        /** 换方向要越过中线多少才算数（防中线抖动导致左右疯狂切换） */
        const val CENTER_HYSTERESIS_DP = 6f

        /** 连发前的停顿：比键盘的连发稍长一点，方向键一下一下走更符合直觉 */
        const val REPEAT_START_DELAY_MS = 320L

        /** 连发间隔：约 14 次/秒 */
        const val REPEAT_INTERVAL_MS = 70L
    }
}
