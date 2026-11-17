package com.airtouch.app.input

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 空中鼠标的数学核心（纯计算，不依赖 Android 传感器 API，便于单独推理与测试）。
 *
 * 输入：陀螺仪三个轴的角速度（rad/s）+ 事件时间戳（纳秒，来自 SensorEvent.timestamp）。
 * 输出：整数像素位移（[Output.Move]）或滚轮格数（[Output.Scroll]），小数余数保留在内部累加器里，
 *       所以慢速转动也不会丢步。
 *
 * ## 轴向映射（v2.1 修正：左右用的是 **Z 轴**，不是 Y 轴）
 *
 * Android 传感器坐标系是**设备固定坐标系**：X 轴沿屏幕水平向右，Y 轴沿屏幕竖直向上，
 * Z 轴垂直屏幕指向外侧（指向用户）。三轴构成右手系。陀螺仪读数是绕轴的角速度，
 * 正方向按右手定则。
 *
 * 空中鼠标的实际握法是**手机基本摊平、屏幕朝上、顶部朝前**（像拿一支激光笔指着屏幕）。
 * 在这种姿态下：
 *
 * | 手的动作 | 转轴 | 陀螺仪读数 | 屏幕效果 |
 * | --- | --- | --- | --- |
 * | 左右转手腕（水平面内转动，像激光笔扫过屏幕） | **Z 轴**（垂直于屏幕，即世界竖直方向） | 向左转 → `gyro[2] > 0` | 光标左右移动 |
 * | 顶部上下俯仰（顶部远离/靠近自己） | **X 轴**（屏幕水平方向） | 顶部向自己 → `gyro[0] > 0` | 光标上下移动 |
 * | 手机绕自身长轴滚转（拧手腕） | Y 轴 | — | **不使用** |
 *
 * 也就是说：**左右取 Z 轴、上下取 X 轴**，Y 轴（滚转）完全忽略。早期版本用的是 X+Y，
 * 于是「左右移动光标」变成了「拧手腕」，越用越别扭 —— 这次改成 X+Z。
 *
 * 符号（默认值，即两个「反向」开关都不勾）：
 * - 向左转手腕 → `gyro[2] > 0` → 光标要向左（dx < 0），所以水平方向系数 `kx = -1`；
 * - 顶部向自己倾斜 → `gyro[0] > 0` → 光标要向上（dy < 0，Android 屏幕坐标向下为正），
 *   所以垂直方向系数 `ky = -1`。
 *
 * 这两个系数就是 [axisMapping]，界面上的「反向（左右）/反向（上下）」会对它乘 -1。
 * 刻意不做 `SensorManager.remapCoordinateSystem`：空中鼠标的直觉映射本来就依赖握持方式，
 * 固定映射 + 两个反相开关更可控（remap 会在手机姿态变化时改变轴向，反而更难用）。
 *
 * ## 滚轮模式
 *
 * [scrollMode] 打开后，**俯仰（X 轴）** 不再移动光标，而是按累计角度折算成滚轮格数：
 * 顶部向下 = 内容向下滚（`scroll amount < 0`，与协议 §2.1「正数上滚、负数下滚」一致）。
 * 这样空中鼠标也能翻页/滚动，不需要换到触摸板页。
 *
 * ## 滤波与死区
 *
 * - [DEAD_ZONE_RAD_S]：**作用在原始角速度上**，只用来挡手抖与零偏噪声。
 *   旧实现把死区加在滤波后的值上，于是「慢慢转」这种小角速度会被低通压到死区以下，
 *   输入被整段丢弃 —— 现象正是「手机明明在动，光标一动不动」。现在慢速旋转只要高于噪声
 *   就会进入像素余数累加器，光标会平顺地慢慢爬。
 * - [LOW_PASS_TAU_SECONDS]：一阶低通的时间常数（秒）。用 `alpha = 1 - exp(-dt/tau)` 按真实
 *   时间步长计算，因此把传感器采样率从 50Hz 提到 500Hz 时手感不变。
 * - [MAX_DELTA_SECONDS]：两次事件的间隔被截断到 100ms。App 被切到后台或系统卡顿时
 *   dt 可能是好几秒，不截断会一瞬间甩出巨大的位移；但截得太狠（旧值 50ms）会把
 *   「卡了 80ms 之后恢复」的真实手部运动吃掉一部分，反而看起来像丢帧。
 *
 * ## 「为什么位移是离散的」（重要）
 *
 * Windows 的光标位置是**整数物理像素**，没有任何亚像素概念：引擎每帧算出的小数位移只能攒在
 * [pendingDx]/[pendingDy] 里，攒够 1 像素才发一条帧。于是慢速移动时「每秒光标更新几次」≈
 * 「每秒移动几个像素」，与发送频率无关 —— 这是整像素光标的固有下限，不是服务端/网络的锅。
 * 能改善观感的只有两件事：把采样率提上去（让 1 像素的步进更均匀，见 AirMouseFragment 的
 * MIN_SENSOR_PERIOD_US），以及把灵敏度调高（同样的手速产生更多像素/秒）。
 */
class AirMouseEngine {

    /** 本次处理的结果：要么是一次光标位移，要么是一次滚轮 */
    sealed interface Output {
        data class Move(val dx: Int, val dy: Int) : Output
        data class Scroll(val amount: Int) : Output
    }

    /** 角速度死区（rad/s），作用在原始读数上 */
    private val deadZone = DEAD_ZONE_RAD_S

    /** 低通时间常数（秒） */
    private val tau = LOW_PASS_TAU_SECONDS

    var sensitivity: Float = 1.0f
        set(value) {
            field = if (value.isNaN()) 1.0f else value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        }

    /** 反向（左右） */
    var invertX: Boolean = false

    /**
     * 反向（上下）。
     *
     * **默认 false 就是正确的方向**（顶部向自己倾斜 = 光标上移）。v2.1 之前默认值给反了，用户必须
     * 一直勾着这个开关才顺手；`SettingsStore` 在升级时会把老设置一次性取反，所以手感不变，
     * 只是这个开关的含义回到了字面意思「勾上 = 上下反向」。
     */
    var invertY: Boolean = false

    /**
     * 滚轮模式：俯仰角速度折算成滚轮格数，不再移动光标。
     * 由「滚轮模式」工具栏按钮在当前页为空中鼠标页时切换。
     */
    var scrollMode: Boolean = false

    /** 轴向映射系数：`[0]` 作用在水平的 Z 轴角速度上，`[1]` 作用在俯仰的 X 轴角速度上 */
    @Volatile
    var axisMapping: FloatArray = floatArrayOf(-1f, -1f)
        private set

    private var filteredHorizontal = 0f
    private var filteredVertical = 0f
    private var lastTimestampNs = 0L
    private var hasLastTimestamp = false

    /** 像素余数累加器：只有整数部分会被发送，余数留下避免丢步 */
    private var pendingDx = 0f
    private var pendingDy = 0f

    /** 滚轮余数累加器（单位：格） */
    private var pendingScroll = 0f

    /** 清空全部内部状态（滤波器、时间基准、余数）。复位按钮与 onResume 都要调用。 */
    @Synchronized
    fun reset() {
        filteredHorizontal = 0f
        filteredVertical = 0f
        lastTimestampNs = 0L
        hasLastTimestamp = false
        pendingDx = 0f
        pendingDy = 0f
        pendingScroll = 0f
    }

    /**
     * 根据反相开关刷新映射系数；UI 切换 CheckBox 时调用。
     *
     * 默认值（两个开关都关）= 实测正确的手感：`kx = -1`、`ky = -1`。
     * 也就是说**默认**「手腕向左转 → 光标左移、顶部向自己倾斜 → 光标上移」，
     * 勾上「反向（上下）」才反过来。v2.1 之前 ky 默认是 +1（上下是反的），
     * 用户必须一直勾着「反向（上下）」才能正常用 —— 现在把那个「勾上才对」的方向
     * 变成了默认值，`SettingsStore` 里对老设置做了一次性取反迁移。
     */
    fun updateAxisMapping() {
        axisMapping = floatArrayOf(
            if (invertX) 1f else -1f,
            if (invertY) 1f else -1f
        )
    }

    /**
     * 处理一次陀螺仪事件。
     *
     * 本方法会在**传感器线程**上与主线程的 [reset] 并发访问内部状态，因此加了
     * [Synchronized]：临界区里只有几次浮点运算，锁开销可以忽略。
     *
     * @param gyro 长度至少 3 的角速度数组（rad/s，设备坐标系）
     * @param timestampNs SensorEvent.timestamp（纳秒）
     * @return 本次要发送的位移或滚轮；若不足 1 像素 / 1 格或无效则返回 null（不发送）
     */
    @Synchronized
    fun update(gyro: FloatArray, timestampNs: Long): Output? {
        if (gyro.size < 3) return null

        // ---- 时间步长 ----
        if (!hasLastTimestamp) {
            hasLastTimestamp = true
            lastTimestampNs = timestampNs
            return null
        }
        var dt = (timestampNs - lastTimestampNs) / 1_000_000_000.0f
        lastTimestampNs = timestampNs
        if (dt <= 0f) return null
        if (dt > MAX_DELTA_SECONDS) dt = MAX_DELTA_SECONDS

        // ---- 死区（先挡噪声，再滤波：慢速旋转不会被滤掉） ----
        // 左右 = Z 轴（绕屏幕法线），上下 = X 轴（绕屏幕水平轴）
        val rawHorizontal = gyro[AXIS_YAW_Z]
        val rawVertical = gyro[AXIS_PITCH_X]
        val gHorizontal = if (abs(rawHorizontal) < deadZone) 0f else rawHorizontal
        val gVertical = if (abs(rawVertical) < deadZone) 0f else rawVertical

        // ---- 一阶低通：alpha 由真实时间步长决定，采样率变化不影响手感 ----
        val alpha = (1.0 - kotlin.math.exp((-dt / tau).toDouble())).toFloat().coerceIn(0.02f, 1f)
        filteredHorizontal += alpha * (gHorizontal - filteredHorizontal)
        filteredVertical += alpha * (gVertical - filteredVertical)

        // 噪声门关闭时，让滤波器把余量收干净，避免「松手后光标继续漂」
        if (gHorizontal == 0f) filteredHorizontal *= 1f - alpha
        if (gVertical == 0f) filteredVertical *= 1f - alpha

        val map = axisMapping
        val kHorizontal = map[0]
        val kVertical = map[1]

        // ---- 滚轮模式：俯仰角增量 → 滚轮格数 ----
        if (scrollMode) {
            // 先按像素方向算（与移动模式同号），再换算成滚轮：光标"向下"的方向 = 内容向下滚 = 负 amount
            val pixelsDown = kVertical * filteredVertical * dt * BASE_PIXELS_PER_RADIAN * sensitivity
            pendingScroll += -pixelsDown / SCROLL_PIXELS_PER_NOTCH
            val whole = pendingScroll.toInt()
            if (whole == 0) return null
            pendingScroll -= whole.toFloat()
            return Output.Scroll(whole)
        }

        // ---- 角度增量 × 灵敏度 → 像素 ----
        val scale = BASE_PIXELS_PER_RADIAN * sensitivity
        pendingDx += kHorizontal * filteredHorizontal * dt * scale
        pendingDy += kVertical * filteredVertical * dt * scale

        val ix = pendingDx.roundToInt()
        val iy = pendingDy.roundToInt()
        if (ix == 0 && iy == 0) return null
        pendingDx -= ix.toFloat()
        pendingDy -= iy.toFloat()
        return Output.Move(ix, iy)
    }

    companion object {
        /** 俯仰轴：陀螺仪数组下标 0 = X 轴（屏幕水平方向），控制**上下** */
        const val AXIS_PITCH_X = 0

        /** 偏航轴：陀螺仪数组下标 2 = Z 轴（垂直屏幕），控制**左右** */
        const val AXIS_YAW_Z = 2

        /** 未使用的滚转轴：下标 1 = Y 轴（手机长轴）。保留常量是为了说明为什么不用它。 */
        const val AXIS_ROLL_UNUSED_Y = 1

        /** 角速度死区：0.02 rad/s（约 1.15°/s），低于此值认为是手抖/零偏噪声 */
        const val DEAD_ZONE_RAD_S = 0.02f

        /** 一阶低通的时间常数：50ms（80% 阶跃响应约 80ms，慢速小动作仍能累加到 1 像素） */
        const val LOW_PASS_TAU_SECONDS = 0.05f

        /** 时间步长上限：100ms（见类注释：截得太狠会把卡顿后的真实运动吃掉） */
        const val MAX_DELTA_SECONDS = 0.1f

        /**
         * 基准增益：1 rad/s 持续 1 秒、灵敏度 1.0x 时约产生 1500 像素位移。
         * 典型手腕转动角速度在 0.5~3 rad/s，因此正常转动大约 750~4500 px/s，
         * 与桌面鼠标手感接近；用户可用灵敏度滑杆在 0.1x~10x 之间调整。
         */
        const val BASE_PIXELS_PER_RADIAN = 1500f

        /**
         * 滚轮模式下多少「像素位移当量」算 1 格滚轮。
         * 复用同一套增益后：灵敏度 1.0x 时约 1.7° 俯仰 = 1 格，30° 俯仰约 18 格，
         * 与真实鼠标滚轮的每格 ~15° 相比稍快，但空中操作需要这个量级才好用。
         */
        const val SCROLL_PIXELS_PER_NOTCH = 45f

        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 10.0f
    }
}
