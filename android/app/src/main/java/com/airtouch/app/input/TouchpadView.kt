package com.airtouch.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.airtouch.app.net.Protocol
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 触摸板自定义 View。
 *
 * 所有坐标换算都以 **dp** 为单位（内部用 density 换算），因此灵敏度与屏幕密度无关。
 *
 * 手势 → 协议指令（协议 §2.1）→ 电脑侧用哪个 API 实现：
 *
 * | 手势 | 协议帧 | 电脑侧 API |
 * | --- | --- | --- |
 * | 单指拖动 | `move` | `SendInput` 相对移动 |
 * | 单指轻点 / 双指轻点 | `click` | `SendInput` 按下+抬起 |
 * | **双击后按住不放（拖拽）** | `button` + `move` | `SendInput` 按下 → 移动 → 抬起 |
 * | 双指上下 / 左右滑 | `scroll`（`delta` 高精度、`axis`） | `SendInput` `MOUSEEVENTF_WHEEL` / `HWHEEL`，小增量 |
 * | **双指捏合 / 张开** | `touch`（几何流） | `InjectTouchInput` 真双指触摸；无触摸栈时服务端回退 Ctrl+高精度滚轮 |
 * | **三指滑动 / 轻点** | `key` | `SendInput` 系统快捷键（Alt+Tab / Win+Tab / Win+D / Win+S） |
 *
 * 每个手势都交给最适合它的那个 Windows API，是这一版的中心思想：滚轮给能接受小增量的
 * 高精度滚轮（像素级平滑，全软件通用），缩放给触摸注入（无级、和真实触摸屏一致），
 * 三指给系统快捷键（稳定、不受权限限制），拖拽给鼠标按键的按下/抬起。
 *
 * 细节：
 * - 单指拖动：dx = (x - lastX) / density * BASE_SENSITIVITY * sensitivity，四舍五入取整，
 *   **余数累积**到 pendingDx/pendingDy，因此慢速拖动不会因为取整而丢步。
 * - 单指轻点（<200ms 且位移 < 12dp） → `click left`。
 * - 双击后按住不放 → 拖拽。判定与真实触摸板一致：轻点一下之后 **300ms 内**在**原地附近**再按下，
 *   随后移动超过 6dp 才算拖拽开始；第二下如果抬手很快、没移动，就按普通轻点处理
 *   （于是「连点两下」仍然是双击，不会被吞掉）。滚轮模式下不参与拖拽。
 * - 双指滑动 → 高精度滚轮：手指位移按 3 单位/dp 换算成原生滚轮单位（40dp = 一格 = 120 单位），
 *   每攒够 **15 单位（约八分之一格）** 就发一帧，纵横向各自独立累积；
 *   **符号约定**：手指下滑 = 内容下滚 = 负 delta（协议 §2.1「正数上滚，负数下滚」），横滑同理。
 * - 双指捏合/张开 → `touch` 几何流：[Listener.onPinch]。手势开始时记下两指间距，
 *   之后每帧报「间距是开始时的几倍」——相对值跨设备成立，也让服务端能在触摸注入与
 *   Ctrl+滚轮之间自由选择。
 * - 三指滑动（累计位移先超过 60dp 的那个方向）→ [Listener.onThreeFinger]；
 *   三指轻点（<200ms、位移 <12dp）→ 同样一个回调，具体映射见 `ControlBridge.sendThreeFinger`。
 *   三指手势一旦出现，本次手势里剩下的双指/单指逻辑全部让位，避免松手指时误触发拖动或点击。
 * - 滚轮模式开启时：单指上下滑动改为发高精度滚轮（同样 3 单位/dp）。
 *
 * **双指手势的意图判定**：捏合与滚动用的是同一组手指，必然互相干扰（捏合时两指中心也会动）。
 * 所以双指手势的前期**两种位移都累积、但都不发送**，直到其中一个明显占优才锁定本次手势的意图
 * （跨度变化 ≥ 8dp 且大于纵向累计位移的 1.2 倍 → 缩放，否则纵向累计 ≥ 8dp → 滚动），
 * 锁定后只发对应的那一种，并把此前积累的量一次性补发。这样既不会「想缩放却在滚屏」，
 * 也不会丢掉手势开头的位移。
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

        /**
         * 高精度滚轮（协议 §2.1 的 `delta` / `axis`）。
         *
         * @param delta 原生滚轮单位，120 = 一格；触摸板发的通常只有 15 上下
         * @param horizontal true = 横向滚动
         */
        fun onScroll(delta: Int, horizontal: Boolean)

        /**
         * 鼠标按键的按下 / 抬起（协议 §2.1 的 `button`），拖拽手势用。
         *
         * @return true 表示帧已经交给连接发出去；false（未连接等）时 View 会放弃拖拽状态，
         *   以免出现「手指抬起、电脑上的左键还按着」。
         */
        fun onButton(button: String, down: Boolean): Boolean

        /**
         * 双指捏合 / 张开（协议 §2.1 的 `touch`）。
         *
         * @param phase [Protocol.PHASE_DOWN] / [Protocol.PHASE_MOVE] / [Protocol.PHASE_UP]
         * @param scale 当前两指间距 ÷ 手势开始时的间距；正着增长是放大
         * @param dx 手势中心相对上一帧的横向移动（屏幕像素）
         * @param dy 手势中心相对上一帧的纵向移动（屏幕像素）
         */
        fun onPinch(phase: String, scale: Double, dx: Int, dy: Int)

        /** 三指手势，action 取 [ThreeFinger] 里的常量 */
        fun onThreeFinger(action: String)
    }

    /** 三指手势的动作名（映射成哪些快捷键由 `ControlBridge` 决定） */
    object ThreeFinger {
        const val LEFT = "left"
        const val RIGHT = "right"
        const val UP = "up"
        const val DOWN = "down"
        const val TAP = "tap"
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
    private var twoFingerLastAvgX = 0f

    // ---- 双击后按住拖拽 ----
    /** 上一次「单指轻点」抬起的时间与位置（0 = 还没有过轻点） */
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** 第二下已经按下、但还没到拖拽激活阈值 */
    private var dragArmed = false

    /** 左键已经按住，正在拖拽（此时每一次移动都在拖东西） */
    private var dragActive = false

    // ---- 双指捏合 / 张开 ----
    /** 本次双指手势的意图；一旦判定就锁定到手指抬起 */
    private enum class TwoFingerMode { UNDECIDED, SCROLL, PINCH }

    private var twoFingerMode = TwoFingerMode.UNDECIDED

    /** 上一次采样的两指间距（px），用来算跨度变化 */
    private var twoFingerLastSpan = 0f

    /** 双指手势开始时的间距（px），scale 的分母 */
    private var pinchStartSpan = 0f

    /** 已经向服务端发过 touch down、还等着 touch up */
    private var pinchActive = false

    /** 上一帧发出去的 scale / 中心位置，用来做无谓帧的抑制 */
    private var pinchLastScale = 1.0
    private var pinchLastCenterX = 0f
    private var pinchLastCenterY = 0f

    /** 跨度变化累计（dp），用于意图判定与轻点容差 */
    private var twoFingerSpanMoveDp = 0f

    // ---- 三指手势 ----
    /** 本次手势里出现过三根手指：剩余的抬起过程都不再当单指/双指处理 */
    private var threeFingerSeen = false
    private var threeFingerDownTime = 0L
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var threeFingerMoveDp = 0f
    private var threeFingerFired = false

    /** move 的小数余数累加器（dp 换算后的像素小数） */
    private var pendingDx = 0f
    private var pendingDy = 0f

    /** 高精度滚轮的余量累加器（单位：原生滚轮单位，120 = 一格），纵横各一个 */
    private val wheelVertical = WheelAccumulator()
    private val wheelHorizontal = WheelAccumulator()

    /**
     * 滚轮余量累加器。
     *
     * 手指位移换算成滚轮单位后先攒着，攒够 [SCROLL_DELTA_STEP] 才发一帧——这样发出去的是
     * 「八分之一格」这种小增量（应用会自己累加，滚动是平滑的），而不是一次一大格。
     * 发送后把整数部分扣掉，小数余量留到下一帧，慢速滑动因此不会丢步。
     */
    private class WheelAccumulator {
        private var pending = 0f

        fun add(units: Float) {
            pending += units
        }

        /** 够一个步进就返回要发送的整数增量，否则返回 0 */
        fun take(): Int {
            if (abs(pending) < SCROLL_DELTA_STEP) return 0
            val whole = pending.roundToInt()
                .coerceIn(-Protocol.SCROLL_DELTA_LIMIT, Protocol.SCROLL_DELTA_LIMIT)
            pending -= whole.toFloat()
            return whole
        }

        fun reset() {
            pending = 0f
        }
    }

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
                twoFingerSpanMoveDp = 0f
                // 「双击后按住不放」的判定：上一次轻点之后 300ms 内、在原地附近再按下。
                // 滚轮模式下单指滑动是滚轮，不参与拖拽（否则拖文件会变成滚屏）。
                dragArmed = !scrollMode &&
                    lastTapUpTime != 0L &&
                    event.eventTime - lastTapUpTime <= DOUBLE_TAP_TIMEOUT_MS &&
                    hypot(
                        (event.x - lastTapX).toDouble(),
                        (event.y - lastTapY).toDouble()
                    ) <= (DOUBLE_TAP_SLOP_DP * density).toDouble()
                dragActive = false
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
                    twoFingerSpanMoveDp = 0f
                    twoFingerLastAvgY = averageY(event)
                    twoFingerLastAvgX = averageX(event)
                    twoFingerLastSpan = span(event)
                    // scale 的分母：手势开始时的间距（在意图判定之前就记下，判定期的跨度变化才不会丢）
                    pinchStartSpan = twoFingerLastSpan
                    twoFingerMode = TwoFingerMode.UNDECIDED
                    totalMoveDp = 0f
                    // 第二根手指落下了：这不再是「双击拖拽」，而是双指手势
                    dragArmed = false
                    resetRemainders()
                } else if (event.pointerCount >= 3) {
                    // 三指：本次手势整体让位给三指逻辑（双指滚动/捏合、单指点击全部不再参与）
                    pointerCountInGesture = event.pointerCount
                    twoFingerCandidate = false
                    dragArmed = false
                    // 捏合途中落下第三根手指：立刻结束捏合，别让服务端一直按着两个虚拟接触点
                    if (pinchActive) {
                        listener?.onPinch(Protocol.PHASE_UP, 1.0, 0, 0)
                        pinchActive = false
                    }
                    threeFingerSeen = true
                    threeFingerFired = false
                    threeFingerDownTime = event.eventTime
                    threeFingerStartX = averageX3(event)
                    threeFingerStartY = averageY3(event)
                    threeFingerMoveDp = 0f
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
                    // 双指轻点的容差判定：抬起时累计位移仍要小于阈值（含捏合造成的跨度变化）
                    if (twoFingerMoveDp > TAP_MAX_MOVE_DP || twoFingerSpanMoveDp > TAP_MAX_MOVE_DP) {
                        twoFingerCandidate = false
                    }
                }
                pointerCountInGesture = event.pointerCount - 1
                twoFingerMode = TwoFingerMode.UNDECIDED
                // 手指少到一根：捏合就此结束（真触摸板也是抬起一根手指就结束缩放），
                // 否则服务端会一直按着两个虚拟接触点，直到本次手势全部抬起
                if (pinchActive) {
                    listener?.onPinch(Protocol.PHASE_UP, 1.0, 0, 0)
                    pinchActive = false
                }
                resetRemainders()
                // 重设单指基准，避免抬起瞬间产生跳变
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                when {
                    // 三指：只判方向，不滚动也不缩放（松到两指/一指也不再改判，见 threeFingerSeen）
                    event.pointerCount >= 3 -> handleThreeFingerMove(event)
                    threeFingerSeen -> Unit
                    event.pointerCount == 2 -> handleTwoFingerMove(event)
                    event.pointerCount == 1 -> handleOneFingerMove(event)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasTwoFingerTap = twoFingerCandidate &&
                    event.eventTime - twoFingerDownTime < TAP_MAX_DURATION_MS &&
                    twoFingerMoveDp < TAP_MAX_MOVE_DP &&
                    twoFingerSpanMoveDp < TAP_MAX_MOVE_DP

                when {
                    // 三指手势：滑动在超过阈值时就已触发，这里只补「三指轻点」
                    threeFingerSeen -> {
                        if (!threeFingerFired &&
                            event.eventTime - threeFingerDownTime < TAP_MAX_DURATION_MS &&
                            threeFingerMoveDp < TAP_MAX_MOVE_DP
                        ) {
                            listener?.onThreeFinger(ThreeFinger.TAP)
                        }
                    }

                    // 拖拽结束：只抬左键，不再补一次点击（补了就会在拖完的一瞬间又点一下）
                    dragActive -> {
                        listener?.onButton(ButtonCodes.LEFT, false)
                        // 拖拽之后不再接续双击，避免「拖完随手一点」被当成第二下
                        lastTapUpTime = 0L
                    }

                    wasTwoFingerTap -> listener?.onClick(ButtonCodes.RIGHT)

                    pointerCountInGesture <= 1 -> {
                        val duration = event.eventTime - downTime
                        if (duration < TAP_MAX_DURATION_MS && totalMoveDp < TAP_MAX_MOVE_DP) {
                            listener?.onClick(ButtonCodes.LEFT)
                            // 记下这一下轻点，供「双击后按住」判定使用
                            lastTapUpTime = event.eventTime
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }
                }
                resetGestureState()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 手势被系统抢走（例如来电、切页）时，正在拖拽的话必须把左键抬起来
                if (dragActive) {
                    listener?.onButton(ButtonCodes.LEFT, false)
                }
                resetGestureState()
                return true
            }
        }
        return false
    }

    /**
     * 外部打断（切页、onPause、连接断开）时调用：正在拖拽就补一次左键抬起。
     *
     * 不能只依赖 ACTION_UP：手指还按着屏幕时切页，View 收不到 UP，电脑上的左键会一直按着。
     * 服务端在连接断开时也会兜底释放，但那是最后一道防线，不该指望它。
     */
    fun cancelGesture() {
        if (dragActive) {
            listener?.onButton(ButtonCodes.LEFT, false)
        }
        resetGestureState()
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

        // 「双击后按住不放」：位移超过阈值才真正按住左键（先按下、再走这一小段，顺序与真实鼠标一致）。
        // 阈值之前的位移刻意不发——那是手指落下的抖动，发出去会让光标在拖拽开始前先跳一下。
        if (dragArmed && !dragActive) {
            if (totalMoveDp < DRAG_ACTIVATE_SLOP_DP) return
            dragArmed = false
            // 按下失败（未连接）就不要进入拖拽状态，否则后面每一次移动都在拖一个不存在的按键
            dragActive = listener?.onButton(ButtonCodes.LEFT, true) == true
            if (!dragActive) return
        }

        val scale = BASE_SENSITIVITY * sensitivity
        if (scrollMode) {
            // 滚轮模式：只取纵向分量，3 单位/dp（40dp = 一格）；手指下滑 → 负 delta（下滚）
            wheelVertical.add(-dyDp * SCROLL_UNITS_PER_DP * sensitivity)
            emitScroll()
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

    /**
     * 三指手势的一个采样点：只看「相对起点的累计位移」，先超过 60dp 的那个方向决定动作。
     *
     * 触发一次就不再改判：手指继续走、手指减少都不影响已经发出去的快捷键。
     */
    private fun handleThreeFingerMove(event: MotionEvent) {
        val x = averageX3(event)
        val y = averageY3(event)
        val startX = threeFingerStartX
        val startY = threeFingerStartY

        if (threeFingerFired) return

        val dxDp = (x - startX) / density
        val dyDp = (y - startY) / density
        threeFingerMoveDp = maxOf(threeFingerMoveDp, hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat())

        if (maxOf(abs(dxDp), abs(dyDp)) < THREE_FINGER_SWIPE_DP) return

        threeFingerFired = true
        val action = if (abs(dxDp) >= abs(dyDp)) {
            if (dxDp < 0) ThreeFinger.LEFT else ThreeFinger.RIGHT
        } else {
            if (dyDp < 0) ThreeFinger.UP else ThreeFinger.DOWN
        }
        listener?.onThreeFinger(action)
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        // 与单指同理：合并事件里的历史采样点也要逐个吃掉，否则双指滚动同样会顿
        val history = event.historySize
        if (history > 0) {
            for (h in 0 until history) {
                processTwoFingerPoint(averageYAt(event, h), spanAt(event, h), averageXAt(event, h))
            }
        }
        processTwoFingerPoint(averageY(event), span(event), averageX(event))
    }

    /**
     * 处理双指手势的一个采样点：先累积、判定意图，再只发送属于该意图的那一种。
     *
     * @param avgY 两指的平均 Y（用于滚动与轻点判定）
     * @param avgX 两指的平均 X（横向滚动与意图判定）
     * @param span 两指间距（用于捏合判定）
     */
    private fun processTwoFingerPoint(avgY: Float, span: Float, avgX: Float) {
        val dyPx = avgY - twoFingerLastAvgY
        val dxPx = avgX - twoFingerLastAvgX
        twoFingerLastAvgY = avgY
        twoFingerLastAvgX = avgX
        val dSpanPx = span - twoFingerLastSpan
        twoFingerLastSpan = span

        val dyDp = dyPx / density
        val dxDp = dxPx / density
        val dSpanDp = dSpanPx / density

        // 取「中心位移的长度」而不是只看纵向：两指横滑现在是横向滚动，只看 dy 永远不会判定成滚动，
        // 横滑就会什么都不发生。轻点容差同样是「中心移动了多少」，这样更准确。
        twoFingerMoveDp += hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat()
        twoFingerSpanMoveDp += abs(dSpanDp)

        // 两种量都先累积、都先不发：判定出意图之前发任何东西都可能发错
        val unitsPerDp = SCROLL_UNITS_PER_DP * sensitivity
        wheelVertical.add(-dyDp * unitsPerDp)
        wheelHorizontal.add(-dxDp * unitsPerDp)

        if (twoFingerMode == TwoFingerMode.UNDECIDED) {
            twoFingerMode = when {
                // 跨度变化明显、并且明显压过纵向位移 → 缩放
                twoFingerSpanMoveDp >= PINCH_ACTIVATE_DP &&
                    twoFingerSpanMoveDp > twoFingerMoveDp * PINCH_DOMINANCE -> TwoFingerMode.PINCH
                // 纵向累计够多 → 滚动
                twoFingerMoveDp >= SCROLL_ACTIVATE_DP -> TwoFingerMode.SCROLL
                // 还不明显：继续攒着，什么都不发（此时也可能是双指轻点）
                else -> return
            }
            // 意图一旦定下，双指轻点就不可能了
            twoFingerCandidate = false

            if (twoFingerMode == TwoFingerMode.PINCH) {
                // 先把攒下的滚动余量丢掉，再开手势：否则松手后会突然滚一下
                wheelVertical.reset()
                wheelHorizontal.reset()
                beginPinch()
            }
        }

        if (twoFingerMode == TwoFingerMode.SCROLL) {
            emitScroll()
        } else {
            // 缩放：始终相对于手势开始时的间距，所以判定期攒下的跨度变化不会丢
            pushPinch(avgX, avgY)
        }
    }

    private fun beginPinch() {
        if (pinchStartSpan <= MIN_SPAN_PX) return
        pinchActive = true
        pinchLastScale = 1.0
        pinchLastCenterX = twoFingerLastAvgX
        pinchLastCenterY = twoFingerLastAvgY
        listener?.onPinch(Protocol.PHASE_DOWN, 1.0, 0, 0)
    }

    /** 把当前的间距倍数与中心位移发给服务端（变化太小就不发，省 Wi-Fi） */
    private fun pushPinch(avgX: Float, avgY: Float) {
        if (!pinchActive) return
        val scale = (twoFingerLastSpan / pinchStartSpan).toDouble()
            .coerceIn(Protocol.SCALE_MIN, Protocol.SCALE_MAX)

        val dxPx = avgX - pinchLastCenterX
        val dyPx = avgY - pinchLastCenterY
        val centerMoved = abs(dxPx) >= 1f || abs(dyPx) >= 1f
        if (!centerMoved && abs(scale - pinchLastScale) < PINCH_MIN_SCALE_DELTA) return

        pinchLastScale = scale
        pinchLastCenterX = avgX
        pinchLastCenterY = avgY
        // 中心位移换算成屏幕像素，用的是和光标同一套灵敏度，手感才一致
        val scaleFactor = BASE_SENSITIVITY * sensitivity
        listener?.onPinch(
            Protocol.PHASE_MOVE,
            scale,
            (dxPx / density * scaleFactor).roundToInt(),
            (dyPx / density * scaleFactor).roundToInt()
        )
    }

    /** 够一个步进就发一帧；纵横各自独立判断 */
    private fun emitScroll() {
        val v = wheelVertical.take()
        if (v != 0) listener?.onScroll(v, horizontal = false)
        val h = wheelHorizontal.take()
        if (h != 0) listener?.onScroll(h, horizontal = true)
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

    /** 前两根手指的平均 X。不足两指时返回 0（调用方只在双指手势里用它） */
    private fun averageX(event: MotionEvent): Float = averageXAt(event, -1)

    private fun averageXAt(event: MotionEvent, historicalIndex: Int): Float {
        var sum = 0f
        val n = if (event.pointerCount > 2) 2 else event.pointerCount
        if (n <= 0) return 0f
        for (i in 0 until n) {
            sum += if (historicalIndex < 0) event.getX(i)
            else event.getHistoricalX(i, historicalIndex)
        }
        return sum / n
    }

    /** 前**三**根手指的平均 X / Y（三指手势用；不足三指时有几根算几根） */
    private fun averageX3(event: MotionEvent): Float = average3(event, y = false)

    private fun averageY3(event: MotionEvent): Float = average3(event, y = true)

    private fun average3(event: MotionEvent, y: Boolean): Float {
        var sum = 0f
        val n = if (event.pointerCount > 3) 3 else event.pointerCount
        if (n <= 0) return 0f
        for (i in 0 until n) {
            sum += if (y) event.getY(i) else event.getX(i)
        }
        return sum / n
    }

    /** 前两根手指的间距（px）。不足两指时返回 0（调用方只在双指手势里用它） */
    private fun span(event: MotionEvent): Float = spanAt(event, -1)

    private fun spanAt(event: MotionEvent, historicalIndex: Int): Float {
        if (event.pointerCount < 2) return 0f
        val x0 = if (historicalIndex < 0) event.getX(0) else event.getHistoricalX(0, historicalIndex)
        val y0 = if (historicalIndex < 0) event.getY(0) else event.getHistoricalY(0, historicalIndex)
        val x1 = if (historicalIndex < 0) event.getX(1) else event.getHistoricalX(1, historicalIndex)
        val y1 = if (historicalIndex < 0) event.getY(1) else event.getHistoricalY(1, historicalIndex)
        return hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
    }

    private fun resetRemainders() {
        pendingDx = 0f
        pendingDy = 0f
        wheelVertical.reset()
        wheelHorizontal.reset()
    }

    private fun resetGestureState() {
        active = false
        pointerCountInGesture = 0
        totalMoveDp = 0f
        twoFingerCandidate = false
        twoFingerMoveDp = 0f
        twoFingerSpanMoveDp = 0f
        twoFingerMode = TwoFingerMode.UNDECIDED
        dragArmed = false
        dragActive = false
        threeFingerSeen = false
        threeFingerFired = false
        threeFingerMoveDp = 0f
        // 正在捏合就必须补一个 up：服务端要么松开两个虚拟接触点，要么松开 Ctrl。
        // 所有结束路径（抬手、ACTION_CANCEL、切页、onPause）都会走到这里，所以只写这一处。
        if (pinchActive) {
            listener?.onPinch(Protocol.PHASE_UP, 1.0, 0, 0)
            pinchActive = false
        }
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

        /** 手指位移 → 原生滚轮单位：40dp = 一格 = 120 单位，也就是 3 单位/dp */
        const val SCROLL_UNITS_PER_DP = Protocol.SCROLL_UNITS_PER_NOTCH / SCROLL_DP_PER_UNIT

        /**
         * 每帧最小滚轮增量（原生单位）。
         *
         * 15 ≈ 八分之一格：比一次 120 细腻得多（应用会把小增量累加，于是滚动是像素级的），
         * 又不至于每移动 1dp 就发一帧、把 Wi-Fi 塞满。
         */
        const val SCROLL_DELTA_STEP = 15f

        /** 轻点判定：按下到抬起不超过 200ms */
        const val TAP_MAX_DURATION_MS = 200L

        /** 轻点判定：总位移不超过 12dp */
        const val TAP_MAX_MOVE_DP = 12f

        /** 双击判定：两下之间不超过 300ms（与 Android 的 ViewConfiguration 默认值一致） */
        const val DOUBLE_TAP_TIMEOUT_MS = 300L

        /** 双击判定：第二下要落在上一处的 32dp 之内，否则算「换个地方点」 */
        const val DOUBLE_TAP_SLOP_DP = 32f

        /** 拖拽激活：第二下按下后移动超过 6dp 才真正按住左键（挡手抖） */
        const val DRAG_ACTIVATE_SLOP_DP = 6f

        /** 双指手势的意图判定门槛与缩放刻度（见类注释） */
        const val SCROLL_ACTIVATE_DP = 8f
        const val PINCH_ACTIVATE_DP = 8f
        const val PINCH_DOMINANCE = 1.2f

        /** 三指手势：累计位移先超过这个距离的方向算作滑动方向 */
        const val THREE_FINGER_SWIPE_DP = 60f

        /**
         * 捏合时 scale 的变化小于这个量、中心也没动，就不发帧。
         *
         * 0.2% 的间距变化在屏幕上根本看不出来，但每帧都发会让 Wi-Fi 上多出几十帧/秒的
         * 无用流量；同时也不能太大，否则慢速捏合会一顿一顿。
         */
        const val PINCH_MIN_SCALE_DELTA = 0.002

        /**
         * 捏合起手时两指间距的下限（px）。
         *
         * 间距太小时「倍数」全是噪声（手指几乎叠在一起，1px 抖动就是几十个百分点），
         * 这种起手直接不开手势。
         */
        const val MIN_SPAN_PX = 24f

        private const val GRID_STEP_DP = 32f
    }
}
