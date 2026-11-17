package com.airtouch.app.control

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.FragmentAirmouseBinding
import com.airtouch.app.input.AirMouseEngine
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.settings.SettingsStore
import kotlin.math.roundToInt

/**
 * 空中鼠标页。
 *
 * 传感器策略：
 * - 优先 [SensorManager.TYPE_GYROSCOPE]，采样周期 10ms（约 100Hz）；
 * - 只有在**标准陀螺仪完全不存在**时才降级到
 *   [SensorManager.TYPE_GYROSCOPE_UNCALIBRATED]（未校准陀螺仪，前三个值是原始角速度，
 *   后三个是零偏估计）。降级原因会在界面上明确写出来，避免用户以为功能正常但手感诡异；
 * - 两者都没有 → 显示中文说明并禁用功能（不崩溃、不发送任何指令）。
 *
 * **回调线程（v2 的关键修复）**：传感器注册在专用的 [HandlerThread] 上，而不是主线程。
 * 旧实现用 `registerListener(listener, sensor, delay)` 注册，回调落在主线程：只要界面在
 * 做任何事（Tab 切换、状态栏刷新、Toast 动画、GC），陀螺仪事件就会被延迟甚至丢弃。
 * 现象正是「手机在动、光标不动，而且不是抖动而是停顿」——因为停顿发生在事件送达之前。
 * 放到独立线程后，界面卡顿不再影响输入通路。
 *
 * 生命周期：onResume 注册、onPause/onDestroyView 注销；每次注册都调用 [AirMouseEngine.reset]，
 * 防止暂停期间残留的余数/时间基准把光标「甩」出去。
 */
class AirMouseFragment : Fragment(), SensorEventListener {

    private var _binding: FragmentAirmouseBinding? = null
    private val binding: FragmentAirmouseBinding
        get() = _binding ?: throw IllegalStateException("AirMouseFragment 的视图尚未创建")

    private var client: AirTouchClient? = null
    private var bridge: ControlBridge? = null
    private var settings: SettingsStore? = null

    private val engine = AirMouseEngine()

    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null

    /** 传感器回调专用线程（主线程卡顿不再影响输入） */
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    /** 当前页是否可见（TabLayout show/hide 由 ControlActivity 同步过来），跨线程读写 */
    @Volatile
    private var screenVisible = false

    private var sensorRegistered = false
    private var unavailableReason = 0

    private var sensitivity = 1.0f

    // ---- 传感器侧的实时统计（用于在界面上证明「事件真的在来」）----
    @Volatile
    private var sensorEventCount = 0

    @Volatile
    private var sensorLastRateHz = 0.0

    /** 每秒真正发出去的帧数（= 光标在电脑上更新的次数），与传感器采样率区分开 */
    @Volatile
    private var lastSentRateHz = 0.0

    /** 手机侧发送间隔的均值/最大值（毫秒，只统计刚过去的一秒） */
    @Volatile
    private var lastSendIntervalMeanMs = 0.0

    @Volatile
    private var lastSendIntervalMaxMs = 0.0

    private var sendIntervalSumMs = 0.0
    private var sendIntervalCount = 0
    private var sendIntervalMaxMs = 0.0

    /**
     * 发送限速间隔（纳秒）。按服务端报告的屏幕刷新率算：60Hz 屏发 90 帧/秒就够（每次刷新都有新位置），
     * 高刷屏最多 144 帧/秒。以前 144~500Hz 的原始帧率只是白占空口。
     */
    @Volatile
    private var sendIntervalNs = 1_000_000_000L / 144

    /** 攒着还没发的位移（位移可加，攒起来发不丢运动） */
    private var pendingSendDx = 0
    private var pendingSendDy = 0
    private var lastMoveSendNs = 0L

    private var rateWindowStartNs = 0L
    private var rateWindowCount = 0
    private var rateWindowSent = 0

    @Volatile
    private var lastMovesSent = 0L

    /** 滚轮模式下已发送的滚轮格数（绝对值累计，用于界面显示） */
    @Volatile
    private var lastScrollsSent = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateUpdater = object : Runnable {
        override fun run() {
            updateSensorStatus()
            if (screenVisible) {
                mainHandler.postDelayed(this, STATUS_REFRESH_MS)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirmouseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val store = settings
        sensitivity = store?.airMouseSensitivity ?: 1.0f
        engine.sensitivity = sensitivity
        engine.invertX = store?.airMouseInvertX ?: false
        engine.invertY = store?.airMouseInvertY ?: false
        engine.scrollMode = store?.airMouseScrollMode ?: false
        engine.updateAxisMapping()

        binding.sensitivitySeek.max = SEEK_MAX
        binding.sensitivitySeek.progress = sensitivityToProgress(sensitivity)
        binding.sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progressToSensitivity(progress)
                sensitivity = value
                engine.sensitivity = value
                binding.sensitivityValue.text = getString(R.string.airmouse_sensitivity_value, value)
                if (fromUser) settings?.airMouseSensitivity = value
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                settings?.airMouseSensitivity = sensitivity
            }
        })

        binding.checkInvertX.isChecked = engine.invertX
        binding.checkInvertY.isChecked = engine.invertY
        binding.checkInvertX.setOnCheckedChangeListener { _: android.widget.CompoundButton, checked: Boolean ->
            engine.invertX = checked
            engine.updateAxisMapping()
            settings?.airMouseInvertX = checked
        }
        binding.checkInvertY.setOnCheckedChangeListener { _: android.widget.CompoundButton, checked: Boolean ->
            engine.invertY = checked
            engine.updateAxisMapping()
            settings?.airMouseInvertY = checked
        }

        binding.buttonResetFloat.setOnClickListener { doReset() }
        setupDraggableResetButton()

        updateSensitivityLabel()
        updateModeLabel()
        resolveSensor()
        updateSensorStatus()
    }

    /** 页面上明确写清当前是「移动光标」还是「滚轮」，避免滚轮模式被误认为鼠标失灵 */
    private fun updateModeLabel() {
        val b = _binding ?: return
        val enabled = engine.scrollMode
        b.sensorMode.text = getString(
            if (enabled) R.string.airmouse_mode_scroll else R.string.airmouse_mode_move
        )
        b.sensorMode.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.at_primary else R.color.at_text_dim
            )
        )
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台都清空累加器：暂停期间的旧余数不能用来移动光标
        engine.reset()
        registerSensor()
        updateSensorStatus()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensor()
        engine.reset()
        mainHandler.removeCallbacks(rateUpdater)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterSensor()
        mainHandler.removeCallbacks(rateUpdater)
        _binding = null
    }

    // ---------------- 由 ControlActivity 调用（主线程） ----------------

    fun attach(client: AirTouchClient, bridge: ControlBridge, settings: SettingsStore) {
        this.client = client
        this.bridge = bridge
        this.settings = settings
        val store = settings
        sensitivity = store.airMouseSensitivity
        engine.sensitivity = sensitivity
        engine.invertX = store.airMouseInvertX
        engine.invertY = store.airMouseInvertY
        engine.scrollMode = store.airMouseScrollMode
        engine.updateAxisMapping()

        val b = _binding
        if (b != null) {
            b.sensitivitySeek.progress = sensitivityToProgress(sensitivity)
            b.checkInvertX.isChecked = engine.invertX
            b.checkInvertY.isChecked = engine.invertY
            updateSensitivityLabel()
            updateModeLabel()
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (!visible) {
            // 离开本页时清空累加器，回来时不会突然跳一大段
            engine.reset()
            clearPendingSend()
            mainHandler.removeCallbacks(rateUpdater)
        } else if (sensorRegistered) {
            mainHandler.removeCallbacks(rateUpdater)
            mainHandler.postDelayed(rateUpdater, STATUS_REFRESH_MS)
        }
        updateSensorStatus()
    }

    /** 复位：清空引擎的余数/滤波器/时间基准（由复位按钮或工具栏复位调用） */
    fun resetEngine() {
        engine.reset()
        clearPendingSend()
        lastScrollsSent = 0
        lastMovesSent = 0
    }

    /** 清掉「攒着还没发的位移」与发送间隔基准（换页/复位/重连时都要清，否则会突然跳一段） */
    private fun clearPendingSend() {
        pendingSendDx = 0
        pendingSendDy = 0
        lastMoveSendNs = 0L
    }

    /**
     * 滚轮模式（由 ControlActivity 的工具栏按钮在本页可见时调用）。
     * 打开后：手机上下俯仰 = 滚轮，不再移动光标。
     */
    fun setScrollMode(enabled: Boolean) {
        engine.scrollMode = enabled
        // 切换时清掉余数：否则滚轮模式下残留的像素余量会在切回时突然跳一段
        engine.reset()
        clearPendingSend()
        lastMovesSent = 0
        lastScrollsSent = 0
        updateModeLabel()
        updateSensorStatus()
    }

    // ---------------- 传感器 ----------------

    private fun resolveSensor() {
        val ctx = context ?: return
        if (sensorManager == null) {
            sensorManager = ContextCompat.getSystemService(ctx, SensorManager::class.java)
        }
        val sm = sensorManager
        if (sm == null) {
            gyroSensor = null
            unavailableReason = R.string.airmouse_no_sensor
            return
        }
        val plain = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (plain != null) {
            gyroSensor = plain
            unavailableReason = 0
            return
        }
        // 降级：未校准陀螺仪（只有标准陀螺仪缺失时才用）
        val uncalibrated = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        if (uncalibrated != null) {
            gyroSensor = uncalibrated
            unavailableReason = R.string.airmouse_using_uncalibrated
            return
        }
        gyroSensor = null
        unavailableReason = R.string.airmouse_no_sensor
    }

    private fun registerSensor() {
        if (sensorRegistered) return
        val sm = sensorManager ?: return
        val sensor = gyroSensor ?: return
        try {
            // 专用线程 + 尽量快的采样周期：主线程再忙也不会让陀螺仪事件排队
            val existing = sensorThread
            val thread = existing ?: HandlerThread("airtouch-gyro").also {
                it.start()
                sensorThread = it
            }
            val handler = Handler(thread.looper)
            sensorHandler = handler

            sensorEventCount = 0
            rateWindowCount = 0
            rateWindowSent = 0
            rateWindowStartNs = 0L

            // 请求「最快，但不快于 500Hz」；传感器自己支持的更慢时按它自己的来（minDelay 可能是 0，
            // 表示设备不报告，此时仍然用 2ms）。
            val requestedPeriodUs = maxOf(MIN_SENSOR_PERIOD_US, sensor.minDelay)

            sensorRegistered = sm.registerListener(
                this,
                sensor,
                requestedPeriodUs,
                handler
            )

            if (sensorRegistered) {
                mainHandler.removeCallbacks(rateUpdater)
                mainHandler.postDelayed(rateUpdater, STATUS_REFRESH_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "注册陀螺仪失败", e)
            sensorRegistered = false
        }
    }

    private fun unregisterSensor() {
        val sm = sensorManager ?: return
        if (!sensorRegistered) return
        try {
            sm.unregisterListener(this)
        } catch (e: Exception) {
            // 忽略：注销失败不影响功能
        }
        sensorRegistered = false

        // 线程要退出，否则每次切页都会留下一个空转的 Looper 线程
        sensorHandler = null
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 运行在 airtouch-gyro 线程上
        sensorEventCount++
        rateWindowCount++
        val nowNs = System.nanoTime()
        if (rateWindowStartNs == 0L) {
            rateWindowStartNs = nowNs
        } else if (nowNs - rateWindowStartNs >= 1_000_000_000L) {
            sensorLastRateHz = rateWindowCount * 1_000_000_000.0 / (nowNs - rateWindowStartNs)
            // 「发出去多少帧/秒」才是光标在屏幕上更新多少次 —— 陀螺仪采样率不等于它。
            // 慢速移动时每帧位移不足 1 像素，引擎会攒够 1 像素才发，于是这个数会明显低于采样率，
            // 用户看到的"离散、不连续"就是这两个数的差。
            lastSentRateHz = rateWindowSent * 1_000_000_000.0 / (nowNs - rateWindowStartNs)
            lastSendIntervalMeanMs = if (sendIntervalCount > 0) sendIntervalSumMs / sendIntervalCount else 0.0
            lastSendIntervalMaxMs = sendIntervalMaxMs
            rateWindowStartNs = nowNs
            rateWindowCount = 0
            rateWindowSent = 0
            sendIntervalSumMs = 0.0
            sendIntervalCount = 0
            sendIntervalMaxMs = 0.0
        }

        // 只有「本页可见 + 已连接」时才发送，否则只更新内部状态
        if (!screenVisible) return
        val values = event.values
        if (values.size < 3) return

        val output = engine.update(values, event.timestamp) ?: return
        val c = client ?: return
        if (c.state != AirTouchClient.State.CONNECTED) return

        // 直接在传感器线程上发送：OkHttp 的 WebSocket.send 是线程安全的，绕开主线程才不会
        // 因为界面卡顿把输入堵在队列里（这正是「手机在动、光标不动」的主要来源）。
        when (output) {
            is AirMouseEngine.Output.Move -> {
                // 位移是可加的，所以「攒起来一起发」不会丢任何运动：这里的限速只减少 TCP 段
                // （= Wi-Fi 空口占用），不改变光标最终位置。60Hz 屏幕每秒只需要约 90 次更新，
                // 之前 144~500Hz 的原始帧率只会把空口塞满、让 Wi-Fi 更容易成串积压。
                pendingSendDx += output.dx
                pendingSendDy += output.dy
                flushPendingMove(c, nowNs)
            }

            is AirMouseEngine.Output.Scroll ->
                if (c.sendScroll(output.amount)) {
                    lastScrollsSent += kotlin.math.abs(output.amount)
                    rateWindowSent++
                }
        }
    }

    /**
     * 到达发送时刻就把攒下的位移一次性发出去。
     *
     * 记录**发送间隔**（均值/最大值）是刻意的：手机侧的"发送间隔"与电脑侧的"到达间隔"一对比，
     * 就能判断停顿发生在手机（两者都大）还是链路（只有到达间隔大）。这是不需要调试器就能定位的办法。
     */
    private fun flushPendingMove(c: AirTouchClient, nowNs: Long) {
        if (pendingSendDx == 0 && pendingSendDy == 0) return
        if (nowNs - lastMoveSendNs < sendIntervalNs) return

        val dx = pendingSendDx
        val dy = pendingSendDy
        if (!c.sendMove(dx, dy)) return

        pendingSendDx = 0
        pendingSendDy = 0

        if (lastMoveSendNs != 0L) {
            val intervalMs = (nowNs - lastMoveSendNs) / 1_000_000.0
            sendIntervalSumMs += intervalMs
            sendIntervalCount++
            if (intervalMs > sendIntervalMaxMs) sendIntervalMaxMs = intervalMs
        }

        lastMoveSendNs = nowNs
        lastMovesSent++
        rateWindowSent++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不做处理
    }

    // ---------------- 界面 ----------------

    private fun doReset() {
        engine.reset()
        pendingSendDx = 0
        pendingSendDy = 0
        lastMoveSendNs = 0L
        lastMovesSent = 0
        lastScrollsSent = 0
        bridge?.sendReset()
    }

    /**
     * 浮动的复位按钮可拖动：用 OnTouchListener 记录按下位置，按位移更新
     * ConstraintLayout 的 margin，并用 requestLayout() 重新排版。
     * 只有真正的「点击」（位移小于阈值）才触发复位，拖动不会误触发。
     */
    private fun setupDraggableResetButton() {
        val button = binding.buttonResetFloat
        val container = binding.root as? ConstraintLayout
        button.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var lastRawX = 0f
            private var lastRawY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        lastRawX = downRawX
                        lastRawY = downRawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        if (!moved &&
                            (Math.abs(event.rawX - downRawX) > TOUCH_SLOP_PX ||
                                Math.abs(event.rawY - downRawY) > TOUCH_SLOP_PX)
                        ) {
                            moved = true
                        }
                        if (moved && container != null) {
                            val lp = v.layoutParams as? ConstraintLayout.LayoutParams
                            if (lp != null) {
                                lp.bottomMargin = (lp.bottomMargin - dy.roundToInt()).coerceAtLeast(0)
                                lp.marginEnd = (lp.marginEnd - dx.roundToInt()).coerceAtLeast(0)
                                v.layoutParams = lp
                                v.requestLayout()
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            v.performClick()
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun updateSensorStatus() {
        val b = _binding ?: return
        when {
            unavailableReason == R.string.airmouse_no_sensor -> {
                b.sensorStatus.setText(R.string.airmouse_no_sensor)
                b.sensorStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.at_error))
                b.buttonResetFloat.isEnabled = false
            }

            unavailableReason == R.string.airmouse_using_uncalibrated -> {
                b.sensorStatus.setText(R.string.airmouse_using_uncalibrated)
                b.sensorStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.at_text_dim))
                b.buttonResetFloat.isEnabled = true
            }

            else -> {
                val connected = client?.state == AirTouchClient.State.CONNECTED
                if (sensorRegistered && screenVisible && connected) {
                    // 刷新发送限速：60Hz 屏发 90 帧/秒、高刷屏最多 144 帧/秒（见 sendIntervalNs 注释）
                    updateSendInterval()
                    // 把采样率、实发帧率、发送间隔与已发送帧数显示出来：用户据此判断
                    // 「传感器到底有没有在出货」「手机自己有没有卡」，这比「陀螺仪工作中」有用得多。
                    b.sensorStatus.text = if (engine.scrollMode) {
                        getString(
                            R.string.airmouse_status_scroll_rate,
                            sensorLastRateHz,
                            lastScrollsSent
                        )
                    } else {
                        getString(
                            R.string.airmouse_status_running_rate,
                            sensorLastRateHz,
                            lastSentRateHz,
                            lastSendIntervalMeanMs,
                            lastSendIntervalMaxMs,
                            lastMovesSent
                        )
                    }

                    // Wi-Fi 低延迟锁没拿到的话必须说出来：它直接关系到「帧是不是成串到达」
                    bridge?.wifiLatencyNote()?.let { note ->
                        b.sensorStatus.append("\n")
                        b.sensorStatus.append(note)
                    }
                } else {
                    b.sensorStatus.setText(R.string.airmouse_status_paused)
                }
                b.sensorStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.at_text_dim))
                b.buttonResetFloat.isEnabled = true
            }
        }
    }

    /** 按服务端报告的屏幕刷新率决定发送限速（屏幕未知时按 144 帧/秒） */
    private fun updateSendInterval() {
        val displayHz = client?.session?.displayHz ?: 0
        val targetHz = when {
            displayHz in 1..75 -> SEND_HZ_ON_60HZ_PANEL
            else -> SEND_HZ_MAX
        }
        sendIntervalNs = 1_000_000_000L / targetHz
    }

    private fun updateSensitivityLabel() {
        binding.sensitivityValue.text = getString(R.string.airmouse_sensitivity_value, sensitivity)
    }

    /** 0.1x ~ 10.0x，0.1x 一档 → 99 档 */
    private fun sensitivityToProgress(value: Float): Int {
        val v = value.coerceIn(AirMouseEngine.MIN_SENSITIVITY, AirMouseEngine.MAX_SENSITIVITY)
        return ((v - AirMouseEngine.MIN_SENSITIVITY) / SEEK_STEP).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun progressToSensitivity(progress: Int): Float {
        val v = AirMouseEngine.MIN_SENSITIVITY + progress.coerceIn(0, SEEK_MAX) * SEEK_STEP
        return v.coerceIn(AirMouseEngine.MIN_SENSITIVITY, AirMouseEngine.MAX_SENSITIVITY)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (sensorManager == null) {
            sensorManager = ContextCompat.getSystemService(context, SensorManager::class.java)
        }
    }

    companion object {
        private const val TAG = "AirMouseFragment"

        private const val SEEK_MAX = 99
        private const val SEEK_STEP = 0.1f

        /**
         * 陀螺仪采样周期上限（微秒）：**不超过 2ms，也就是至少 500Hz**。
         *
         * 为什么从 10ms(100Hz) 提到这里：光标只能整物理像素跳，所以慢速移动时的观感由
         * 「攒够 1 像素要多久」决定 —— 采样越密，1 像素的步进越均匀，看起来才连续。
         * 电脑侧实测（`tools/cursor-rate.ps1`）`SetCursorPos` 到 1000Hz 都能 100% 落地，
         * 服务端不构成瓶颈；500Hz 在 Wi-Fi 上也只有约 20KB/s。
         *
         * 实际周期取 `max(2ms, 传感器支持的最快周期)`：手机只支持 200Hz 时就老老实实按 200Hz，
         * 界面上的「陀螺仪 N Hz」显示的是**实测**值，不是这里的请求值。
         */
        private const val MIN_SENSOR_PERIOD_US = 2_000

        /** 状态文本刷新间隔（只刷文字，不参与输入通路） */
        private const val STATUS_REFRESH_MS = 500L

        /** 60Hz 屏的发送帧率：比面板略高，保证每次刷新都有新位置，同时只有 144Hz 的 3/5 空口占用 */
        private const val SEND_HZ_ON_60HZ_PANEL = 90

        /** 高刷屏（或屏幕刷新率未知）时的发送帧率上限 */
        private const val SEND_HZ_MAX = 144

        /** 拖动判定的最小位移（像素），避免手指微抖就触发拖动 */
        private const val TOUCH_SLOP_PX = 12f
    }
}
