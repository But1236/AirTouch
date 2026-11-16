package com.airtouch.app.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.FragmentTouchpadBinding
import com.airtouch.app.input.TouchpadView
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.settings.SettingsStore

/**
 * 触摸板页。
 *
 * 只做三件事：
 * 1. 把 [TouchpadView] 的手势回调转成 [ControlBridge] 上的协议指令；
 * 2. 灵敏度 SeekBar（0.5x~3.0x）读写在 [SettingsStore]；
 * 3. 用文字提示当前模式（普通 / 滚轮模式），避免用户搞不清单指滑动为什么在滚屏。
 */
class TouchpadFragment : Fragment(), TouchpadView.Listener {

    private var _binding: FragmentTouchpadBinding? = null
    private val binding: FragmentTouchpadBinding
        get() = _binding ?: throw IllegalStateException("TouchpadFragment 的视图尚未创建")

    private var client: AirTouchClient? = null
    private var bridge: ControlBridge? = null
    private var settings: SettingsStore? = null

    /** 当前页是否可见（由 ControlActivity 在 show/hide 时同步） */
    private var screenVisible = false

    private var sensitivity = 1.0f
    private var scrollMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTouchpadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.touchpadView.listener = this
        binding.touchpadView.isClickable = true
        binding.touchpadView.isFocusable = true

        val store = settings
        sensitivity = store?.touchpadSensitivity ?: 1.0f
        scrollMode = store?.scrollMode ?: false

        binding.touchpadView.sensitivity = sensitivity
        binding.touchpadView.scrollMode = scrollMode

        binding.sensitivitySeek.max = SEEK_MAX
        binding.sensitivitySeek.progress = sensitivityToProgress(sensitivity)
        binding.sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progressToSensitivity(progress)
                sensitivity = value
                binding.touchpadView.sensitivity = value
                binding.sensitivityValue.text = getString(R.string.touchpad_sensitivity_value, value)
                if (fromUser) {
                    settings?.touchpadSensitivity = value
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                settings?.touchpadSensitivity = sensitivity
            }
        })

        updateSensitivityLabel()
        updateModeHint()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.touchpadView.listener = null
        _binding = null
    }

    // ---------------- 由 ControlActivity 调用（主线程） ----------------

    fun attach(client: AirTouchClient, bridge: ControlBridge, settings: SettingsStore) {
        this.client = client
        this.bridge = bridge
        this.settings = settings
        val store = settings
        scrollMode = store.scrollMode
        sensitivity = store.touchpadSensitivity
        val b = _binding
        if (b != null) {
            b.touchpadView.scrollMode = scrollMode
            b.touchpadView.sensitivity = sensitivity
            b.sensitivitySeek.progress = sensitivityToProgress(sensitivity)
            updateSensitivityLabel()
            updateModeHint()
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
    }

    /** 由 ControlActivity 的滚轮模式按钮调用（同样会写入 SettingsStore） */
    fun setScrollMode(enabled: Boolean) {
        scrollMode = enabled
        val b = _binding ?: return
        b.touchpadView.scrollMode = enabled
        updateModeHint()
    }

    // ---------------- TouchpadView.Listener ----------------

    override fun onMove(dx: Int, dy: Int) {
        // 触摸板不在前台时不发送，避免隐藏页仍然产生输入
        if (!screenVisible) return
        bridge?.sendMove(dx, dy)
    }

    override fun onClick(button: String) {
        if (!screenVisible) return
        bridge?.sendClick(button)
    }

    override fun onScroll(amount: Int) {
        if (!screenVisible) return
        bridge?.sendScroll(amount)
    }

    // ---------------- 内部 ----------------

    private fun updateSensitivityLabel() {
        binding.sensitivityValue.text = getString(R.string.touchpad_sensitivity_value, sensitivity)
    }

    private fun updateModeHint() {
        binding.touchpadHint.setText(
            if (scrollMode) R.string.touchpad_hint_scroll else R.string.touchpad_hint_move
        )
    }

    /** 0.5x~3.0x 映射到 0..SEEK_MAX 的整数刻度（0.05x 一档） */
    private fun sensitivityToProgress(value: Float): Int {
        val v = value.coerceIn(TouchpadView.MIN_SENSITIVITY, TouchpadView.MAX_SENSITIVITY)
        return ((v - TouchpadView.MIN_SENSITIVITY) / SEEK_STEP).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun progressToSensitivity(progress: Int): Float {
        val v = TouchpadView.MIN_SENSITIVITY + progress.coerceIn(0, SEEK_MAX) * SEEK_STEP
        return v.coerceIn(TouchpadView.MIN_SENSITIVITY, TouchpadView.MAX_SENSITIVITY)
    }

    companion object {
        /** 0.5x ~ 3.0x，0.05x 一档 → 50 档 */
        private const val SEEK_MAX = 50
        private const val SEEK_STEP = 0.05f
    }
}
