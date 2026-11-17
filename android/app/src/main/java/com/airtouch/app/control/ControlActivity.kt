package com.airtouch.app.control

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.ActivityControlBinding
import com.airtouch.app.file.FileSender
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.net.AppVersion
import com.airtouch.app.net.PairingUri
import com.airtouch.app.net.PinnedTrust
import com.airtouch.app.net.Protocol
import com.airtouch.app.net.ServerDiagReport
import com.airtouch.app.net.WifiLatencyLock
import com.airtouch.app.settings.SettingsStore
import com.google.android.material.tabs.TabLayout

/**
 * 控制界面。
 *
 * 结构：TabLayout + FrameLayout + 三个 Fragment（show/hide 切换）。
 * **刻意不使用 ViewPager2**：页数很少、不需要滑动分页，TabLayout + show/hide 的
 * FragmentTransaction 是最稳定、行为最可预测的方案。
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
    private var diagnosticsFragment: DiagnosticsFragment? = null
    private var currentTabIndex = 0

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

        // 滚轮模式按「当前页」生效：空中鼠标页 = 俯仰手机滚轮，触摸板页 = 单指上下滑动滚轮。
        // （早期版本只有一个全局开关，只作用于触摸板：在空中鼠标页按下它什么都不会发生，
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
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.control_tab_diag))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchToTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        switchToTab(0)
    }

    private fun setupBackGesture() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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

        var diag = fm.findFragmentByTag(TAG_DIAG) as DiagnosticsFragment?
        if (diag == null) {
            diag = DiagnosticsFragment()
            fm.beginTransaction().add(binding.fragmentContainer.id, diag, TAG_DIAG).commitNow()
        }
        diag.attach(client, this, settings)

        airMouseFragment = air
        touchpadFragment = pad
        diagnosticsFragment = diag
    }

    private fun switchToTab(index: Int) {
        if (isFinishing || isDestroyed) return
        ensureFragments()
        currentTabIndex = index
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        val fragments = listOf(airMouseFragment, touchpadFragment, diagnosticsFragment)
        fragments.forEachIndexed { i, fragment ->
            if (fragment == null) return@forEachIndexed
            if (i == index) {
                if (fragment.isHidden) tx.show(fragment)
            } else {
                if (!fragment.isHidden) tx.hide(fragment)
            }
        }
        tx.commitNow()

        // 每个 Fragment 只在可见时才应该干活（传感器 / 触摸事件 / 画圆测试）
        airMouseFragment?.setScreenVisible(index == 0)
        touchpadFragment?.setScreenVisible(index == 1)
        diagnosticsFragment?.setScreenVisible(index == 2)

        // 工具栏按钮反映「当前页」的滚轮模式；切页时提示一次，避免用户忘记自己开着滚轮模式
        val scrollMode = currentPageScrollMode()
        updateScrollModeButton(scrollMode)
        if (scrollMode) {
            showHint(
                getString(
                    if (index == 0) R.string.control_airscroll_mode_on
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

    override fun sendScroll(amount: Int) {
        if (client.state != AirTouchClient.State.CONNECTED) return
        client.sendScroll(amount)
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

    /** 空中鼠标页的滚轮模式（俯仰手机 = 滚轮），与触摸板的开关相互独立。 */
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
            0 -> setAirMouseScrollMode(!settings.airMouseScrollMode)
            1 -> setScrollMode(!settings.scrollMode)
            else -> showHint(getString(R.string.control_scroll_mode_diag_hint))
        }
    }

    /** 当前页的滚轮模式状态（诊断页没有滚轮概念，返回 false） */
    private fun currentPageScrollMode(): Boolean = when (currentTabIndex) {
        0 -> settings.airMouseScrollMode
        1 -> settings.scrollMode
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

            if (info.authMode == "resume") {
                showFileStatus(getString(R.string.control_auth_mode_resume))
            } else if (info.authMode == "pair") {
                showFileStatus(getString(R.string.control_auth_mode_pair))
            }

            diagnosticsFragment?.onConnectionState(AirTouchClient.State.CONNECTED)
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

    override fun onUnknownText(type: String, raw: String) {
        // 协议 §2.3：未知类型必须忽略，不得关闭连接
        Log.d(TAG, "忽略未知文本帧 type=$type")
    }

    override fun onReconnecting(attempt: Int, delayMs: Long) {
        runOnUiThread {
            if (finishing || isFinishing || isDestroyed) return@runOnUiThread
            reconnectAttempt = attempt
            updateStatusLine(AirTouchClient.State.RECONNECTING)
            diagnosticsFragment?.onConnectionState(AirTouchClient.State.RECONNECTING)
            // 只提示一次，避免每次退避都弹一遍
            if (!reconnectHintShown) {
                reconnectHintShown = true
                toast(getString(R.string.control_reconnecting_hint))
            }
        }
    }

    override fun onDiagReport(report: ServerDiagReport) {
        runOnUiThread { diagnosticsFragment?.onServerReport(report) }
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

        diagnosticsFragment?.onConnectionState(state)
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

    companion object {
        private const val TAG = "ControlActivity"

        const val EXTRA_HOST = "com.airtouch.app.extra.HOST"
        const val EXTRA_PORT = "com.airtouch.app.extra.PORT"
        const val EXTRA_TOKEN = "com.airtouch.app.extra.TOKEN"
        const val EXTRA_SID = "com.airtouch.app.extra.SID"
        const val EXTRA_FP = "com.airtouch.app.extra.FP"

        private const val TAG_AIRMOUSE = "tag_airmouse"
        private const val TAG_TOUCHPAD = "tag_touchpad"
        private const val TAG_DIAG = "tag_diag"

        private const val FILE_STATUS_HIDE_DELAY_MS = 4000L
    }
}
