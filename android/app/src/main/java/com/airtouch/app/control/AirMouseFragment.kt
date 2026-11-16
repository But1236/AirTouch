package com.airtouch.app.control

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
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
 * - 优先 [SensorManager.TYPE_GYROSCOPE]（SENSOR_DELAY_GAME）；
 * - 只有在**标准陀螺仪完全不存在**时才降级到
 *   [SensorManager.TYPE_GYROSCOPE_UNCALIBRATED]（未校准陀螺仪，前三个值是原始角速度，
 *   后三个是零偏估计）。降级原因会在界面上明确写出来，避免用户以为功能正常但手感诡异；
 * - 两者都没有 → 显示中文说明并禁用功能（不崩溃、不发送任何指令）。
 *
 * 生命周期：onResume 注册、onPause 注销；每次注册都调用 [AirMouseEngine.reset]，
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

    /** 当前页是否可见（TabLayout show/hide 由 ControlActivity 同步过来） */
    private var screenVisible = false

    private var sensorRegistered = false
    private var unavailableReason = 0

    private var sensitivity = 1.0f

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
        resolveSensor()
        updateSensorStatus()
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterSensor()
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
        engine.updateAxisMapping()

        val b = _binding
        if (b != null) {
            b.sensitivitySeek.progress = sensitivityToProgress(sensitivity)
            b.checkInvertX.isChecked = engine.invertX
            b.checkInvertY.isChecked = engine.invertY
            updateSensitivityLabel()
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (!visible) {
            // 离开本页时清空累加器，回来时不会突然跳一大段
            engine.reset()
        }
        updateSensorStatus()
    }

    /** 复位：清空引擎的余数/滤波器/时间基准（由复位按钮或工具栏复位调用） */
    fun resetEngine() {
        engine.reset()
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
            // SENSOR_DELAY_GAME 约 50Hz，兼顾流畅与耗电
            sensorRegistered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } catch (e: Exception) {
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
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 只有「本页可见 + 已连接」时才发送，否则只更新内部状态
        if (!screenVisible) return
        val values = event.values
        if (values.size < 3) return
        val delta = engine.update(values, event.timestamp) ?: return
        if (client?.state != AirTouchClient.State.CONNECTED) return
        bridge?.sendMove(delta.dx, delta.dy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不做处理
    }

    // ---------------- 界面 ----------------

    private fun doReset() {
        engine.reset()
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
                val res = if (sensorRegistered && screenVisible && client?.state == AirTouchClient.State.CONNECTED) {
                    R.string.airmouse_status_running
                } else {
                    R.string.airmouse_status_paused
                }
                b.sensorStatus.setText(res)
                b.sensorStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.at_text_dim))
                b.buttonResetFloat.isEnabled = true
            }
        }
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
        private const val SEEK_MAX = 99
        private const val SEEK_STEP = 0.1f

        /** 拖动判定的最小位移（像素），避免手指微抖就触发拖动 */
        private const val TOUCH_SLOP_PX = 12f
    }
}
