package com.airtouch.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.airtouch.app.R
import com.airtouch.app.input.keyboard.KeyAction
import com.airtouch.app.input.keyboard.KeyKind
import com.airtouch.app.input.keyboard.KeySpec
import com.airtouch.app.input.keyboard.KeyboardLayout
import com.airtouch.app.input.keyboard.KeyboardLayouts
import com.airtouch.app.input.keyboard.StickyKeys
import kotlin.math.abs

/**
 * 自绘的模拟键盘阵列。
 *
 * ## 为什么不用一堆 `Button`
 * 104 键需要 104 个 View：inflate 一次就是几百毫秒，而且每个按键的圆角、按下态、粘滞高亮都要
 * 各自维护一份 drawable。这里用一个 View 按 [KeyboardLayout] 的数据画出整张键帽图 ——
 * 换布局只是换一份数据（工程要求 3：布局数据与 UI 渲染分离）。
 *
 * ## 触摸语义（与真实键盘对齐）
 * - **按下即发 `KEY_DOWN`**（不等抬手）：`KEY_DOWN`/`KEY_UP` 是两个独立事件，按下瞬间就发出去
 *   才有最低的输入延迟，这也是长按连发与游戏场景的基础。
 * - **修饰键与动作键在抬手时才生效**：这样可以「按错了滑出去取消」，而不会误锁 Ctrl 或误切布局。
 * - **支持在键面之间滑动**：手指从一个键滑到另一个键，会先抬起旧键再按下新键（真实键盘的手感）；
 *   但只有位移超过 [SLIDE_SLOP_DP] 才算滑动，避免指尖轻微抖动导致按错键。
 * - **长按连发**：`repeat` 的键（退格/删除/方向键）在 [REPEAT_START_DELAY_MS] 后开始按
 *   [REPEAT_INTERVAL_MS] 的节奏连发，并给一次 `LONG_PRESS` 触觉反馈表示「连发已开始」。
 * - **多指同时按键**：每一根手指各自维护「按着哪个键」，所以「一根手指按住 Shift、另一根敲字母」
 *   这种真键盘上最基本的操作是成立的 —— 这也是 Shift / Alt / Win 不做粘滞之后**唯一**能打出
 *   大写的办法（粘滞化的键单击什么都不发，等于永远按不下去）。
 *
 * ## 防误触
 * - 本 View 无条件消费自己的全部触摸事件（包括没命中任何按键时的滑动），因此事件不会穿透到
 *   下层的工具栏/导航栏；
 * - 按下时 `requestDisallowInterceptTouchEvent(true)`，父容器（滚动视图、ViewPager）不会把
 *   手势抢走；
 * - API 29+ 把整片键盘区域加入**系统手势排除区**（`systemGestureExclusionRects`），
 *   这样在键盘上从屏幕边缘划过不会触发「返回」而把整个界面退掉。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 一次按键事件的阶段 */
    enum class Phase {
        /** 按下 */
        DOWN,

        /** 抬起 */
        UP,

        /**
         * 长按连发的一次中间节拍。
         *
         * 单独一个阶段而不是复用 UP+DOWN：连发不能触碰修饰键状态，否则「Ctrl 单次锁定 + 长按退格」
         * 会在第一次重复时就把 Ctrl 消费掉。
         */
        REPEAT,
    }

    interface Listener {
        /** 普通按键的状态变化（[KeyKind.PLAIN]） */
        fun onKeyEvent(key: KeySpec, phase: Phase)

        /** 修饰键被点了一下（粘滞锁定状态机由实现方推进） */
        fun onModifierTap(key: KeySpec)

        /** 动作键被点了一下（切换布局 / 全屏展开） */
        fun onAction(action: KeyAction, key: KeySpec)

        /** 查询修饰键的粘滞状态，用于高亮渲染 */
        fun modifierLock(vk: Int): StickyKeys.Lock
    }

    var listener: Listener? = null

    /** 当前布局。换布局会取消进行中的手势（否则按下的是旧布局的键、抬起时对不上） */
    var layout: KeyboardLayout = KeyboardLayouts.QWERTY
        set(value) {
            if (field.id == value.id) return
            field = value
            cancelGesture()
            // 高度由布局的宽高比决定（见 onMeasure），换布局可能换高度，必须重新测量
            requestLayout()
            invalidate()
        }

    // ---------------- 尺寸与画笔 ----------------

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    /**
     * sp → px。
     *
     * 用 [TypedValue.applyDimension] 而不是 `displayMetrics.scaledDensity`：后者在 API 34 起已废弃
     * （它不跟随「按应用单独设置字体大小」），前者是官方替代写法。
     */
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val keyGap = dp(KEY_GAP_DP)
    private val corner = dp(CORNER_DP)
    private val slideSlop = dp(SLIDE_SLOP_DP)
    private val labelMaxTextSize = sp(16f)
    private val labelMinTextSize = sp(9f)

    private val colorKeyBg = color(R.color.at_key_bg)
    private val colorKeyModifierBg = color(R.color.at_key_bg_modifier)
    private val colorKeyPressedBg = color(R.color.at_key_bg_pressed)
    private val colorKeyArmedBg = color(R.color.at_key_bg_armed)
    private val colorKeyLockedBg = color(R.color.at_key_bg_locked)
    private val colorKeyStroke = color(R.color.at_button_stroke)
    private val colorKeyText = color(R.color.at_text)
    private val colorKeySubText = color(R.color.at_text_dim)
    private val colorKeyTextOnAccent = color(R.color.at_on_primary)
    private val colorUnderline = color(R.color.at_secondary)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = colorKeyStroke
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorUnderline }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorKeyText
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = colorKeySubText
    }

    private val keyRect = RectF()
    private val underlineRect = RectF()

    /** 文本尺寸按「(字符数, 可用宽度分档)」缓存：一次绘制要给上百个键算字号，逐帧 measure 太浪费 */
    private val textSizeCache = HashMap<String, Float>()

    // ---------------- 手势状态 ----------------

    /**
     * 一根手指的状态。
     *
     * 多指是**必须**的（见类注释）：粘滞化会让「按住 Shift 再敲字母」彻底失效，所以每根手指
     * 各记一份「按着哪个键、从哪儿按下、是否已经滑出去」。
     */
    private class PointerState(
        var key: KeySpec?,
        val downX: Float,
        val downY: Float,
        var sliding: Boolean = false,
    )

    /** pointerId → 该指的状态。用 `event.getPointerId(actionIndex)` 而不是 index，手指抬起后 index 会重排 */
    private val pointers = HashMap<Int, PointerState>()

    /** 正被任意一根手指按着的键。渲染要按「这个键是否被按着」查询，所以单独存一份 */
    private val pressedKeys = HashSet<KeySpec>()

    private val repeatHandler = Handler(Looper.getMainLooper())

    /** 当前拥有连发权的那根手指；-1 表示没有。后按下的可连发键会抢占它 */
    private var repeatPointerId = -1
    private var repeatKey: KeySpec? = null
    private var repeatTicked = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = repeatKey ?: return
            if (!repeatTicked) {
                // 连发开始的信号：手感上必须能分辨「按了一下」和「开始连发」
                repeatTicked = true
                haptic(HapticFeedbackConstants.LONG_PRESS)
            }
            listener?.onKeyEvent(key, Phase.REPEAT)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    // ---------------- 布局与绘制 ----------------

    /** 去掉内边距之后真正用来画键帽的宽度 */
    private val contentWidth: Float
        get() = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()

    /** 去掉内边距之后真正用来画键帽的高度 */
    private val contentHeight: Float
        get() = (height - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()

    private val totalRowWeight: Float
        get() {
            val l = layout
            var sum = 0f
            for (i in 0 until l.rowCount) sum += l.rowHeightWeight(i)
            return if (sum <= 0f) 1f else sum
        }

    private fun rowHeightAt(rowIndex: Int): Float =
        contentHeight * layout.rowHeightWeight(rowIndex) / totalRowWeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)

        val w = if (wMode == MeasureSpec.UNSPECIFIED) {
            dp(DEFAULT_WIDTH_DP).toInt()
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }

        val availableWidth = (w - paddingLeft - paddingRight).coerceAtLeast(1)
        // 高度由布局自己声明的宽高比决定（见 KeyboardLayout.heightRatio）。
        // 可用高度不够时再夹一次：键盘宁可矮一点，也不能把自己挤出屏幕。
        val ideal = (availableWidth * layout.heightRatio).toInt() + paddingTop + paddingBottom

        val h = when (hMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> ideal.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> ideal
        }

        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        textSizeCache.clear()
        applySystemGestureExclusion()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val l = layout
        if (l.rowCount == 0 || contentWidth <= 0f || contentHeight <= 0f) return

        // 一次性平移进内容区，之后所有几何计算都按内容区尺寸走
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        var top = 0f
        for (r in 0 until l.rowCount) {
            val h = rowHeightAt(r)
            drawRow(canvas, l.rows[r], top, h)
            top += h
        }

        canvas.restore()
    }

    private fun drawRow(canvas: Canvas, row: List<KeySpec>, top: Float, rowHeight: Float) {
        var total = 0f
        for (key in row) total += key.weight
        if (total <= 0f) return

        val unit = contentWidth / total
        var left = 0f
        for (key in row) {
            val w = key.weight * unit
            if (key.isTouchable) drawKey(canvas, key, left, top, w, rowHeight)
            left += w
        }
    }

    private fun drawKey(canvas: Canvas, key: KeySpec, left: Float, top: Float, w: Float, h: Float) {
        val inset = keyGap / 2f
        keyRect.set(left + inset, top + inset, left + w - inset, top + h - inset)
        if (keyRect.width() <= 1f || keyRect.height() <= 1f) return

        // 只有粘滞修饰键才有锁定状态；Shift / Alt / Win 现在就是普通键，按下态就是它们的全部
        val lock = if (key.isStickyModifier) {
            listener?.modifierLock(key.vk) ?: StickyKeys.Lock.NONE
        } else {
            StickyKeys.Lock.NONE
        }
        val pressed = key in pressedKeys

        val background: Int
        val onAccent: Boolean
        when {
            pressed -> {
                background = colorKeyPressedBg
                onAccent = true
            }
            lock == StickyKeys.Lock.LOCKED -> {
                background = colorKeyLockedBg
                onAccent = true
            }
            // 单次锁定用「较暗的强调色」：持续锁定才是满强调 + 下划线，两种状态一眼可辨
            lock == StickyKeys.Lock.ONCE -> {
                background = colorKeyArmedBg
                onAccent = true
            }
            key.isModifier || key.kind == KeyKind.ACTION -> {
                background = colorKeyModifierBg
                onAccent = false
            }
            else -> {
                background = colorKeyBg
                onAccent = false
            }
        }

        keyPaint.color = background
        canvas.drawRoundRect(keyRect, corner, corner, keyPaint)
        canvas.drawRoundRect(keyRect, corner, corner, strokePaint)

        if (lock == StickyKeys.Lock.LOCKED) {
            // 下划线：远看也能把「持续锁定」和「单次锁定」分开（工程要求里的「常亮并带下划线」）
            val thickness = dp(2f)
            underlineRect.set(
                keyRect.left + dp(5f),
                keyRect.bottom - thickness - dp(2f),
                keyRect.right - dp(5f),
                keyRect.bottom - dp(2f),
            )
            canvas.drawRoundRect(underlineRect, thickness / 2f, thickness / 2f, underlinePaint)
        }

        val sub = key.subLabel
        val size = fitTextSize(key.label, keyRect.width() - dp(6f))
        labelPaint.textSize = size
        labelPaint.color = if (onAccent) colorKeyTextOnAccent else colorKeyText

        val baseline = if (sub != null) {
            keyRect.centerY() + size * 0.28f
        } else {
            keyRect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
        }
        canvas.drawText(key.label, keyRect.centerX(), baseline, labelPaint)

        if (sub != null) {
            subLabelPaint.textSize = size * 0.7f
            subLabelPaint.color = if (onAccent) colorKeyTextOnAccent else colorKeySubText
            canvas.drawText(sub, keyRect.left + dp(4f), keyRect.top + dp(3f) - subLabelPaint.ascent(), subLabelPaint)
        }
    }

    /** 让 [text] 在 [maxWidth] 内放得下，从 [labelMaxTextSize] 逐档缩小到 [labelMinTextSize] */
    private fun fitTextSize(text: String, maxWidth: Float): Float {
        if (text.isEmpty() || maxWidth <= 0f) return labelMinTextSize

        val cacheKey = "${text.length}:${(maxWidth / dp(4f)).toInt()}"
        textSizeCache[cacheKey]?.let { return it }

        var size = labelMaxTextSize
        labelPaint.textSize = size
        val step = sp(0.5f)
        while (size > labelMinTextSize && labelPaint.measureText(text) > maxWidth) {
            size -= step
            labelPaint.textSize = size
        }
        textSizeCache[cacheKey] = size
        return size
    }

    // ---------------- 命中测试 ----------------

    /** 命中的按键；落在空隙或键盘之外返回 null。命中用整格（不含绘制时的内缩），按键更好按 */
    fun keyAt(x: Float, y: Float): KeySpec? {
        // 换算到内容区坐标（与 onDraw 的平移保持一致）
        val cx = x - paddingLeft
        val cy = y - paddingTop
        if (cx < 0f || cy < 0f || cx >= contentWidth || cy >= contentHeight) return null

        val l = layout
        var top = 0f
        for (r in 0 until l.rowCount) {
            val h = rowHeightAt(r)
            if (cy >= top && cy < top + h) {
                val row = l.rows[r]
                var total = 0f
                for (key in row) total += key.weight
                if (total <= 0f) return null

                val unit = contentWidth / total
                var left = 0f
                for (key in row) {
                    val w = key.weight * unit
                    if (key.isTouchable && cx >= left && cx < left + w) return key
                    left += w
                }
                return null
            }
            top += h
        }
        return null
    }

    // ---------------- 触摸 ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 父容器（横向滚动的容器、ViewPager 之类）不得抢走键盘上的手势
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                // 用 actionIndex 取「刚按下的那一根」，用 getPointerId 做键：手指抬起后 index 会重排
                val i = event.actionIndex
                beginPointer(event.getPointerId(i), event.getX(i), event.getY(i))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val state = pointers[event.getPointerId(i)] ?: continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (!state.sliding &&
                        (abs(x - state.downX) > slideSlop || abs(y - state.downY) > slideSlop)
                    ) {
                        state.sliding = true
                    }
                    if (state.sliding) {
                        val target = keyAt(x, y)
                        if (target !== state.key) {
                            // 先算「滑出去」再按新键：滑出旧键面不触发它的抬手语义
                            endPointer(event.getPointerId(i), null)
                            state.key = target
                            startPointer(event.getPointerId(i), target)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 先取「抬起点所在的键」，再结束按下 —— 滑出键面再抬手就等于取消，不能误触发
                val i = event.actionIndex
                val id = event.getPointerId(i)
                endPointer(id, keyAt(event.getX(i), event.getY(i)))
                pointers.remove(id)

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                return true
            }
        }
        return true
    }

    /** 无条件消费：即使没命中按键也不把事件漏给下层（防误触/防穿透） */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 手指落下：记状态，并按下它命中的键 */
    private fun beginPointer(pointerId: Int, x: Float, y: Float) {
        val key = keyAt(x, y)?.takeIf { it.isTouchable }
        pointers[pointerId] = PointerState(key = null, downX = x, downY = y)
        startPointer(pointerId, key)
    }

    private fun startPointer(pointerId: Int, key: KeySpec?) {
        if (key == null) return
        pointers[pointerId]?.key = key
        pressedKeys.add(key)
        invalidate()

        // 触觉反馈在「按下」时就给：这是要求的时机，也是唯一不会与抬手动作混淆的时机
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)

        if (key.reportsPress) {
            // 立刻发 KEY_DOWN：不等抬手，输入延迟最低
            listener?.onKeyEvent(key, Phase.DOWN)
            if (key.repeat) startRepeat(pointerId, key)
        }
    }

    /**
     * 结束某一指当前按着的键。
     *
     * @param tapOn 抬起点所在的键；只有与按下的键是同一个才算「点击」（修饰键 / 动作键的抬手语义）
     */
    private fun endPointer(pointerId: Int, tapOn: KeySpec?) {
        stopRepeatIfOwnedBy(pointerId)
        val state = pointers[pointerId] ?: return
        val key = state.key ?: return
        state.key = null
        pressedKeys.remove(key)
        invalidate()

        if (key.reportsPress) {
            listener?.onKeyEvent(key, Phase.UP)
        }

        if (key === tapOn) {
            when {
                key.isStickyModifier -> {
                    listener?.onModifierTap(key)
                    invalidate()
                }
                key.kind == KeyKind.ACTION -> {
                    listener?.onAction(key.action, key)
                }
            }
        }
    }

    /** 布局切换、视图销毁时调用：把还按着的键抬起来，绝不给电脑端留下卡住的按键 */
    fun cancelGesture() {
        for (id in pointers.keys.toList()) {
            endPointer(id, null)
        }
        pointers.clear()
        stopRepeat()
    }

    private fun startRepeat(pointerId: Int, key: KeySpec) {
        // 连发只有一份：后按下的可连发键抢占，避免两根手指同时在连发时互相打架
        stopRepeat()
        repeatPointerId = pointerId
        repeatKey = key
        repeatTicked = false
        repeatHandler.postDelayed(repeatRunnable, REPEAT_START_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatKey = null
        repeatPointerId = -1
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    private fun stopRepeatIfOwnedBy(pointerId: Int) {
        if (repeatPointerId == pointerId) stopRepeat()
    }

    private fun haptic(feedback: Int) {
        try {
            performHapticFeedback(feedback)
        } catch (e: RuntimeException) {
            // 个别 ROM 在没有震动器 / 关闭触感反馈时会抛异常。按键绝不能因为「震不动」而丢掉。
        }
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    /**
     * 把整片键盘加入系统手势排除区（API 29+）。
     *
     * 横屏全屏键盘铺满屏幕时，手指从屏幕边缘划过（很常见：按最左边的 Esc 或最右边的按键）
     * 会被系统判成「返回」而把整个控制界面退掉。排除区让系统不在这一片区域起手势；
     * 系统自身会限制可排除的总面积，所以这不会真的锁死返回手势。
     */
    private fun applySystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (width <= 0 || height <= 0) return
        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    companion object {
        /** 键与键之间的缝（dp） */
        const val KEY_GAP_DP = 3f

        const val CORNER_DP = 6f

        /** 超过这个位移才算「在键面之间滑动」，避免指尖抖动按错键 */
        const val SLIDE_SLOP_DP = 6f

        /** 长按多久开始连发 */
        const val REPEAT_START_DELAY_MS = 400L

        /** 连发间隔：约 22 次/秒，接近 Windows 默认键盘重复速度 */
        const val REPEAT_INTERVAL_MS = 45L

        /** 尺寸完全无法确定时（UNSPECIFIED）的兜底宽度 */
        const val DEFAULT_WIDTH_DP = 360f
    }
}
