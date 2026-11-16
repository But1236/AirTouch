package com.airtouch.app.input

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 空中鼠标的数学核心（纯计算，不依赖 Android 传感器 API，便于单独推理与测试）。
 *
 * 输入：陀螺仪三个轴的角速度（rad/s）+ 事件时间戳（纳秒，来自 SensorEvent.timestamp）。
 * 输出：整数的屏幕像素位移（dx, dy），余数保留在内部累加器里，慢速转动也不会丢步。
 *
 * 坐标与轴向映射（重点，容易搞反）：
 * - Android 传感器坐标系是**设备固定坐标系**（与屏幕内容方向无关）：
 *   X 轴沿屏幕水平方向向右（竖屏下即向右），Y 轴沿屏幕竖直方向向上，
 *   Z 轴垂直于屏幕指向屏幕外侧（指向用户）。三轴构成右手系。
 * - 陀螺仪读数是绕轴的角速度，按右手定则：拇指指向轴的正方向时，四指弯曲方向为角速度正方向。
 * - 因此（手机竖持、屏幕朝向自己）：
 *   * 向右转动手腕 → 绕 Y 轴（向上为 +Y）旋转，右手定则给出**负**的 gyro[1]；
 *     向右移动光标需要正的 dx，所以 kx = -1。
 *   * 顶部向自己倾斜 → 绕 X 轴（向右为 +X）旋转，右手定则给出**正**的 gyro[0]；
 *     Android 屏幕坐标向下为 +dy，因此向下移动光标需要正的 dy，ky = +1。
 * - 上面的 kx/ky 就是 [axisMapping] 里的系数，可由 UI 上的「反向X/反向Y」取反（乘 -1）。
 * - 这里不做 sensor 坐标系到屏幕坐标系的 SensorManager.remapCoordinateSystem 重映射：
 *   空中鼠标的直觉映射本来就依赖握持方式，直接给出可解释的固定映射 + 反相开关，
 *   比自动 remap 更可控（remap 会在手机姿态变化时改变轴向，反而更难用）。
 *
 * 滤波与死区：
 * - [DEAD_ZONE_RAD_S]：角速度绝对值低于 0.02 rad/s 视为抖动，直接丢弃
 *   （手静止时陀螺仪有零偏噪声，典型量级就在 0.005~0.02 rad/s）。
 * - [LOW_PASS_ALPHA]：一阶低通 filtered = 0.8*filtered + 0.2*raw。
 *   alpha 越小越平滑但延迟越大；0.2 在手机上实测足够平滑而几乎无感延迟。
 * - [MAX_DELTA_SECONDS]：两次事件的间隔被截断到 50ms。App 被切到后台或系统卡顿时
 *   dt 可能是好几秒，不截断会一瞬间甩出巨大的位移。
 */
class AirMouseEngine {

    /** 角速度死区（rad/s） */
    private val deadZone = DEAD_ZONE_RAD_S

    /** 低通滤波系数（新值权重） */
    private val alpha = LOW_PASS_ALPHA

    var sensitivity: Float = 1.0f
        set(value) {
            field = if (value.isNaN()) 1.0f else value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        }

    var invertX: Boolean = false
    var invertY: Boolean = false

    /** 轴向映射系数：kx 作用在 gyro[1]（Y 轴），ky 作用在 gyro[0]（X 轴） */
    @Volatile
    var axisMapping: FloatArray = floatArrayOf(-1f, 1f)
        private set

    private var filteredX = 0f
    private var filteredY = 0f
    private var lastTimestampNs = 0L
    private var hasLastTimestamp = false

    /** 像素余数累加器：只有整数部分会被发送，余数留下避免丢步 */
    private var pendingDx = 0f
    private var pendingDy = 0f

    /** 清空全部内部状态（滤波器、时间基准、余数）。复位按钮与 onResume 都要调用。 */
    fun reset() {
        filteredX = 0f
        filteredY = 0f
        lastTimestampNs = 0L
        hasLastTimestamp = false
        pendingDx = 0f
        pendingDy = 0f
    }

    /** 根据反相开关刷新映射系数；UI 切换 CheckBox 时调用。 */
    fun updateAxisMapping() {
        axisMapping = floatArrayOf(
            if (invertX) 1f else -1f,
            if (invertY) -1f else 1f
        )
    }

    /**
     * 处理一次陀螺仪事件。
     * @param gyro 长度至少 3 的角速度数组（rad/s，设备坐标系）
     * @param timestampNs SensorEvent.timestamp（纳秒）
     * @return 本次要发送的整像素位移；若不足 1 像素或无效则返回 null（不发送）
     */
    fun update(gyro: FloatArray, timestampNs: Long): MoveDelta? {
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

        // ---- 低通滤波 ----
        filteredX = alpha * gyro[AXIS_Y] + (1f - alpha) * filteredX
        filteredY = alpha * gyro[AXIS_X] + (1f - alpha) * filteredY

        // ---- 死区 ----
        val rx = if (abs(filteredX) < deadZone) 0f else filteredX
        val ry = if (abs(filteredY) < deadZone) 0f else filteredY
        if (rx == 0f && ry == 0f) return null

        // ---- 角度增量 × 灵敏度 → 像素 ----
        val map = axisMapping
        val kx = map[0]
        val ky = map[1]
        val scale = BASE_PIXELS_PER_RADIAN * sensitivity
        pendingDx += kx * rx * dt * scale
        pendingDy += ky * ry * dt * scale

        val ix = pendingDx.roundToInt()
        val iy = pendingDy.roundToInt()
        if (ix == 0 && iy == 0) return null
        pendingDx -= ix.toFloat()
        pendingDy -= iy.toFloat()
        return MoveDelta(ix, iy)
    }

    /** 一次发送的位移 */
    data class MoveDelta(val dx: Int, val dy: Int)

    companion object {
        /** gyro 数组里 X 轴（绕屏幕水平轴）的下标 */
        const val AXIS_X = 0

        /** gyro 数组里 Y 轴（绕屏幕竖直轴）的下标 */
        const val AXIS_Y = 1

        /** 角速度死区：0.02 rad/s（约 1.15°/s），低于此值认为是手抖/零偏噪声 */
        const val DEAD_ZONE_RAD_S = 0.02f

        /** 一阶低通：新值权重 0.2 */
        const val LOW_PASS_ALPHA = 0.2f

        /** 时间步长上限：50ms */
        const val MAX_DELTA_SECONDS = 0.05f

        /**
         * 基准增益：1 rad/s 持续 1 秒、灵敏度 1.0x 时约产生 1500 像素位移。
         * 典型手腕转动角速度在 0.5~3 rad/s，因此正常转动大约 750~4500 px/s，
         * 与桌面鼠标手感接近；用户可用灵敏度滑杆在 0.1x~10x 之间调整。
         */
        const val BASE_PIXELS_PER_RADIAN = 1500f

        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 10.0f
    }
}
