package com.airtouch.app.control

import android.Manifest
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
import com.airtouch.app.net.PairingUri
import com.airtouch.app.net.PinnedTrust
import com.airtouch.app.net.Protocol
import com.airtouch.app.settings.SettingsStore
import com.google.android.material.tabs.TabLayout

/**
 * 控制界面。
 *
 * 结构：TabLayout + FrameLayout + 两个 Fragment（show/hide 切换）。
 * **刻意不使用 ViewPager2**：本页面只有两页、不需要滑动分页，TabLayout + show/hide 的
 * FragmentTransaction 是最稳定、行为最可预测的方案。
 *
 * 连接生命周期：WebSocket 连接由本 Activity 持有（不是 Fragment），因为
 * - Fragment 在 show/hide 之间不会重建，但旋转时 Activity 会重建；
 * - AndroidManifest 里 ControlActivity 声明了 configChanges="orientation|screenSize|..."，
 *   所以屏幕旋转不会重建 Activity，连接天然存活。
 *
 * 线程模型：[AirTouchClient] 的所有回调都在 OkHttp 线程上触发，本类统一用
 * [runOnUiThread] 切回主线程后再碰界面。
 */
class ControlActivity : AppCompatActivity(), AirTouchClient.Listener, ControlBridge {

    private lateinit var binding: ActivityControlBinding
    private lateinit var settings: SettingsStore
    private lateinit var client: AirTouchClient
    private lateinit var fileSender: FileSender

    private val mainHandler = Handler(Looper.getMainLooper())

    private var airMouseFragment: AirMouseFragment? = null
    private var touchpadFragment: TouchpadFragment? = null
    private var currentTabIndex = 0

    /** 已经触发过「断开并退出」的流程，避免重复 Toast/重复 finish */
    private var finishing = false

    private var serverIp = ""
    private var serverVersion = ""
    private var deviceName = ""
    private var pendingLegacyFileUri: Uri? = null

    /**
     * 服务端 error 帧里鉴权类错误码对应的中文提示（由 onError 写入、主线程读写）。
     * 服务端发完 error 就关连接，因此 onClosed/onFailure 用它替换通用的「连接已断开」文案。
     */
    private var serverErrorHintText: String? = null

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
        fileSender = FileSender(this, client, fileSenderListener)

        // 控制期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupToolbar()
        setupTabs()
        setupBackGesture()
        requestFileReceiverPermissionIfNeeded()

        val host = intent.getStringExtra(EXTRA_HOST) ?: settings.lastHost
        val port = intent.getIntExtra(EXTRA_PORT, settings.lastPort)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: settings.lastToken
        val uri = PairingUri.fromParts(host, port, token)
        if (uri == null) {
            toast(getString(R.string.control_connect_failed_toast, getString(R.string.pairing_invalid_qr)))
            finish()
            return
        }
        updateStatusLine(AirTouchClient.State.CONNECTING)
        client.connect(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(fileStatusHideRunnable)
        try {
            fileSender.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "fileSender.shutdown 失败", e)
        }
        try {
            client.close()
        } catch (e: Exception) {
            Log.w(TAG, "client.close 失败", e)
        }
    }

    // ---------------- 初始化 ----------------

    private fun setupToolbar() {
        binding.buttonLeftClick.setOnClickListener { sendClick(Protocol.BUTTON_LEFT) }
        binding.buttonRightClick.setOnClickListener { sendClick(Protocol.BUTTON_RIGHT) }

        binding.buttonScrollMode.setOnClickListener {
            setScrollMode(!settings.scrollMode)
        }
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

        binding.fileStatus.visibility = View.GONE
        updateScrollModeButton(settings.scrollMode)
    }

    private fun setupTabs() {
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.control_tab_airmouse))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.control_tab_touchpad))

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
     * 把两个 Fragment 加入容器（只加一次），之后靠 show/hide 切换。
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

        airMouseFragment = air
        touchpadFragment = pad
    }

    private fun switchToTab(index: Int) {
        if (isFinishing || isDestroyed) return
        ensureFragments()
        currentTabIndex = index
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        val target: Fragment?
        val other: Fragment?
        if (index == 0) {
            target = airMouseFragment
            other = touchpadFragment
        } else {
            target = touchpadFragment
            other = airMouseFragment
        }
        if (other != null && !other.isHidden) tx.hide(other)
        if (target != null && target.isHidden) tx.show(target)
        tx.commitNow()
        // 空中鼠标只在可见时才应该跑传感器
        airMouseFragment?.setScreenVisible(index == 0)
        touchpadFragment?.setScreenVisible(index == 1)
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
        // 复位同时清掉两个输入源的累计余量，避免复位后光标又被旧余量带走
        airMouseFragment?.resetEngine()
    }

    override fun isScrollMode(): Boolean = settings.scrollMode

    override fun setScrollMode(enabled: Boolean) {
        settings.scrollMode = enabled
        touchpadFragment?.setScrollMode(enabled)
        updateScrollModeButton(enabled)
        showHint(
            getString(
                if (enabled) R.string.control_scroll_mode_on else R.string.control_scroll_mode_off
            )
        )
    }

    override fun showHint(message: String) {
        runOnUiThread { toast(message) }
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

    // ---------------- AirTouchClient.Listener（全部在 OkHttp 线程触发） ----------------

    override fun onOpen() {
        // WebSocket 握手成功；hello 上报设备信息（协议 §2.1）
        val device = deviceName
        val version = appVersion()
        client.sendHello(device, version)
        runOnUiThread { updateStatusLine(AirTouchClient.State.CONNECTED) }
    }

    override fun onWelcome(
        serverVersion: String,
        serverIp: String,
        port: Int,
        boundIp: String,
        maxFileSize: Long,
        chunkSize: Int
    ) {
        runOnUiThread {
            this.serverVersion = serverVersion
            if (serverIp.isNotEmpty()) this.serverIp = serverIp
            updateStatusLine(AirTouchClient.State.CONNECTED)
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
        // 协议 §1.2：鉴权失败时服务端会发 error 后立即关闭连接（1008，busy 为 1013），
        // 所以这里把错误码对应的中文提示记下来，紧接着由 onClosed/onFailure 用它作为退出提示。
        runOnUiThread {
            val hint = serverErrorHint(code)
            if (hint != null) serverErrorHintText = hint
            val text = when {
                hint != null -> "$hint（$code）"
                message.isNotEmpty() -> "$message（$code）"
                else -> code
            }
            showHint(getString(R.string.control_welcome_error, text))
        }
    }

    /**
     * 把服务端 error 帧的 code 映射成可操作的中文提示；未知码返回 null（退回通用文案）。
     *
     * token 每次断开后都会在服务端重新生成（协议 §1.2/§4），所以断线重连时最常见的
     * 失败就是 token_expired —— 必须明确告诉用户「要重新扫码」，而不是只说「连接已断开」。
     */
    private fun serverErrorHint(code: String): String? = when (code) {
        "token_expired" -> getString(R.string.control_server_error_token_expired)
        "token_invalid" -> getString(R.string.control_server_error_token_invalid)
        "banned" -> getString(R.string.control_server_error_banned)
        "ip_locked" -> getString(R.string.control_server_error_ip_locked)
        "rate_limited" -> getString(R.string.control_server_error_rate_limited)
        "busy" -> getString(R.string.control_server_error_busy)
        else -> null
    }

    override fun onPong() {
        // 心跳应答，无需界面反馈
    }

    override fun onUnknownText(type: String, raw: String) {
        // 协议 §2.3：未知类型必须忽略，不得关闭连接
        Log.d(TAG, "忽略未知文本帧 type=$type")
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

    override fun onFileReceived(fileName: String, savedPath: String, bytes: Long) {
        runOnUiThread {
            toast(getString(R.string.file_receive_done, savedPath))
            showFileStatus(getString(R.string.file_receive_done, savedPath))
        }
    }

    override fun onFileReceiveFailed(fileName: String, reason: String) {
        runOnUiThread {
            val text = if (fileName.isEmpty()) reason else "$fileName：$reason"
            toast(getString(R.string.file_receive_failed, text))
            showFileStatus(getString(R.string.file_receive_failed, text))
        }
    }

    override fun onClosed(code: Int, reason: String) {
        runOnUiThread {
            if (finishing || isFinishing || isDestroyed) return@runOnUiThread
            updateStatusLine(AirTouchClient.State.CLOSED)
            // 服务端明确报过鉴权类错误码时，直接给出可操作的中文提示
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
        override fun onPinStored(host: String, fingerprintHex: String) {
            Log.i(TAG, "已记录 $host 的证书指纹")
        }

        override fun onPinMismatch(host: String, expectedHex: String, actualHex: String) {
            Log.w(TAG, "$host 证书指纹不一致，连接将被中断")
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
            AirTouchClient.State.CONNECTED -> getString(R.string.control_status_connected)
            AirTouchClient.State.CLOSED -> getString(R.string.control_status_closed)
            AirTouchClient.State.FAILED -> getString(R.string.control_status_failed)
        }
        val label = connectionLabel()
        val detail = if (label.isEmpty()) stateText else getString(R.string.control_status_detail, stateText, label)
        val line = if (serverVersion.isEmpty()) detail
        else getString(R.string.control_status_detail, detail, getString(R.string.control_server_version, serverVersion))
        binding.statusLine.text = line
    }

    private fun showFileStatus(text: String) {
        binding.fileStatus.text = text
        binding.fileStatus.visibility = View.VISIBLE
        mainHandler.removeCallbacks(fileStatusHideRunnable)
        mainHandler.postDelayed(fileStatusHideRunnable, FILE_STATUS_HIDE_DELAY_MS)
    }

    private fun hideFileStatus() {
        if (isFinishing || isDestroyed) return
        binding.fileStatus.visibility = View.GONE
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
            1001 -> "对端离开"
            1006 -> "链路异常中断"
            1008 -> "策略/鉴权原因被拒绝"
            1013 -> "服务端忙（同一 IP 只允许一条连接）"
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

    private fun appVersion(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: FALLBACK_VERSION
        } catch (e: Exception) {
            FALLBACK_VERSION
        }
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
    }

    companion object {
        private const val TAG = "ControlActivity"

        const val EXTRA_HOST = "com.airtouch.app.extra.HOST"
        const val EXTRA_PORT = "com.airtouch.app.extra.PORT"
        const val EXTRA_TOKEN = "com.airtouch.app.extra.TOKEN"

        private const val TAG_AIRMOUSE = "tag_airmouse"
        private const val TAG_TOUCHPAD = "tag_touchpad"

        private const val FILE_STATUS_HIDE_DELAY_MS = 4000L
        private const val FALLBACK_VERSION = "1.0.0"
    }
}
