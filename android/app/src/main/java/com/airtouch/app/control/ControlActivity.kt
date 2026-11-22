package com.airtouch.app.control

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.ActivityControlBinding
import com.airtouch.app.databinding.OverlayKeyboardFullscreenBinding
import com.airtouch.app.file.FileSender
import com.airtouch.app.input.KeyboardInputHandler
import com.airtouch.app.input.TouchpadView
import com.airtouch.app.input.keyboard.KeyAction
import com.airtouch.app.input.keyboard.KeyboardLayouts
import com.airtouch.app.input.keyboard.KeyboardSession
import com.airtouch.app.input.keyboard.ModifierNames
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.net.AppVersion
import com.airtouch.app.net.PairingUri
import com.airtouch.app.net.PinnedTrust
import com.airtouch.app.net.Protocol
import com.airtouch.app.net.WifiLatencyLock
import com.airtouch.app.net.ZoomStatus
import com.airtouch.app.settings.SettingsStore
import com.google.android.material.tabs.TabLayout

/**
 * 控制界面。
 *
 * 结构：TabLayout + FrameLayout + 三个 Fragment（show/hide 切换）。
 * **刻意不使用 ViewPager2**：页数很少、不需要滑动分页，TabLayout + show/hide 的
 * FragmentTransaction 是最稳定、行为最可预测的方案。
 *
 * 横屏全屏键盘是**本 Activity 根布局上的一个覆盖层**（[OverlayKeyboardFullscreenBinding]），
 * 不是新的 Activity 也不是 Dialog：WebSocket 由本类持有，换 Activity 会把连接和键盘的按键
 * 记账一起重建，而「全屏打字」本来就只是同一个会话里的另一种形态。
 *
 * ## 连接生命周期（v2 的关键变化）
 * WebSocket 由本 Activity 持有。[AirTouchClient] 内部完成「auth_challenge → auth_req →
 * welcome」三步认证，并在断线时自动退避重连。因此本类**不再在掉线时退出到配对页**：
 * 只有「电脑端明确拒绝」（未信任/封禁/证书变了）才退出并提示重新扫码；普通网络抖动只是
 * 状态栏变成「正在自动重连」。
 *
 * 线程模型：[AirTouchClient] 的所有回调都在 OkHttp 线程或重连调度线程上触发，
 * 本类统一用 [runOnUiThread] 切回主线程后再碰界面。
 */
class ControlActivity : AppCompatActivity(), AirTouchClient.Listener, ControlBridge {

    private lateinit var binding: ActivityControlBinding
    private lateinit var settings: SettingsStore
    private lateinit var client: AirTouchClient
    private lateinit var fileSender: FileSender

    /**
     * 连接期间持有的 Wi-Fi 低延迟锁：Wi-Fi 省电会把帧攒着一起发，是「发送频率不低但看起来一顿一顿」
     * 的头号嫌疑（见 [com.airtouch.app.net.WifiLatencyLock] 的说明）。
     */
    private val wifiLatencyLock by lazy { WifiLatencyLock(this) }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var airMouseFragment: AirMouseFragment? = null
    private var touchpadFragment: TouchpadFragment? = null
    private var keyboardFragment: KeyboardFragment? = null
    private var currentTabIndex = 0

    // ---------------- 横屏全屏键盘 ----------------

    /** 覆盖层的 binding；在 onCreate 里 inflate 到根布局的最后，因此它是层级最上面的一层 */
    private lateinit var keyboardOverlay: OverlayKeyboardFullscreenBinding

    /**
     * 全屏键盘**自己的一份**输入会话。
     *
     * 刻意与竖屏键盘页各持一份：两边的视图不会同时存在，切换形态时两边都会松开全部按键，
     * 共享一份状态反而会在切换的瞬间出现「手机以为按着、电脑上已经松开」的记账错位。
     */
    private val fullscreenSession = KeyboardSession(
        send = { vk, down, extended -> sendKey(vk, down, extended) },
    )

    private val fullscreenHandler = KeyboardInputHandler(
        session = fullscreenSession,
        onActionRequested = { action -> if (action == KeyAction.FULLSCREEN) setKeyboardFullscreen(true) },
        onModifierChanged = { _, _ -> updateFullscreenHint() },
    )

    /** 全屏键盘是否正在显示 */
    private var fullscreenActive = false

    /**
     * 这次全屏是不是用户按「全屏展开」进来的。
     *
     * 按按钮进来的会锁成横屏（`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`），此时用户把手机转回竖屏
     * 不会改变配置，因此**只能**用「取消全屏」按钮退出；而靠旋转进来的不加锁，转回竖屏自动退出。
     * 少了这个区分，就会出现「转了半圈手机，全屏键盘自己没了 / 自己又回来了」。
     */
    private var fullscreenLockedByButton = false

    /** 微型触控板气泡是否展开 */
    private var miniTouchpadOpen = false

    /** 已经提示过「手形图标能开触控板」：入口是个没有文字的小图标，值得提示一次，但不该反复打扰 */
    private var miniTouchpadHintShown = false

    /** 已经触发过「退出到配对页」的流程，避免重复 Toast/重复 finish */
    private var finishing = false

    /** 当前重连次数（0 表示没有在重连） */
    private var reconnectAttempt = 0
    private var reconnectHintShown = false

    private var serverIp = ""
    private var serverVersion = ""
    private var serverSid = ""
    private var deviceKeyId = ""
    private var authMode = ""
    private var deviceName = ""
    private var pendingLegacyFileUri: Uri? = null

    /** 服务端 error 帧里鉴权类错误码对应的中文提示（由 onError 写入、主线程读写）。 */
    private var serverErrorHintText: String? = null

    /** 刚收到的文件的内容 URI（可交给系统打开）；null 表示没有可打开的文件 */
    private var lastReceivedUri: Uri? = null

    private val fileStatusHideRunnable = Runnable { hideFileStatus() }

    // ---------------- 权限（仅 API < 29 需要，用于把收到的文件写入公共下载目录） ----------------

    private val requestLegacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val uri = pendingLegacyFileUri
            pendingLegacyFileUri = null
            if (granted) {
                if (uri != null) fileSender.send(uri) else showHint(getString(R.string.file_send_failed, "权限已授予，请重新选择文件"))
            } else {
                showHint(getString(R.string.file_send_failed, "没有存储写入权限，无法在 Android 9 及以下保存/发送文件"))
            }
        }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (client.state != AirTouchClient.State.CONNECTED) {
            showHint(getString(R.string.file_send_failed, getString(R.string.control_status_closed)))
            return@registerForActivityResult
        }
        if (needsLegacyStoragePermission()) {
            pendingLegacyFileUri = uri
            requestLegacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return@registerForActivityResult
        }
        if (!fileSender.send(uri)) {
            showHint(getString(R.string.file_send_busy))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        deviceName = buildDeviceName()
        client = AirTouchClient(applicationContext, settings, this, pinListener)
        client.listenerDeviceName = deviceName
        fileSender = FileSender(this, client, fileSenderListener)

        // 控制期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupToolbar()
        setupTabs()
        setupKeyboardOverlay()
        setupBackGesture()
        requestFileReceiverPermissionIfNeeded()

        val uri = resolveTarget()
        if (uri == null) {
            toast(getString(R.string.control_connect_failed_toast, getString(R.string.pairing_invalid_qr)))
            finish()
            return
        }

        Log.i(TAG, "连接目标：${uri.displayName()}（${if (uri.isPairing) "首次配对" else "设备密钥重连"}）")
        updateStatusLine(AirTouchClient.State.CONNECTING)
        client.connect(uri)
    }

    /**
     * 决定这次到底是「扫码配对」还是「免扫码重连」：
     * - 带 token（刚扫完码）→ 配对，并把二维码里的 sid/fp 一起带上做严格证书校验；
     * - 不带 token（从配对页的「上次连接」进来，或 Activity 被重建）→ 用设备密钥重连。
     */
    private fun resolveTarget(): PairingUri? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: settings.lastHost
        val port = intent.getIntExtra(EXTRA_PORT, settings.lastPort)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
        val sid = intent.getStringExtra(EXTRA_SID) ?: settings.lastSid.ifEmpty { null }
        val fp = intent.getStringExtra(EXTRA_FP) ?: settings.lastPin.ifEmpty { null }

        if (host.isEmpty()) return null

        if (token.isNotEmpty()) {
            val parsed = PairingUri.fromParts(host, port, token) ?: return null
            return parsed.copy(sid = sid ?: parsed.sid, fp = fp ?: parsed.fp)
        }

        return PairingUri.resume(host, port, sid, fp)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(fileStatusHideRunnable)
        wifiLatencyLock.release()
        try {
            fileSender.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "fileSender.shutdown 失败", e)
        }
        try {
            // shutdown 会停掉重连调度线程：Activity 没了就不该再自动重连
            client.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "client.shutdown 失败", e)
        }
    }

    // ---------------- 初始化 ----------------

    private fun setupToolbar() {
        binding.buttonLeftClick.setOnClickListener { sendClick(Protocol.BUTTON_LEFT) }
        binding.buttonRightClick.setOnClickListener { sendClick(Protocol.BUTTON_RIGHT) }

        // 滚轮模式按「当前页」生效：鼠标页 = 俯仰/推拉手机滚轮，触摸板页 = 单指上下滑动滚轮。
        // （早期版本只有一个全局开关，只作用于触摸板：在鼠标页按下它什么都不会发生，
        //   却把触摸板静默切成滚轮模式 —— 用户回到触摸板页以为触摸板坏了。）
        binding.buttonScrollMode.setOnClickListener { toggleScrollModeForCurrentPage() }
        binding.buttonReset.setOnClickListener { sendReset() }
        binding.buttonSendFile.setOnClickListener {
            if (fileSender.isBusy) {
                showHint(getString(R.string.file_send_busy))
                return@setOnClickListener
            }
            try {
                pickFile.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Log.w(TAG, "打开文件选择器失败", e)
                showHint(getString(R.string.file_send_failed, e.message ?: "无法打开文件选择器"))
            }
        }
        binding.buttonDisconnect.setOnClickListener {
            showHint(getString(R.string.control_status_closed))
            finish()
        }

        binding.fileStatusRow.visibility = View.GONE
        binding.buttonOpenFile.setOnClickListener { openLastReceivedFile() }
        updateScrollModeButton(settings.scrollMode)
    }

    private fun setupTabs() {
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.control_tab_airmouse))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.control_tab_touchpad))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.control_tab_keyboard))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchToTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        switchToTab(TAB_AIRMOUSE)
    }

    private fun setupBackGesture() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 全屏键盘时返回键先退出全屏：直接 finish 会连带把连接断掉，用户只是想收起键盘
                if (fullscreenActive) {
                    exitFullscreen()
                    return
                }
                // 系统返回手势：干净地断开连接并回到配对界面
                finish()
            }
        })
    }

    /**
     * 把三个 Fragment 加入容器（只加一次），之后靠 show/hide 切换。
     * 旋转时 FragmentManager 会恢复已有实例，这里用 findFragmentByTag 复用，避免重复添加。
     */
    private fun ensureFragments() {
        val fm = supportFragmentManager

        var air = fm.findFragmentByTag(TAG_AIRMOUSE) as AirMouseFragment?
        if (air == null) {
            air = AirMouseFragment()
            fm.beginTransaction().add(binding.fragmentContainer.id, air, TAG_AIRMOUSE).commitNow()
        }
        air.attach(client, this, settings)

        var pad = fm.findFragmentByTag(TAG_TOUCHPAD) as TouchpadFragment?
        if (pad == null) {
            pad = TouchpadFragment()
            fm.beginTransaction().add(binding.fragmentContainer.id, pad, TAG_TOUCHPAD).commitNow()
        }
        pad.attach(client, this, settings)

        var keyboard = fm.findFragmentByTag(KeyboardFragment.TAG) as KeyboardFragment?
        if (keyboard == null) {
            keyboard = KeyboardFragment()
            fm.beginTransaction().add(binding.fragmentContainer.id, keyboard, KeyboardFragment.TAG).commitNow()
        }
        keyboard.attach(this, settings)

        airMouseFragment = air
        touchpadFragment = pad
        keyboardFragment = keyboard
    }

    private fun switchToTab(index: Int) {
        if (isFinishing || isDestroyed) return
        ensureFragments()
        currentTabIndex = index
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        val fragments = listOf(airMouseFragment, touchpadFragment, keyboardFragment)
        fragments.forEachIndexed { i, fragment ->
            if (fragment == null) return@forEachIndexed
            if (i == index) {
                if (fragment.isHidden) tx.show(fragment)
            } else {
                if (!fragment.isHidden) tx.hide(fragment)
            }
        }
        tx.commitNow()

        // 每个 Fragment 只在可见时才应该干活（传感器 / 触摸事件 / 按键状态）
        airMouseFragment?.setScreenVisible(index == TAB_AIRMOUSE)
        touchpadFragment?.setScreenVisible(index == TAB_TOUCHPAD)
        // 全屏键盘盖在上面时，竖屏键盘页不算可见：它必须松开全部按键，否则两边会各按一次修饰键
        keyboardFragment?.setScreenVisible(index == TAB_KEYBOARD && !fullscreenActive)

        // 离开键盘页就退出全屏键盘：否则用户切到别的页，看到的是被键盘盖住的一片
        if (fullscreenActive && index != TAB_KEYBOARD) {
            exitFullscreen()
        }

        // 工具栏按钮反映「当前页」的滚轮模式；切页时提示一次，避免用户忘记自己开着滚轮模式
        val scrollMode = currentPageScrollMode()
        updateScrollModeButton(scrollMode)
        if (scrollMode) {
            showHint(
                getString(
                    if (index == TAB_AIRMOUSE) R.string.control_airscroll_mode_on
                    else R.string.control_scroll_mode_on
                )
            )
        }
    }

    // ---------------- ControlBridge ----------------

    override fun connectionState(): AirTouchClient.State = client.state

    override fun connectionLabel(): String =
        if (serverIp.isNotEmpty()) "$serverIp:${client.serverPort}"
        else if (client.serverHost.isNotEmpty()) "${client.serverHost}:${client.serverPort}"
        else ""

    override fun sendMove(dx: Int, dy: Int) {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendMove(dx, dy)
    }

    override fun sendClick(button: String) {
        if (client.state != AirTouchClient.State.CONNECTED) {
            showHint(getString(R.string.control_status_closed))
            return
        }
        client.sendClick(button)
    }

    /** 滚轮（整格）：陀螺仪滚轮模式用 */
    override fun sendScrollNotches(amount: Int) {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendScroll(amount)
    }

    /** 高精度滚轮：触摸板两指滑动用 */
    override fun sendScroll(delta: Int, horizontal: Boolean) {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendScrollDelta(delta, horizontal)
    }

    /**
     * 双指捏合/张开（协议 §2.1 的 `touch`）：只转发几何，用哪个 Windows API 由服务端决定。
     *
     * `up` 即使发失败也不要紧（服务端在断线时会松开接触点/Ctrl），但 `down/move` 失败就没意义了，
     * 所以这里静默丢弃、不弹提示——捏合过程中每秒几十帧，Toast 会毁掉体验。
     */
    override fun sendPinch(phase: String, scale: Double, dx: Int, dy: Int) {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendTouch(phase, scale, dx, dy)
    }

    /**
     * 设置电脑端的缩放偏好（协议 §2.1 的 `zoom_mode`）。
     *
     * 这是**电脑端**的开关：手机只提要求，电脑端按自己的触摸栈能力决定最终走哪个 API，
     * 结果通过 `zoom_status` 回来（见 [onZoomStatus]）。
     */
    override fun sendZoomMode(mode: String) {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendZoomMode(mode)
    }

    /** 请电脑端立刻重新探测触控能力（协议 §2.1 的 `zoom_probe`）：刚插上触控设备后用 */
    override fun sendZoomProbe() {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendZoomProbe()
    }

    /**
     * 鼠标按键的按下 / 抬起（协议 §2.1 的 `button`），触摸板的拖拽手势用它。
     *
     * 未连接时**返回失败**：调用方（触摸板）据此取消拖拽状态。这里不弹提示，理由与 [sendKey]
     * 相同（拖拽过程中每秒会发多次），但「未连接」这件事界面上已经有常驻提示。
     */
    override fun sendButton(button: String, down: Boolean): Boolean {
        if (client.state != AirTouchClient.State.CONNECTED) return false
        return client.sendButton(button, down)
    }

    override fun sendReset() {
        if (client.state != AirTouchClient.State.CONNECTED) {
            showHint(getString(R.string.control_status_closed))
            return
        }
        client.sendReset()
        // 复位同时清掉输入源的累计余量，避免复位后光标又被旧余量带走
        airMouseFragment?.resetEngine()
    }

    override fun sendKey(vk: Int, down: Boolean, extended: Boolean?) {
        // 刻意**不**弹提示：按键是每秒可能发几十次的操作，一次 Toast 会直接把输入体验毁掉。
        // 未连接时静默丢弃，界面上的「尚未连接电脑」提示已经说明了原因。
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendKey(vk, down, extended)
    }

    override fun sendUnicodeText(text: String) {
        // 与按键同样的理由：文本可能是九键选一个字，不能每次都弹提示
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendUnicodeText(text)
    }

    /**
     * 「纯文本输入框」：打大段文字时的补充通道。
     *
     * 这里**刻意**让系统输入法参与进来：多行编辑、粘贴、联想、语音都靠它 —— 用户要的就是
     * 「像在手机上打一段话，然后整段丢到电脑上」。自绘键盘本身从不调起系统输入法，
     * 这个例外只存在于这一个对话框里，不要把它推广到别处。
     */
    override fun showTextSender() {
        val view = layoutInflater.inflate(R.layout.dialog_send_text, null)
        val input = view.findViewById<EditText>(R.id.text_input)
        val counter = view.findViewById<TextView>(R.id.text_counter)

        val offline = client.state != AirTouchClient.State.CONNECTED
        counter.setTextColor(
            ContextCompat.getColor(this, if (offline) R.color.at_error else R.color.at_text_dim)
        )
        counter.text = if (offline) {
            getString(R.string.keyboard_text_sender_offline)
        } else {
            getString(R.string.keyboard_text_sender_count, 0)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                counter.text = getString(R.string.keyboard_text_sender_count, s?.length ?: 0)
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.keyboard_text_sender_title)
            .setView(view)
            .setPositiveButton(R.string.keyboard_text_sender_send, null)
            .setNegativeButton(R.string.keyboard_text_sender_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 自己接发送按钮：内容为空、没连上时不能把对话框关掉（关掉就等于丢了用户刚打的字）
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString().orEmpty()
                if (text.isEmpty()) {
                    toast(getString(R.string.keyboard_text_sender_empty))
                    return@setOnClickListener
                }
                if (client.state != AirTouchClient.State.CONNECTED) {
                    toast(getString(R.string.keyboard_text_sender_failed))
                    return@setOnClickListener
                }
                client.sendUnicodeText(text)
                toast(getString(R.string.keyboard_text_sender_sent, text.length))
                dialog.dismiss()
            }
        }

        dialog.show()
        // 点「文本」就是为了打字，直接把系统输入法弹出来，省掉一次多余的点击
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    override fun isScrollMode(): Boolean = settings.scrollMode

    override fun setScrollMode(enabled: Boolean) {
        settings.scrollMode = enabled
        touchpadFragment?.setScrollMode(enabled)
        if (currentTabIndex == 1) updateScrollModeButton(enabled)
        showHint(
            getString(
                if (enabled) R.string.control_scroll_mode_on else R.string.control_scroll_mode_off
            )
        )
    }

    /** 鼠标页的滚轮模式（上下俯仰手机 = 滚轮），与触摸板的开关相互独立。 */
    private fun setAirMouseScrollMode(enabled: Boolean) {
        settings.airMouseScrollMode = enabled
        airMouseFragment?.setScrollMode(enabled)
        if (currentTabIndex == 0) updateScrollModeButton(enabled)
        showHint(
            getString(
                if (enabled) R.string.control_airscroll_mode_on else R.string.control_airscroll_mode_off
            )
        )
    }

    /** 工具栏「滚轮模式」按钮：切换**当前页**的滚轮模式 */
    private fun toggleScrollModeForCurrentPage() {
        when (currentTabIndex) {
            TAB_AIRMOUSE -> setAirMouseScrollMode(!settings.airMouseScrollMode)
            TAB_TOUCHPAD -> setScrollMode(!settings.scrollMode)
            TAB_KEYBOARD -> showHint(getString(R.string.control_scroll_mode_keyboard_hint))
        }
    }

    /** 当前页的滚轮模式状态（键盘页没有滚轮概念，返回 false） */
    private fun currentPageScrollMode(): Boolean = when (currentTabIndex) {
        TAB_AIRMOUSE -> settings.airMouseScrollMode
        TAB_TOUCHPAD -> settings.scrollMode
        else -> false
    }

    override fun showHint(message: String) {
        runOnUiThread { toast(message) }
    }

    /**
     * Wi-Fi 低延迟锁没拿到时返回给用户看的说明（正常时返回 null）。
     *
     * 这个状态必须能看见：拿不到锁时 Wi-Fi 会进省电模式，把帧攒起来一起发，
     * 现象正是「平均很流畅、每隔一会儿冻结 200ms 左右」。
     */
    override fun wifiLatencyNote(): String? {
        if (client.state != AirTouchClient.State.CONNECTED) return null
        if (wifiLatencyLock.held) return null
        val reason = wifiLatencyLock.lastError ?: "未知原因"
        return getString(R.string.airmouse_wifi_lock_note, reason)
    }

    private fun updateScrollModeButton(enabled: Boolean) {
        binding.buttonScrollMode.setBackgroundResource(
            if (enabled) R.drawable.bg_at_button_active else R.drawable.bg_at_button
        )
        val color = ContextCompat.getColor(
            this,
            if (enabled) R.color.at_bg else R.color.at_text
        )
        binding.buttonScrollMode.setTextColor(ColorStateList.valueOf(color))
    }

    // ---------------- 横屏全屏键盘 ----------------

    /**
     * 把全屏键盘覆盖层 inflate 到根布局的**最后**并接好回调。
     *
     * 用 `attachToRoot = true` 而不是在 `activity_control.xml` 里写 `<include>`：覆盖层是
     * 一个整屏独层，程序化 inflate 能明确写出它铺满父容器的约束，不依赖 ConstraintLayout
     * 对 `match_parent` 的处理方式，也不会让主布局多出一层嵌套 binding。
     */
    private fun setupKeyboardOverlay() {
        keyboardOverlay = OverlayKeyboardFullscreenBinding.inflate(
            layoutInflater,
            binding.root as ViewGroup,
            true,
        )

        val lp = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        keyboardOverlay.root.layoutParams = lp
        // 抬到所有兄弟之上：全屏键盘必须盖住页签、状态栏与底部工具栏
        keyboardOverlay.root.elevation = resources.displayMetrics.density * 16f
        keyboardOverlay.root.visibility = View.GONE

        keyboardOverlay.keyboardViewFullscreen.listener = fullscreenHandler.keyboardListener
        keyboardOverlay.keyboardViewFullscreen.layout = KeyboardLayouts.FULL_104

        // 标题条既是「取消全屏 / 手形图标」的容器，也是拖动手柄。
        // 触摸先给子 View，所以按钮仍然是按钮；只有落在标题条空白处的触摸才变成拖动。
        keyboardOverlay.keyboardContainer.dragHandle = keyboardOverlay.keyboardHeader
        keyboardOverlay.keyboardContainer.onDragFinished = { x, y -> persistKeyboardOffset(x, y) }

        keyboardOverlay.buttonExitFullscreen.setOnClickListener { exitFullscreen() }
        keyboardOverlay.buttonMiniTouchpad.setOnClickListener { toggleMiniTouchpad() }
        keyboardOverlay.buttonTextSender.setOnClickListener { showTextSender() }

        keyboardOverlay.miniTouchpadView.listener = miniTouchpadListener
        keyboardOverlay.miniTouchpadView.isClickable = true
        keyboardOverlay.miniTouchpadView.isFocusable = true
        keyboardOverlay.miniTouchpadView.scrollMode = false
        // 气泡面积只有整页触摸板的四分之一，灵敏度不调高的话一次滑动挪不了几像素
        keyboardOverlay.miniTouchpadView.sensitivity = MINI_TOUCHPAD_SENSITIVITY
    }

    override fun setKeyboardFullscreen(enabled: Boolean) {
        if (enabled) enterFullscreen(byButton = true) else exitFullscreen()
    }

    override fun isKeyboardFullscreen(): Boolean = fullscreenActive

    /**
     * 进入横屏全屏键盘。
     *
     * @param byButton true = 用户按了「全屏展开」（会锁成横屏）；false = 靠旋转屏幕进来（不锁）
     */
    private fun enterFullscreen(byButton: Boolean) {
        if (fullscreenActive) return
        fullscreenActive = true
        fullscreenLockedByButton = byButton

        // 竖屏键盘立即让位：两个键盘各有自己的会话，不先松开就会在电脑上按两次修饰键
        keyboardFragment?.setScreenVisible(false)

        if (byButton) {
            // 锁成横屏：不锁的话用户把手机转回竖屏，全屏键盘会「自己消失」，而按钮还写着「取消全屏」
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        keyboardOverlay.root.visibility = View.VISIBLE
        applyImmersiveMode(true)

        // 手形图标是一个没有文字的图标，第一次进全屏时提示一次它有什么用；之后不再打扰
        if (!miniTouchpadHintShown) {
            miniTouchpadHintShown = true
            toast(getString(R.string.keyboard_mini_touchpad_hint))
        }

        // 恢复上次拖动到的位置。此刻容器还没测量，先按保存值设一次，等布局完成后会被重新夹取。
        val dx = dpToPx(settings.keyboardOffsetXDp)
        val dy = dpToPx(settings.keyboardOffsetYDp)
        keyboardOverlay.keyboardContainer.post {
            keyboardOverlay.keyboardContainer.setTranslation(dx, dy)
            keyboardOverlay.keyboardContainer.reclamp()
        }

        updateFullscreenHint()
    }

    /** 退出全屏键盘（按钮 / 返回键 / 旋转回竖屏 / 切页都会走到这里） */
    private fun exitFullscreen() {
        if (!fullscreenActive) return
        fullscreenActive = false
        fullscreenLockedByButton = false

        closeMiniTouchpad()

        // 退出前松开全部按键：粘滞锁定的 Ctrl 只存在于手机内存里，
        // 电脑端收不到抬起事件就会一直按着它。同时清掉键帽的「按下高亮」。
        keyboardOverlay.keyboardViewFullscreen.cancelGesture()
        fullscreenHandler.releaseAll()
        // 微型触控板如果正拖着东西，左键也要跟着松开（它与全屏键盘同生共死）
        keyboardOverlay.miniTouchpadView.cancelGesture()

        keyboardOverlay.root.visibility = View.GONE
        applyImmersiveMode(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        keyboardFragment?.setScreenVisible(currentTabIndex == TAB_KEYBOARD)
    }

    /**
     * 手形图标：展开 / 收起微型触控板气泡。
     *
     * 气泡是**容器里的正常子 View**（右对齐、就在手形图标正上方），所以「打开」= 容器自己向上长高：
     * 不需要 translationY、不需要 clipChildren=false，也不依赖容器当前在屏幕上的位置 ——
     * 气泡跑到屏幕外这个失败模式被结构性消除了（旧实现靠 translationY 把它抬到容器上方，
     * 容器越靠上被裁掉得越多）。
     */
    private fun toggleMiniTouchpad() {
        if (miniTouchpadOpen) {
            closeMiniTouchpad()
            return
        }
        miniTouchpadOpen = true
        keyboardOverlay.buttonMiniTouchpad.active = true

        val bubble = keyboardOverlay.miniTouchpadBubble
        bubble.visibility = View.VISIBLE
        // 先明确告诉用户「点到了」：这样万一气泡本身没出现，也能立刻分清是放置问题还是点击问题
        toast(getString(R.string.keyboard_mini_touchpad_opened))
        applyMiniTouchpadLayout(expanded = true)
    }

    private fun closeMiniTouchpad() {
        if (!miniTouchpadOpen && keyboardOverlay.miniTouchpadBubble.visibility == View.GONE) return
        // 收气泡时如果手指还按在里面（正在拖东西），补一次左键抬起：视图变 GONE 后不一定还收得到 UP
        keyboardOverlay.miniTouchpadView.cancelGesture()
        miniTouchpadOpen = false
        keyboardOverlay.miniTouchpadBubble.visibility = View.GONE
        keyboardOverlay.buttonMiniTouchpad.active = false
        applyMiniTouchpadLayout(expanded = false)
    }

    /**
     * 打开气泡时把「气泡 + 标题条 + 键盘」压进屏幕高度里。
     *
     * 只压键盘、不压气泡：104 键是按行高比例缩放的，压矮只是键变矮，不会错位；而气泡里的
     * [TouchpadView] 有固定尺寸，压小会把可拖动的面积真的削掉。键盘有下限，真到放不下时
     * 宁可让容器略微溢出（那需要一块比 320dp 还矮的横屏，现实中不存在）。
     *
     * @param expanded true = 气泡已展开，需要腾地方；false = 恢复键盘的自然高度
     */
    private fun applyMiniTouchpadLayout(expanded: Boolean) {
        val keyboard = keyboardOverlay.keyboardViewFullscreen
        val params = keyboard.layoutParams

        if (!expanded) {
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                keyboard.layoutParams = params
            }
            return
        }

        // 现在的高度就是自然高度（关闭时会还原成 wrap_content），所以它既是要压的对象也是基准
        val natural = keyboard.height
        if (natural <= 0) return

        val available = keyboardOverlay.root.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val chrome = resources.getDimensionPixelSize(R.dimen.at_keyboard_header_height) +
            2 * resources.getDimensionPixelSize(R.dimen.at_space_xs) +
            // 气泡与标题条之间的空隙，与 overlay 布局里的 layout_marginBottom 是同一个 dimen
            resources.getDimensionPixelSize(R.dimen.at_space_s)
        val bubbleHeight = resources.getDimensionPixelSize(R.dimen.at_mini_touchpad_height) +
            2 * resources.getDimensionPixelSize(R.dimen.at_space_xs)

        val budget = available - bubbleHeight - chrome
        val minKeyboard = dpToPx(MIN_KEYBOARD_HEIGHT_DP).toInt()
        val target = budget.coerceIn(minKeyboard, natural)

        if (target != params.height) {
            params.height = target
            keyboard.layoutParams = params
        }
    }

    /** 全屏键盘标题条上的状态文字：有修饰键生效时替换掉「拖动」提示（模态状态必须看得见） */
    private fun updateFullscreenHint() {
        if (!::keyboardOverlay.isInitialized) return
        val status = ModifierNames.statusText(this, fullscreenSession)
        keyboardOverlay.keyboardDragHint.text = status ?: getString(R.string.keyboard_drag_hint)
        keyboardOverlay.keyboardDragHint.setTextColor(
            ContextCompat.getColor(this, if (status == null) R.color.at_text_dim else R.color.at_primary)
        )
    }

    /** 把键盘的拖动位置存起来（density 无关，旋转屏幕后仍然有效） */
    private fun persistKeyboardOffset(x: Float, y: Float) {
        settings.keyboardOffsetXDp = pxToDp(x)
        settings.keyboardOffsetYDp = pxToDp(y)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun pxToDp(px: Float): Float = px / resources.displayMetrics.density

    /**
     * 沉浸式全屏。
     *
     * 全屏键盘铺满屏幕时，手指按最左边/最右边的按键很容易从屏幕边缘划入，被系统当成「返回」或
     * 「回到桌面」（需求里的「阻止事件穿透 / 系统侧滑返回冲突」）。隐藏系统栏是最直接的一层；
     * 键盘自身的 `systemGestureExclusionRects` 是第二层。
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` / `IMMERSIVE_STICKY` 保证用户主动划出系统栏后
     * 它会自动缩回去，不会把「取消全屏」按钮永久顶掉。
     */
    private fun applyImmersiveMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (enabled) {
                controller.hide(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    /** 全屏键盘上的微型触控板：手势识别全部复用 [TouchpadView]，这里只做协议转发 */
    private val miniTouchpadListener = object : TouchpadView.Listener {
        override fun onMove(dx: Int, dy: Int) {
            sendMove(dx, dy)
        }

        override fun onClick(button: String) {
            sendClick(button)
        }

        override fun onScroll(delta: Int, horizontal: Boolean) {
            sendScroll(delta, horizontal)
        }

        // 微型触控板与大触摸板共用同一套手势，所以拖拽、缩放、三指手势也一并支持
        override fun onButton(button: String, down: Boolean): Boolean =
            sendButton(button, down)

        override fun onPinch(phase: String, scale: Double, dx: Int, dy: Int) {
            sendPinch(phase, scale, dx, dy)
        }

        override fun onThreeFinger(action: String) {
            sendThreeFinger(action)
        }
    }

    /**
     * 屏幕旋转。
     *
     * Activity 声明了 `configChanges`，旋转不会重建，所以「旋转 = 切换键盘形态」这条需求
     * 必须在这里实现：横过来时若正在键盘页就进入全屏键盘，转回竖屏则自动退出。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::keyboardOverlay.isInitialized) return

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (!fullscreenActive && currentTabIndex == TAB_KEYBOARD) {
                enterFullscreen(byButton = false)
            }
        } else if (fullscreenActive && !fullscreenLockedByButton) {
            // 只有「旋转进来」的才自动退出；按钮进来的已经锁成横屏，根本不会走到这个分支
            exitFullscreen()
        }

        if (fullscreenActive) {
            // 旋转会重置系统栏状态，也会改变父容器尺寸：两者都要重新处理
            applyImmersiveMode(true)
            keyboardOverlay.keyboardContainer.reclamp()
            // 气泡展开时键盘被压矮过，新方向的可用高度不同，必须按新尺寸重算
            if (miniTouchpadOpen) applyMiniTouchpadLayout(expanded = true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 切前后台、弹系统对话框之后沉浸式会被系统撤掉，回到前台要重新申请
        if (hasFocus && fullscreenActive) applyImmersiveMode(true)
    }

    // ---------------- AirTouchClient.Listener（全部在后台线程触发） ----------------

    override fun onOpen() {
        // WS 握手完成，但**还没认证**：此时什么都不能发（协议 §1.2），等 auth_challenge。
        runOnUiThread { updateStatusLine(AirTouchClient.State.AUTHENTICATING) }
    }

    override fun onAuthenticating(mode: String) {
        runOnUiThread { updateStatusLine(AirTouchClient.State.AUTHENTICATING) }
    }

    override fun onWelcome(info: AirTouchClient.SessionInfo) {
        runOnUiThread {
            serverVersion = info.serverVersion
            if (info.serverIp.isNotEmpty()) serverIp = info.serverIp
            serverSid = info.sid
            deviceKeyId = info.keyId ?: ""
            authMode = info.authMode
            reconnectAttempt = 0
            reconnectHintShown = false
            serverErrorHintText = null

            // 把服务器身份/固定值/设备 keyId 记下来：以后重连不用再扫码，也用得到 sid 做严格校验
            settings.lastHost = client.serverHost
            settings.lastPort = client.serverPort
            if (info.sid.isNotEmpty()) settings.lastSid = info.sid
            if (info.keyId != null) settings.lastKeyId = info.keyId
            client.token?.let { settings.lastToken = it }

            updateStatusLine(AirTouchClient.State.CONNECTED)

            // 会话建立即抓 Wi-Fi 低延迟锁：后面每一帧输入都吃这条链路的抖动
            wifiLatencyLock.acquire()
            if (!wifiLatencyLock.held) {
                // 拿不到不是致命问题，但要让人知道"可能更抖"的原因（观察到的抖动值会更大）
                Log.w(TAG, "Wi-Fi 低延迟锁未获取：${wifiLatencyLock.lastError}")
            }

            // 认证通过后再上报设备信息（协议 §2.1 的 hello）
            client.sendHello(deviceName, AppVersion.of(this))

            // 用户拨过「原生丝滑缩放」开关就把偏好重发给电脑端：这是**电脑端**的设置，
            // 服务端重启或换台电脑后不会替我们记着。没拨过（null）就不发，让电脑端保持自己的 auto 判断。
            settings.zoomPreference?.let { client.sendZoomMode(it) }

            if (info.authMode == "resume") {
                showFileStatus(getString(R.string.control_auth_mode_resume))
            } else if (info.authMode == "pair") {
                showFileStatus(getString(R.string.control_auth_mode_pair))
            }
        }
    }

    override fun onFileAck(fileName: String, status: String, bytes: Long, savedPath: String?, message: String?) {
        runOnUiThread {
            if (status.equals("ok", ignoreCase = true)) {
                showFileStatus(getString(R.string.file_ack_ok, fileName, formatBytes(bytes)))
            } else {
                val detail = if (!message.isNullOrEmpty()) message else fileName
                showFileStatus(getString(R.string.file_ack_error, detail))
            }
        }
    }

    override fun onError(code: String, message: String) {
        // 协议 §1.2：认证失败时服务端会发 error 后立即关闭连接，所以这里先记下中文提示，
        // 再由 onClosed/onFailure 决定是「自动重连」还是「退出到配对页」。
        runOnUiThread {
            val hint = serverErrorHint(code)
            if (hint != null) serverErrorHintText = hint
            val text = when {
                hint != null -> "$hint（$code）"
                message.isNotEmpty() -> "$message（$code）"
                else -> code
            }
            Log.w(TAG, "服务端 error：$text")
            // token_expired / busy / too_many_pending 属于「会自己恢复」的情况，
            // 交给重连逻辑处理，不要弹一串吓人的提示。
            if (hint == null || isRecoverableError(code)) {
                showFileStatus(text)
            } else {
                showHint(getString(R.string.control_welcome_error, text))
            }
        }
    }

    /**
     * 把服务端 error 帧的 code 映射成可操作的中文提示；未知码返回 null（退回通用文案）。
     *
     * v2 里 token 只在「首次配对」时用一次，重连完全靠设备私钥，所以最常见的失败不再是
     * token_expired，而是 key_unknown（电脑端清过信任列表）—— 那必须重新扫码。
     */
    private fun serverErrorHint(code: String): String? = when (code) {
        "token_expired" -> getString(R.string.control_server_error_token_expired)
        "token_invalid" -> getString(R.string.control_server_error_token_invalid)
        "banned" -> getString(R.string.control_server_error_banned)
        "key_unknown" -> getString(R.string.control_server_error_key_unknown)
        "bad_key" -> getString(R.string.control_server_error_bad_key)
        "sig_invalid" -> getString(R.string.control_server_error_sig_invalid)
        "stale_auth" -> getString(R.string.control_server_error_stale_auth)
        "trust_revoked" -> getString(R.string.control_server_error_trust_revoked)
        "replaced" -> getString(R.string.control_server_error_replaced)
        "rate_limited" -> getString(R.string.control_server_error_rate_limited)
        "too_many_pending" -> getString(R.string.control_server_error_too_many_pending)
        "busy" -> getString(R.string.control_server_error_busy)
        else -> null
    }

    /** 这些错误重连一次就可能恢复，不需要用户做任何事。 */
    private fun isRecoverableError(code: String): Boolean = when (code) {
        "token_expired", "busy", "too_many_pending", "rate_limited", "replaced", "stale_auth", "auth_required" -> true
        else -> false
    }

    override fun onPong() {
        // 心跳应答，无需界面反馈
    }

    /**
     * 电脑端缩放后端状态（协议 §2.2 的 `zoom_status`）。
     *
     * 只做一件事：转达给触摸板页的状态指示器 —— 那是用户确认「电脑端实际在用哪个后端」
     * 的唯一地方，所以这里不做任何拦截或缓存丢失。
     */
    override fun onZoomStatus(status: ZoomStatus) {
        runOnUiThread { touchpadFragment?.onZoomStatusChanged(status) }
    }

    override fun onUnknownText(type: String, raw: String) {
        // 协议 §2.3：未知类型必须忽略，不得关闭连接
        Log.d(TAG, "忽略未知文本帧 type=$type")
    }

    override fun onReconnecting(attempt: Int, delayMs: Long) {
        runOnUiThread {
            if (finishing || isFinishing || isDestroyed) return@runOnUiThread
            reconnectAttempt = attempt
            updateStatusLine(AirTouchClient.State.RECONNECTING)
            // 只提示一次，避免每次退避都弹一遍
            if (!reconnectHintShown) {
                reconnectHintShown = true
                toast(getString(R.string.control_reconnecting_hint))
            }
        }
    }

    override fun onFileReceiveProgress(fileName: String, received: Long, total: Long) {
        runOnUiThread {
            if (total > 0) {
                val percent = ((received * 100L) / total).toInt().coerceIn(0, 100)
                showFileStatus(
                    getString(R.string.file_receive_start, fileName, formatBytes(total)) +
                        " · $percent%"
                )
            } else {
                showFileStatus(getString(R.string.file_receive_start, fileName, formatBytes(received)))
            }
        }
    }

    override fun onFileReceived(fileName: String, savedPath: String, bytes: Long, viewUri: Uri?) {
        runOnUiThread {
            // 电脑 → 手机最容易让人困惑的就是「文件存哪了」，所以这里把完整路径留在状态行，
            // 并给一个「打开」按钮（URI 由 FileReceiver 返回，API 29+ 是 MediaStore 的）。
            lastReceivedUri = viewUri
            val text = getString(R.string.file_receive_done, savedPath)
            toast(text)
            showFileStatus(text, showOpen = viewUri != null, sticky = true)
        }
    }

    override fun onFileReceiveFailed(fileName: String, reason: String) {
        runOnUiThread {
            val text = if (fileName.isEmpty()) reason else "$fileName：$reason"
            toast(getString(R.string.file_receive_failed, text))
            showFileStatus(getString(R.string.file_receive_failed, text))
        }
    }

    /**
     * 连接被关闭。客户端只在「不会自动重连」时才回调这里：
     * 1000/1001 或电脑端明确拒绝（未信任/封禁/证书变了）。所以这里直接回到配对页并给出原因。
     */
    override fun onClosed(code: Int, reason: String) {
        runOnUiThread {
            if (finishing || isFinishing || isDestroyed) return@runOnUiThread
            updateStatusLine(AirTouchClient.State.CLOSED)
            val hint = serverErrorHintText
            val text = if (hint != null) hint
            else getString(R.string.control_disconnected_toast, describeClose(code, reason))
            finishToPairing(text)
        }
    }

    override fun onFailure(t: Throwable) {
        runOnUiThread {
            if (finishing || isFinishing || isDestroyed) return@runOnUiThread
            updateStatusLine(AirTouchClient.State.FAILED)
            val hint = serverErrorHintText
            val text = if (hint != null) hint
            else getString(R.string.control_connect_failed_toast, describeError(t))
            finishToPairing(text)
        }
    }

    private val pinListener = object : PinnedTrust.PinListener {
        override fun onPinStored(id: String, pin: String) {
            Log.i(TAG, "已记录 $id 的证书固定值 $pin")
        }

        override fun onPinMismatch(id: String, expectedPin: String, actualPin: String) {
            Log.w(TAG, "$id 证书固定值不一致，连接将被中断")
        }
    }

    private val fileSenderListener = object : FileSender.Listener {
        override fun onSendStarted(fileName: String, total: Long) {
            runOnUiThread {
                showFileStatus(
                    getString(R.string.file_send_start, fileName, formatBytes(total))
                )
                if (total < 0) showHint(getString(R.string.file_send_unknown_size))
            }
        }

        override fun onSendProgress(fileName: String, sent: Long, total: Long, percent: Int) {
            runOnUiThread {
                val p = if (percent < 0) 0 else percent
                showFileStatus(
                    getString(R.string.file_send_progress, p, formatBytes(sent), formatBytes(total))
                )
            }
        }

        override fun onSendCompleted(fileName: String, sent: Long) {
            runOnUiThread {
                showFileStatus(getString(R.string.file_send_done, fileName))
            }
        }

        override fun onSendFailed(fileName: String, reason: String) {
            runOnUiThread {
                val text = if (fileName.isEmpty()) reason else "$fileName：$reason"
                toast(getString(R.string.file_send_failed, text))
                showFileStatus(getString(R.string.file_send_failed, text))
            }
        }
    }

    // ---------------- 界面辅助 ----------------

    private fun updateStatusLine(state: AirTouchClient.State) {
        val stateText = when (state) {
            AirTouchClient.State.IDLE -> getString(R.string.control_status_idle)
            AirTouchClient.State.CONNECTING -> getString(R.string.control_status_connecting)
            AirTouchClient.State.AUTHENTICATING -> getString(R.string.control_status_authenticating)
            AirTouchClient.State.CONNECTED -> getString(R.string.control_status_connected)
            AirTouchClient.State.RECONNECTING ->
                getString(R.string.control_status_reconnecting, maxOf(1, reconnectAttempt))
            AirTouchClient.State.CLOSED -> getString(R.string.control_status_closed)
            AirTouchClient.State.FAILED -> getString(R.string.control_status_failed)
        }
        val label = connectionLabel()
        val detail = if (label.isEmpty()) stateText else getString(R.string.control_status_detail, stateText, label)
        val withVersion = if (serverVersion.isEmpty()) detail
        else getString(R.string.control_status_detail, detail, getString(R.string.control_server_version, serverVersion))
        val withKey = if (deviceKeyId.isEmpty()) withVersion
        else getString(
            R.string.control_status_detail,
            withVersion,
            getString(R.string.control_device_identity, deviceKeyId.take(8))
        )
        binding.statusLine.text = withKey

        // 键盘的按键记账必须跟着连接状态走：断开重连后电脑端不再按着任何键，
        // 手机若还认为「Ctrl 已经按着」，重连后的第一次 Ctrl+C 会静默变成一次 C。
        if (state != AirTouchClient.State.CONNECTED) {
            releaseKeyboardKeys()
        }
        keyboardFragment?.refreshHint()
        updateFullscreenHint()
    }

    /** 让两个键盘会话都松开全部按键并清空记账（连接断开、界面离开前台、退出全屏时调用） */
    private fun releaseKeyboardKeys() {
        fullscreenHandler.releaseAll()
        keyboardFragment?.resetKeyState()
    }

    private fun showFileStatus(text: String, showOpen: Boolean = false, sticky: Boolean = false) {
        binding.fileStatus.text = text
        binding.fileStatusRow.visibility = View.VISIBLE
        binding.buttonOpenFile.visibility = if (showOpen) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(fileStatusHideRunnable)
        if (!sticky) {
            mainHandler.postDelayed(fileStatusHideRunnable, FILE_STATUS_HIDE_DELAY_MS)
        }
    }

    private fun hideFileStatus() {
        if (isFinishing || isDestroyed) return
        binding.fileStatusRow.visibility = View.GONE
        binding.buttonOpenFile.visibility = View.GONE
        lastReceivedUri = null
    }

    /** 用系统「打开方式」打开刚收到的文件（内容 URI 已带临时读权限） */
    private fun openLastReceivedFile() {
        val uri = lastReceivedUri
        if (uri == null) {
            showHint(getString(R.string.file_open_unavailable))
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "打开文件失败", e)
            showHint(getString(R.string.file_open_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun finishToPairing(message: String) {
        if (finishing) return
        finishing = true
        toast(message)
        finish()
    }

    private fun describeClose(code: Int, reason: String): String {
        val base = when (code) {
            1000 -> "对端正常关闭"
            1001 -> "对端离开（同一设备建立了新会话）"
            1006 -> "链路异常中断"
            1008 -> "策略/认证原因被拒绝"
            1013 -> "电脑已有一台设备在连接"
            else -> "关闭码 $code"
        }
        return if (reason.isNullOrEmpty()) base else "$base：$reason"
    }

    private fun describeError(t: Throwable): String {
        val msg = t.message
        if (msg != null && msg.isNotEmpty()) return msg
        return t.javaClass.simpleName
    }

    private fun needsLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestFileReceiverPermissionIfNeeded() {
        if (needsLegacyStoragePermission()) {
            // 拒绝也不阻塞控制功能，只是收到文件时无法写入公共下载目录
            try {
                requestLegacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } catch (e: Exception) {
                Log.w(TAG, "申请存储权限失败", e)
            }
        }
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        val name = ("$manufacturer $model").trim()
        return if (name.isEmpty()) "Android" else name
    }

    /** 把字节数格式化成可读文本（用 strings.xml 里的模板，避免代码里散落中文） */
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return getString(R.string.unit_unknown)
        val b = bytes.toDouble()
        return when {
            bytes < 1024L -> getString(R.string.unit_bytes, bytes.toInt())
            bytes < 1024L * 1024L -> getString(R.string.unit_kib, b / 1024.0)
            bytes < 1024L * 1024L * 1024L -> getString(R.string.unit_mib, b / (1024.0 * 1024.0))
            else -> getString(R.string.unit_gib, b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatusLine(client.state)
        // 回到前台且仍然连着：重新抓住 Wi-Fi 低延迟锁（onPause 时释放过，避免后台耗电）
        if (client.state == AirTouchClient.State.CONNECTED) {
            wifiLatencyLock.acquire()
        }
    }

    override fun onPause() {
        super.onPause()
        // 界面不在前台就没有流畅度需求了，让 Wi-Fi 恢复正常省电策略
        wifiLatencyLock.release()
    }

    override fun onStop() {
        super.onStop()
        // 视图不再接收触摸，ACTION_UP 永远不会来了。此刻不松开，电脑上就会留下一个卡住的键
        // （服务端虽然会在会话结束时兜底释放，但不该依赖兜底）。
        releaseKeyboardKeys()
    }

    companion object {
        private const val TAG = "ControlActivity"

        const val EXTRA_HOST = "com.airtouch.app.extra.HOST"
        const val EXTRA_PORT = "com.airtouch.app.extra.PORT"
        const val EXTRA_TOKEN = "com.airtouch.app.extra.TOKEN"
        const val EXTRA_SID = "com.airtouch.app.extra.SID"
        const val EXTRA_FP = "com.airtouch.app.extra.FP"

        /** 页签下标：顺序必须与 [setupTabs] 里 addTab 的顺序一致 */
        private const val TAB_AIRMOUSE = 0
        private const val TAB_TOUCHPAD = 1
        private const val TAB_KEYBOARD = 2

        private const val TAG_AIRMOUSE = "tag_airmouse"
        private const val TAG_TOUCHPAD = "tag_touchpad"

        private const val FILE_STATUS_HIDE_DELAY_MS = 4000L

        /** 微型触控板灵敏度：气泡面积只有整页触摸板的四分之一，倍数要更高才好用 */
        private const val MINI_TOUCHPAD_SENSITIVITY = 2.5f

        /**
         * 气泡展开时允许把 104 键压到的最低高度（dp）。
         *
         * 再矮下去键帽就只有十几 dp，连按键本身都点不准了 —— 那时宁可让容器略微溢出屏幕，
         * 也不该给用户一块按不动的键盘。
         */
        private const val MIN_KEYBOARD_HEIGHT_DP = 120f
    }
}
