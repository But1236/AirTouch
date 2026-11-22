package com.airtouch.app.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.FragmentTouchpadBinding
import com.airtouch.app.input.TouchpadView
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.net.Protocol
import com.airtouch.app.net.ZoomStatus
import com.airtouch.app.settings.SettingsStore

/**
 * 触摸板页。
 *
 * 只做四件事：
 * 1. 把 [TouchpadView] 的手势回调转成 [ControlBridge] 上的协议指令；
 * 2. 灵敏度 SeekBar（0.5x~3.0x）读写在 [SettingsStore]；
 * 3. 用文字提示当前模式（普通 / 滚轮模式），避免用户搞不清单指滑动为什么在滚屏；
 * 4. 「原生触控缩放」开关 + 电脑端缩放后端实时状态 + 「重新检测」按钮。
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

    /** 电脑端最近一次报告的缩放后端；null = 还没收到 */
    private var zoomStatus: ZoomStatus? = null

    /**
     * 本地开关的期望状态。
     *
     * 与 [zoomStatus] 分开维护：开关是「我想怎样」，状态是「电脑端实际怎样」。
     * 用户拨开关时先乐观地更新本地，再发 `zoom_mode`；电脑端推回来的 `preference` 才是权威值
     * （两台手机连同一台电脑时，另一台改了偏好这里也会跟着变）。
     */
    private var zoomSwitchOn = false

    /** 引导弹窗是否已经因为「开关打开但电脑端没有触控数字化仪」弹过（每次打开只烦用户一次） */
    private var guidePending = false

    /** 程序化改开关时抑制回调，避免「回显」又变成一次用户操作 */
    private var suppressSwitchCallback = false

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

        // 开关初值：用户拨过就以他的偏好为准；没拨过就跟随电脑端的实际状态
        // （这样刚连上一台有触摸屏的电脑时，开关不会显示成「关」而让人以为没生效）
        zoomSwitchOn = when (store?.zoomPreference) {
            Protocol.ZOOM_PREF_WHEEL -> false
            Protocol.ZOOM_PREF_NATIVE -> true
            else -> zoomStatus?.isNative ?: false
        }
        binding.zoomNativeSwitch.isChecked = zoomSwitchOn
        binding.zoomNativeSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            zoomSwitchOn = checked
            // 打开开关 = 优先原生触控；关掉 = 强制滚轮回退（即使电脑有触摸屏）。
            // 偏好写进本地设置，之后每次连接都会重发一遍。
            val mode = if (checked) Protocol.ZOOM_PREF_NATIVE else Protocol.ZOOM_PREF_WHEEL
            settings?.zoomPreference = mode
            bridge?.sendZoomMode(mode)
            // 电脑端状态回来了才知道到底能不能用原生；先立个「待引导」标记，
            // 这样「开关打开但电脑端没触控栈」时弹一次引导，而不是每个状态帧都弹。
            guidePending = checked
            updateZoomStatusView()
            val s = zoomStatus
            if (checked && s != null && !s.available) {
                guidePending = false
                showZoomGuide()
            }
        }
        binding.zoomRefresh.setOnClickListener { requestZoomProbe(userInitiated = true) }
        updateZoomStatusView()
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
        zoomSwitchOn = when (store.zoomPreference) {
            Protocol.ZOOM_PREF_WHEEL -> false
            Protocol.ZOOM_PREF_NATIVE -> true
            else -> zoomStatus?.isNative ?: false
        }
        val b = _binding
        if (b != null) {
            b.touchpadView.scrollMode = scrollMode
            b.touchpadView.sensitivity = sensitivity
            b.sensitivitySeek.progress = sensitivityToProgress(sensitivity)
            suppressSwitchCallback = true
            b.zoomNativeSwitch.isChecked = zoomSwitchOn
            suppressSwitchCallback = false
            updateSensitivityLabel()
            updateModeHint()
            updateZoomStatusView()
        }
    }

    /**
     * 电脑端缩放后端状态变化（由 ControlActivity 在主线程转达）。
     *
     * 这个回调是「电脑端实际在用哪个后端」的唯一判据：状态文字、开关回显、切换成功的 Toast、
     * 「开关开着但电脑端没有触控数字化仪」的引导弹窗、以及热切换后本地手势的作废，全部由它驱动。
     */
    fun onZoomStatusChanged(status: ZoomStatus) {
        val wasNative = zoomStatus?.isNative == true
        zoomStatus = status

        val b = _binding
        if (b != null) {
            // 电脑端的 preference 是权威值（另一台手机或电脑端窗口改过也会推到这里）
            val desired = when (status.preference) {
                Protocol.ZOOM_PREF_WHEEL -> false
                Protocol.ZOOM_PREF_NATIVE -> true
                else -> status.isNative
            }
            zoomSwitchOn = desired
            if (b.zoomNativeSwitch.isChecked != desired) {
                suppressSwitchCallback = true
                b.zoomNativeSwitch.isChecked = desired
                suppressSwitchCallback = false
            }
            updateZoomStatusView()
            if (status.isNative != wasNative) {
                // 后端热切换会作废进行中的那一次捏合：服务端先把旧后端的触点/Ctrl 收干净，
                // 且到下一次 down 之间的 move 一律忽略。本地必须一起丢掉捏合状态，否则手指
                // 还按着、界面以为在捏，用户会觉得「捏合突然不响应，得重新捏一次」。
                // cancelGesture() 补发的 up 在服务端是空操作，安全。
                b.touchpadView.cancelGesture()
            }
            if (status.isNative && !wasNative) {
                // 刚刚切到原生触控：这正是用户插上触控屏、点了「重新检测」之后想看到的确认
                if (status.preference != Protocol.ZOOM_PREF_WHEEL) {
                    toast(getString(R.string.touchpad_zoom_activated))
                }
            }
            // 用户刚打开开关 / 刚点过重新检测，而电脑端仍然没有触控数字化仪 → 弹一次引导
            if (guidePending && zoomSwitchOn && !status.available) {
                guidePending = false
                showZoomGuide()
            } else if (status.available || !zoomSwitchOn) {
                guidePending = false
            }
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (visible) {
            updateModeHint()
        } else {
            // 切走时如果正拖着东西，必须把左键抬起来（手指还按着就切页时收不到 ACTION_UP）
            _binding?.touchpadView?.cancelGesture()
        }
    }

    /** 由 ControlActivity 的滚轮模式按钮调用（同样会写入 SettingsStore） */
    fun setScrollMode(enabled: Boolean) {
        scrollMode = enabled
        val b = _binding ?: return
        b.touchpadView.scrollMode = enabled
        updateModeHint()
    }

    // ---------------- TouchpadView.Listener ----------------
    //
    // 这里**刻意不做 screenVisible 判断**：本页被 hide() 之后视图是 GONE，根本收不到触摸事件，
    // 触摸事件本身就是「用户正在这一页操作」的最可靠证据。早期版本额外加了一道 screenVisible
    // 门禁，一旦某个路径漏了 setScreenVisible(true)（或滚轮模式被别的页打开），
    // 就会出现「触摸板完全没反应」这种最难排查的现象。

    override fun onMove(dx: Int, dy: Int) {
        bridge?.sendMove(dx, dy)
    }

    override fun onClick(button: String) {
        bridge?.sendClick(button)
    }

    override fun onScroll(delta: Int, horizontal: Boolean) {
        bridge?.sendScroll(delta, horizontal)
    }

    /**
     * 「双击后按住不放」的拖拽：左键按下 / 抬起（协议 §2.1 的 `button`）。
     *
     * 返回值很重要：按下失败（没连接）时 View 会放弃拖拽状态。这里不做任何额外判断——
     * 「有没有连上」只有 bridge 知道，View 不该自己猜。
     */
    override fun onButton(button: String, down: Boolean): Boolean =
        bridge?.sendButton(button, down) ?: false

    /**
     * 双指捏合 / 张开（协议 §2.1 的 `touch`）。
     *
     * 这里只把几何转发出去：**用哪个 Windows API 是服务端的事**——有触摸输入栈的机器走
     * `InjectTouchInput` 注入真双指触摸（应用得到和真实触摸屏一样的无级缩放），
     * 没有触摸栈的机器由服务端自动回退到 Ctrl + 高精度滚轮。手机端不需要知道对面有没有触摸屏。
     */
    override fun onPinch(phase: String, scale: Double, dx: Int, dy: Int) {
        bridge?.sendPinch(phase, scale, dx, dy)
    }

    /** 三指手势 → 系统快捷键，映射表在 [ControlBridge.sendThreeFinger] */
    override fun onThreeFinger(action: String) {
        bridge?.sendThreeFinger(action)
    }

    override fun onPause() {
        super.onPause()
        // 手指还按在屏幕上时锁屏/切走：ACTION_CANCEL 通常已经处理了，这里再兜一次（幂等）
        _binding?.touchpadView?.cancelGesture()
    }

    // ---------------- 原生触控缩放 ----------------

    /**
     * 请求电脑端重新探测触控能力（协议 §2.1 的 `zoom_probe`）。
     *
     * @param userInitiated 是用户点的按钮（要给 Toast 反馈）；程序化调用（例如从引导弹窗外）
     *   不打扰用户
     */
    private fun requestZoomProbe(userInitiated: Boolean) {
        if (client?.state != AirTouchClient.State.CONNECTED) {
            if (userInitiated) toast(getString(R.string.touchpad_zoom_probe_offline))
            return
        }
        guidePending = zoomSwitchOn
        bridge?.sendZoomProbe()
        if (userInitiated) toast(getString(R.string.touchpad_zoom_probe_sent))
    }

    /**
     * 刷新状态文字与开关回显。
     *
     * 文字刻意写「电脑端实际在用哪个后端」而不是「你选了什么」：用户点完重新检测，
     * 最需要确认的就是这句到底有没有变成「已激活原生触控栈」。
     */
    private fun updateZoomStatusView() {
        val b = _binding ?: return
        val s = zoomStatus
        b.zoomStatus.text = when {
            s == null -> getString(R.string.touchpad_zoom_status_unknown)
            s.isNative -> getString(R.string.touchpad_zoom_status_native)
            s.preference == Protocol.ZOOM_PREF_WHEEL -> getString(R.string.touchpad_zoom_status_off)
            s.maxTouches > 0 -> getString(R.string.touchpad_zoom_status_wheel_touches, s.maxTouches)
            else -> getString(R.string.touchpad_zoom_status_wheel)
        }
        b.zoomStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (s?.isNative == true) R.color.at_primary else R.color.at_text_dim
            )
        )
    }

    /**
     * 「电脑端没有触控数字化仪」的引导：说明自动回退到滚轮兼容模式，以及怎样拿到原生触控。
     *
     * 只讲三件事：双指缩放会自动挑后端、现在为什么是滚轮、以及插上/启用触控设备后点「重新检测」
     * 就能自动热切换（**不需要装任何驱动**）。最后附上电脑端给出的技术原因，方便用户／我排查。
     */
    private fun showZoomGuide() {
        if (!isAdded) return
        val reason = zoomStatus?.reason.orEmpty()
        val message = buildString {
            append(getString(R.string.touchpad_zoom_guide_message, ""))
            if (reason.isNotEmpty()) {
                append(getString(R.string.touchpad_zoom_guide_reason, reason))
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.touchpad_zoom_guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.touchpad_zoom_guide_refresh) { _, _ ->
                requestZoomProbe(userInitiated = true)
            }
            .setNegativeButton(R.string.touchpad_zoom_guide_ok, null)
            .show()
    }

    private fun toast(text: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 内部 ----------------

    private fun updateSensitivityLabel() {
        binding.sensitivityValue.text = getString(R.string.touchpad_sensitivity_value, sensitivity)
    }

    private fun updateModeHint() {
        binding.touchpadHint.setText(
            if (scrollMode) R.string.touchpad_hint_scroll else R.string.touchpad_hint_move
        )
        // 滚轮模式开着的时候用主色写提示：早期版本在别的页按了滚轮模式，回到这页只看到
        // 「拖动没反应」，看不出是滚轮模式在生效。现在状态一眼可见。
        binding.touchpadHint.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (scrollMode) R.color.at_primary else R.color.at_text_dim
            )
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
