package com.airtouch.app.ui.redesign.integration

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airtouch.app.file.FileSender
import com.airtouch.app.input.AirMouseEngine
import com.airtouch.app.input.keyboard.KeyboardSession
import com.airtouch.app.input.keyboard.Vk
import com.airtouch.app.input.keyboard.t9.PinyinDictionary
import com.airtouch.app.input.keyboard.t9.T9Composer
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.net.AppVersion
import com.airtouch.app.net.PairingUri
import com.airtouch.app.net.PinnedTrust
import com.airtouch.app.net.WifiLatencyLock
import com.airtouch.app.net.ZoomStatus
import com.airtouch.app.settings.SettingsStore
import com.airtouch.app.ui.redesign.theme.AccentPreset
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignRoute
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.reduce
import kotlin.math.max

/**
 * 重构 UI 与现有工程接口之间的**唯一**适配层。
 *
 * 页面只发 [RedesignAction]，这里把它映射到：
 *  - [AirTouchClient]（协议帧：move / click / button / scroll / key / text / reset / touch）
 *  - [SettingsStore]（连接信息、灵敏度、键盘布局、证书固定值）
 *  - [AirMouseEngine] + [SensorManager]（陀螺仪 → 像素位移/滚轮，专用 HandlerThread 采样）
 *  - [KeyboardSession]（粘滞修饰键的正确按下/抬起顺序）
 *  - [FileSender] / SAF（文件传输）
 *  - [WifiLatencyLock]（低延迟锁）
 *  - [RedesignPrefs]（外观偏好持久化）
 *
 * 刻意**不修改**任何现有业务类：AirTouchClient / Protocol / PinnedTrust /
 * SettingsStore / AirMouseEngine / KeyboardSession / FileSender 全部原样复用；
 * 旧版 View/XML 界面（PairingActivity / ControlActivity / Fragment / 自绘 View）已整体删除。
 * 所有 AirTouchClient 回调都在后台线程，统一 post 回主线程再写 Compose state。
 */
class RedesignController(
    private val activity: ComponentActivity,
    private val onHint: (String) -> Unit,
    private val openFilePicker: () -> Unit,
) : AirTouchClient.Listener,
    PinnedTrust.PinListener,
    SensorEventListener {

    private val settings = SettingsStore(activity)
    private val prefs = RedesignPrefs(activity)
    private val wifiLock = WifiLatencyLock(activity)
    private val engine = AirMouseEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fileSenderListener = object : FileSender.Listener {
        override fun onSendStarted(fileName: String, total: Long) {
            mainHandler.post { state = state.copy(fileStatus = "发送 $fileName 0%") }
        }

        override fun onSendProgress(fileName: String, sent: Long, total: Long, percent: Int) {
            mainHandler.post { state = state.copy(fileStatus = "发送 $fileName $percent%") }
        }

        override fun onSendCompleted(fileName: String, sent: Long) {
            mainHandler.post {
                state = state.copy(fileStatus = "已发送 $fileName")
                onHint("已发送 $fileName")
            }
        }

        override fun onSendFailed(fileName: String, reason: String) {
            mainHandler.post {
                state = state.copy(fileStatus = "发送失败 $fileName")
                onHint("发送失败: $reason")
            }
        }
    }

    private val client = AirTouchClient(activity.applicationContext, settings, this, this)
    private val fileSender = FileSender(activity, client, fileSenderListener)
    private val keyboardSession = KeyboardSession(
        send = { vk, down, extended -> ifConnected { client.sendKey(vk, down, extended) } }
    )

    /**
     * 当前被按住、还没松开的鼠标键（拖拽进行中）。
     *
     * 拖拽是「按下 → 一连串 move → 抬起」，与一次性 click 不同：手指还按着时切页 / 断线 /
     * 进后台，View 收不到抬起事件，电脑上的左键就会**一直按着**（表现为整屏一直处于拖选状态）。
     * 所以这里记着按住了哪些键，兜底路径统一补一次 `down = false`。
     */
    private val heldButtons = linkedSetOf<String>()

    // 移动帧的余数（见 sendMove）：灵敏度缩放后不足 1px 的部分攒着，下一次一起发。
    private var moveCarryX = 0f
    private var moveCarryY = 0f

    /**
     * 九键拼音组合状态机（不管界面、不管发送通道，见 [T9Composer]）。
     * 数字键的候选、退格、上屏都经过它；词库在第一次按九键数字时后台加载。
     */
    private val t9Composer = T9Composer()
    private var t9DictionaryRequested = false

    private val sensorManager: SensorManager? =
        activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var gyroSensor: Sensor? = null
    private val sensorThread = HandlerThread("redesign-sensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private var lastPingSentMs = 0L
    private var pingRunning = false
    /**
     * 当前连接目标 `"host:port"`。用来忽略对同一台电脑的重复扫码；
     * **主动断开时清空**，这样「断开 → 重新扫同一台电脑」仍然可用。
     */
    private var connectTarget: String? = null
    /** Activity 是否在前台；后台时必须注销陀螺仪（省电，也避免后台误发位移帧）。 */
    private var foreground = false
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!pingRunning) return
            if (client.state == AirTouchClient.State.CONNECTED) {
                lastPingSentMs = SystemClock.elapsedRealtime()
                client.sendPing()
            }
            mainHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    /** Compose 可观察状态；所有写操作都在主线程。 */
    var state by mutableStateOf(initialState())
        private set

    var route by mutableStateOf(RedesignRoute.HOME)
        private set

    var showManualConnectDialog by mutableStateOf(false)
    var showTextDialog by mutableStateOf(false)

    init {
        client.stateListener = AirTouchClient.StateListener { s ->
            mainHandler.post {
                state = state.copy(
                    connected = s == AirTouchClient.State.CONNECTED,
                    statusText = stateTextOf(s),
                )
            }
        }
        client.listenerDeviceName = Build.MODEL.ifEmpty { "Android" }
        engine.sensitivity = state.sensitivity
        engine.invertX = settings.airMouseInvertX
        engine.invertY = settings.airMouseInvertY
        engine.scrollMode = settings.airMouseScrollMode
        engine.updateAxisMapping()
    }

    // -----------------------------------------------------------------------
    // 对外：连接 / 生命周期 / 文件选择结果
    // -----------------------------------------------------------------------

    /** Activity 启动时调用：优先用扫码带回来的 EXTRA_*，否则免扫码重连上次设备。 */
    fun connectFromIntent(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST)
        if (!host.isNullOrEmpty()) {
            val port = intent.getIntExtra(EXTRA_PORT, SettingsStore.DEFAULT_PORT)
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
            val sid = intent.getStringExtra(EXTRA_SID) ?: settings.lastSid.ifEmpty { null }
            val fp = intent.getStringExtra(EXTRA_FP) ?: settings.lastPin.ifEmpty { null }
            val uri = if (token.isNotEmpty()) {
                PairingUri.fromParts(host, port, token)?.copy(sid = sid, fp = fp)
            } else {
                PairingUri.resume(host, port, sid, fp)
            }
            if (uri == null) {
                onHint("连接地址无效，请重新扫码")
                return
            }
            connect(uri)
        } else if (settings.lastHost.isNotEmpty()) {
            connectLast()
        } else {
            onHint("尚未配对，请先扫描电脑端二维码")
        }
    }

    /** 新版主页 1:1 取景框扫到二维码：直接解析并连接，全程不启动任何旧 Activity。 */
    fun connectFromQr(raw: String) {
        mainHandler.post {
            val text = raw.trim()
            Log.i(TAG, "扫码内容：${text.take(48)}")
            val uri = PairingUri.parse(text)
            if (uri == null) {
                onHint("二维码不是 AirTouch 地址：${text.take(24)}")
                return@post
            }
            // 同一台电脑只连一次：取景框里那张码会被反复扫到，
            // 每 1.5 秒触发一次重连的现象就是「一对着二维码就不停重新连接」。
            // 这里连「正在连接但还没握手完」的窗口也一起挡住（那时 state.connected 还是 false）。
            if (connectTarget == targetKey(uri.host, uri.port)) {
                Log.i(TAG, "已在使用 ${uri.displayName()}，忽略重复扫码")
                onHint("正在使用 ${uri.displayName()}，无需重复扫码")
                return@post
            }
            onHint("已识别二维码，正在连接 ${uri.displayName()}")
            connect(uri)
        }
    }

    fun connectLast() {
        val host = settings.lastHost
        if (host.isEmpty()) {
            onHint("还没有配对过任何电脑，请扫描主页取景框中的二维码")
            return
        }
        connect(
            PairingUri.resume(
                host,
                settings.lastPort,
                settings.lastSid.ifEmpty { null },
                settings.lastPin.ifEmpty { null },
            )
        )
    }

    fun connectManual(host: String, port: Int, token: String) {
        // 允许直接粘贴二维码里的完整 wss:// URL（扫不了时可用电脑端复制的文本兜底）
        val pasted = PairingUri.parse(host.trim())
        val uri = when {
            pasted != null -> pasted
            token.isBlank() -> PairingUri.resume(
                host.trim(),
                port,
                settings.lastSid.ifEmpty { null },
                settings.lastPin.ifEmpty { null },
            )
            else -> PairingUri.fromParts(host, port, token)?.copy(
                sid = settings.lastSid.ifEmpty { null },
                fp = settings.lastPin.ifEmpty { null },
            )
        }
        if (uri == null) {
            onHint("主机地址无效")
            return
        }
        connect(uri)
    }

    private fun connect(uri: PairingUri) {
        showManualConnectDialog = false
        // 记下这次连接的目标：取景框里那张二维码会被反复扫到，
        // 「已经连上 / 正在连同一台电脑」时必须忽略，否则每 1.5 秒重连一次。
        connectTarget = targetKey(uri.host, uri.port)
        mainHandler.post {
            state = state.copy(
                connectionLabel = uri.displayName(),
                statusText = "正在连接…",
            )
        }
        client.connect(uri)
    }

    /** 连接目标的比较键（host 小写 + 端口）。 */
    private fun targetKey(host: String, port: Int): String =
        "${host.trim().lowercase(java.util.Locale.US)}:$port"

    /** SAF 选完文件后由 Activity 调用 */
    fun sendFile(uri: Uri) {
        if (client.state != AirTouchClient.State.CONNECTED) {
            onHint("尚未连接电脑，无法发送文件")
            return
        }
        if (!fileSender.send(uri)) onHint("已有文件正在传输")
    }

    fun onDestroy() {
        pingRunning = false
        mainHandler.removeCallbacks(pingRunnable)
        releaseHeldButtons()
        stopSensors()
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "unregister sensor", e)
        }
        sensorThread.quitSafely()
        fileSender.shutdown()
        wifiLock.release()
        client.shutdown()
    }

    /**
     * 进设置页之前所在的路由。设置页返回时回到这里（而不是一律回主页）。
     * 只记一层：从设置页再进设置页（不该发生）不会把它覆盖掉。
     */
    private var routeBeforeSettings: RedesignRoute? = null

    /** 唯一的页面切换入口：陀螺仪只在鼠标页工作（否则触控板/键盘页也会被姿态推着走，光标乱飞）。 */
    private fun enterRoute(target: RedesignRoute) {
        val changed = target != route
        route = target
        if (changed) {
            keyboardSession.releaseAll()
            // 按住左键时切页 = 手指还在屏幕上但 View 没了，必须补抬起
            releaseHeldButtons()
        }
        refreshSensors()
    }

    fun onStart() {
        foreground = true
        refreshSensors()
    }

    fun onStop() {
        foreground = false
        stopSensors()
        keyboardSession.releaseAll()
        releaseHeldButtons()
        t9Composer.reset()
        publishT9()
    }

    // -----------------------------------------------------------------------
    // 动作分发：UI → 真实接口
    // -----------------------------------------------------------------------

    fun dispatch(action: RedesignAction) {
        state = state.reduce(action)
        when (action) {
            is RedesignAction.SetDarkTheme -> prefs.darkTheme = action.value
            is RedesignAction.SetAccent -> {
                prefs.accentKey = action.preset.key
                prefs.customAccentArgb = null
            }
            is RedesignAction.SetCustomAccent -> prefs.customAccentArgb = action.color.toArgb()
            is RedesignAction.SetLeftHand -> prefs.leftHand = action.value
            is RedesignAction.SetHaptic -> prefs.haptic = action.value

            is RedesignAction.SetSensitivity -> {
                settings.touchpadSensitivity = action.value
                settings.airMouseSensitivity = action.value
                engine.sensitivity = action.value
            }

            is RedesignAction.SetTouchZeroLatency -> settings.touchZeroLatency = action.value

            is RedesignAction.SetAirMouseInvertX -> {
                settings.airMouseInvertX = action.value
                engine.invertX = action.value
                engine.updateAxisMapping()
            }

            is RedesignAction.SetAirMouseInvertY -> {
                settings.airMouseInvertY = action.value
                engine.invertY = action.value
                engine.updateAxisMapping()
            }

            is RedesignAction.SetPaused -> if (action.value) stopSensors() else refreshSensors()

            is RedesignAction.SetT9 -> {
                settings.keyboardLayout = if (action.value) "t9" else SettingsStore.DEFAULT_KEYBOARD_LAYOUT
                keyboardSession.releaseAll()
                // 切走布局时丢掉没上屏的拼音组合，避免回到九键时还挂着上次的数字串
                t9Composer.reset()
                publishT9()
            }

            is RedesignAction.SetMode -> {
                enterRoute(
                    when (action.index) {
                        0 -> RedesignRoute.AIR_MOUSE
                        1 -> RedesignRoute.TOUCHPAD
                        2 -> RedesignRoute.KEYBOARD_PORTRAIT
                        else -> route
                    }
                )
            }

            is RedesignAction.ConnectLast -> connectLast()
            is RedesignAction.ManualConnect -> connectManual(action.host, action.port, action.token)
            // 新版主页已内嵌真实扫码取景，ScanQr 只需提示，不再启动任何旧 Activity
            is RedesignAction.ScanQr -> onHint("请扫描主页取景框中的二维码")

            is RedesignAction.ResetTrust -> {
                val n = settings.resetAllServerPins()
                onHint(if (n > 0) "已清除 $n 条信任记录" else "当前没有保存的信任记录")
                mainHandler.post { state = state.copy(fileStatus = "已清除 $n 条信任记录") }
            }

            is RedesignAction.OpenFilePicker -> openFilePicker()

            is RedesignAction.SendText -> {
                showTextDialog = false
                if (action.text.isNotEmpty()) sendUnicode(action.text)
            }

            is RedesignAction.Screenshot -> screenshot()

            is RedesignAction.Disconnect -> {
                releaseHeldButtons()
                t9Composer.reset()
                publishT9()
                client.close()
                wifiLock.release()
                stopSensors()
                // 主动断开 = 释放连接目标，之后再扫同一台电脑的码应当正常工作
                connectTarget = null
                pingRunning = false
                mainHandler.removeCallbacks(pingRunnable)
                mainHandler.post {
                    state = state.copy(connected = false, statusText = "已断开", latencyMs = -1)
                }
            }

            is RedesignAction.MouseScroll -> sendScrollDelta(action.delta * 15, false)
            is RedesignAction.SetButton -> setButton(action.button, action.down)
            is RedesignAction.TouchpadMove -> sendMove(action.dx, action.dy)
            is RedesignAction.TouchpadClick -> sendClick(action.button)
            is RedesignAction.TouchpadScroll -> sendScrollDelta(action.delta, action.horizontal)
            is RedesignAction.TouchpadPinch -> ifConnected {
                client.sendTouch(action.phase, action.scale, action.dx, action.dy)
            }

            is RedesignAction.ToggleSticky -> {
                val vk = modifierVk(action.modifier)
                if (vk != null) keyboardSession.tapModifier(vk)
            }

            is RedesignAction.PressKey -> pressKey(action.key)

            is RedesignAction.T9Digit -> {
                ensureT9Dictionary()
                if (t9Composer.onDigit(action.digit)) publishT9()
            }
            // 组合中才吃退格；组合为空时它就是电脑上的退格键
            is RedesignAction.T9Backspace -> if (t9Composer.onBackspace()) publishT9() else pressKey("⌫")
            is RedesignAction.T9Commit -> {
                val text = t9Composer.commit(action.index)
                publishT9()
                if (!text.isNullOrEmpty()) sendUnicode(text)
            }
            is RedesignAction.T9Clear -> {
                t9Composer.reset()
                publishT9()
            }

            is RedesignAction.Click -> handleClick(action.label)

            is RedesignAction.SetMiniTouchpad,
            is RedesignAction.SetConnected -> Unit
        }
    }

    private fun handleClick(label: String) {
        when (label) {
            // 打开设置：记下"来时的页面"，返回时回到它（首页/鼠标页/触控板页/键盘页/全屏键盘都覆盖）
            "打开设置" -> {
                if (route != RedesignRoute.SETTINGS) routeBeforeSettings = route
                enterRoute(RedesignRoute.SETTINGS)
            }
            // 设置页的返回键：回到进来时的那个页面；没有记录（理论上不会）就回主页
            "返回上一页" -> enterRoute(routeBeforeSettings ?: RedesignRoute.HOME)
            // 模式页（鼠标/触控板/键盘）的返回键：一律回主页
            "返回主页" -> enterRoute(RedesignRoute.HOME)
            "进入键鼠模式 -> AirMouseScreen" -> enterRoute(RedesignRoute.AIR_MOUSE)
            "展开全屏横屏键盘" -> enterRoute(RedesignRoute.KEYBOARD_LANDSCAPE)
            "退出全屏" -> enterRoute(RedesignRoute.KEYBOARD_PORTRAIT)
            // 左键 / 右键不再走 Click：按住要能拖动，改用 RedesignAction.SetButton（见 setButton）
            "鼠标中键点击" -> sendClick("middle")
            "陀螺仪姿态归零复位" -> {
                ifConnected { client.sendReset() }
                engine.reset()
                onHint("姿态已归零")
            }
            "手动输入 IP/端口" -> showManualConnectDialog = true
            "打开文件传输面板" -> openFilePicker()
            "重置所有信任设备" -> dispatch(RedesignAction.ResetTrust)
            "切换到分段:0" -> dispatch(RedesignAction.SetMode(0))
            "切换到分段:1" -> dispatch(RedesignAction.SetMode(1))
            "切换到分段:2" -> dispatch(RedesignAction.SetMode(2))
            "迷你触控板位置微调", "CameraX + ML Kit 二维码扫描" -> Unit
            else -> onHint(label)
        }
    }

    // -----------------------------------------------------------------------
    // 协议发送（全部先判连接态，避免断线时刷 Toast）
    // -----------------------------------------------------------------------

    private inline fun ifConnected(block: () -> Unit) {
        if (client.state == AirTouchClient.State.CONNECTED) block()
    }

    private fun sendMove(dx: Int, dy: Int) = ifConnected {
        // 触摸板灵敏度：SettingsStore 的默认 1.0 对应 1dp ≈ 1.4px，这里按倍数缩放。
        // 注意**不能**直接 (dx * k).toInt()：灵敏度不是整数（比如 0.8）或者单帧位移很小时，
        // 每一帧都会被截成 0，于是「手机上明明在拖、电脑上一动不动」。余数必须攒起来，攒够 1px 再发。
        val k = settings.touchpadSensitivity
        val fx = dx * k + moveCarryX
        val fy = dy * k + moveCarryY
        val sx = fx.toInt()
        val sy = fy.toInt()
        moveCarryX = fx - sx
        moveCarryY = fy - sy
        if (sx != 0 || sy != 0) {
            client.sendMove(sx, sy)
        }
    }

    private fun sendClick(button: String) = ifConnected { client.sendClick(button) }

    /**
     * 鼠标键按下 / 抬起（拖拽）。轻点一下再松开 = 一次普通单击，按住不放再移动 = 拖动。
     * [releaseHeldButtons] 是所有「拖到一半被打断」路径的统一兜底。
     */
    private fun setButton(button: String, down: Boolean) {
        if (down) {
            if (client.state != AirTouchClient.State.CONNECTED) return
            heldButtons += button
        } else if (!heldButtons.remove(button)) {
            return // 没按住过就别补一帧抬起，避免多一次无意义的按键变化
        }
        client.sendButton(button, down)
        mainHandler.post { state = state.copy(heldButton = heldButtons.lastOrNull()) }
    }

    /** 拖拽被打断（切页 / 进后台 / 断线 / 退出）时补一次抬起，否则电脑上左键会一直按着。 */
    private fun releaseHeldButtons() {
        if (heldButtons.isEmpty()) return
        if (client.state == AirTouchClient.State.CONNECTED) {
            heldButtons.forEach { client.sendButton(it, false) }
        }
        heldButtons.clear()
        mainHandler.post { state = state.copy(heldButton = null) }
    }

    private fun sendScrollDelta(delta: Int, horizontal: Boolean) = ifConnected {
        client.sendScrollDelta(delta, horizontal)
    }

    private fun sendUnicode(text: String) = ifConnected { client.sendUnicodeText(text) }

    /**
     * 九键拼音词库：第一次按九键数字时才开始后台加载（约 130KB、近六千行，主线程解析会卡住第一帧）。
     * 读取失败时词库退化成空表 —— 九键仍然能打标点和英文字母，只是没有中文候选，要如实告诉用户。
     */
    private fun ensureT9Dictionary() {
        if (t9Composer.isReady || t9DictionaryRequested) return
        t9DictionaryRequested = true
        PinyinDictionary.loadAsync(activity) { dictionary ->
            t9Composer.attach(dictionary)
            publishT9()
            if (dictionary.isEmpty) onHint("拼音词库读取失败，九键暂时只有数字和标点")
        }
    }

    /** 把正在组合的数字串和候选写回界面状态（键盘页的候选条读它） */
    private fun publishT9() {
        val composition = t9Composer.compositionLabel()
        val candidates = t9Composer.candidates().map { it.text }
        mainHandler.post {
            state = state.copy(t9Composition = composition, t9Candidates = candidates)
        }
    }

    private fun screenshot() {
        // 现有协议（Protocol.TYPE_*）没有「截屏回传」帧：只能发送 PrintScreen 按键。
        // 若服务端通过 file_* 回传了图片，会在 onFileReceived 里标记 screenshotAvailable。
        ifConnected {
            client.sendKey(Vk.SNAPSHOT, true)
            client.sendKey(Vk.SNAPSHOT, false)
        }
        onHint("已发送 PrintScreen（当前协议无截屏回传帧）")
    }

    /** 把页面上的键位标签翻译成 Win32 虚拟键码；返回 null 表示走 Unicode 文本帧。 */
    private fun vkFor(key: String): Int? = when (key) {
        "Esc", "Escape" -> Vk.ESCAPE
        "Tab" -> Vk.TAB
        "Enter", "↵", "Enter ↵", "回车", "NumEnter" -> Vk.ENTER
        "Space", "空格" -> Vk.SPACE
        "⌫", "⌫ Bksp", "Backspace" -> Vk.BACKSPACE
        "Del", "Delete" -> Vk.DELETE
        "PrtSc" -> Vk.SNAPSHOT
        "Caps" -> Vk.CAPITAL
        "Shift", "⇧" -> Vk.LSHIFT
        "Ctrl" -> Vk.LCONTROL
        "Alt" -> Vk.LMENU
        "Win" -> Vk.LWIN
        "←", "◀" -> Vk.LEFT
        "→", "▶" -> Vk.RIGHT
        "↑", "▲", "▲▼" -> Vk.UP
        "↓", "▼" -> Vk.DOWN
        "NumLock", "Num" -> Vk.NUMLOCK
        "NumAdd" -> Vk.ADD
        "NumSubtract" -> Vk.SUBTRACT
        "NumMultiply" -> Vk.MULTIPLY
        "NumDivide" -> Vk.DIVIDE
        "NumDecimal" -> Vk.DECIMAL
        "-" -> Vk.OEM_MINUS
        "=" -> Vk.OEM_PLUS
        "[" -> Vk.OEM_4
        "]" -> Vk.OEM_6
        "\\" -> Vk.OEM_5
        ";" -> Vk.OEM_1
        "'" -> Vk.OEM_7
        "," -> Vk.OEM_COMMA
        "." -> Vk.OEM_PERIOD
        "/" -> Vk.OEM_2
        "`" -> Vk.OEM_3
        else -> when {
            key.length == 1 && key[0] in '0'..'9' -> Vk.digit(key[0] - '0')
            key.length == 1 && key[0].uppercaseChar() in 'A'..'Z' -> Vk.letter(key[0])
            key.startsWith("F") && (key.drop(1).toIntOrNull()?.let { it in 1..12 } == true) ->
                Vk.function(key.drop(1).toInt())
            key.startsWith("Num") && (key.drop(3).toIntOrNull()?.let { it in 0..9 } == true) ->
                Vk.numpadDigit(key.drop(3).toInt())
            else -> null
        }
    }

    /** 完整的一次 Ctrl+X：按下 Ctrl → 按下并抬起 X → 抬起 Ctrl（电脑上就是复制/粘贴）。 */
    private fun sendCtrlKey(vk: Int) {
        ifConnected {
            client.sendKey(Vk.LCONTROL, true)
            client.sendKey(vk, true)
            client.sendKey(vk, false)
            client.sendKey(Vk.LCONTROL, false)
        }
    }

    private fun modifierVk(name: String): Int? = when (name) {
        "Ctrl" -> Vk.LCONTROL
        "Shift" -> Vk.LSHIFT
        "Alt" -> Vk.LMENU
        "Win" -> Vk.LWIN
        "Caps" -> Vk.CAPITAL
        else -> null
    }

    private fun pressKey(key: String) {
        // 九键上的「复制 / 粘贴」：一次发完整的一套 Ctrl+C / Ctrl+V，
        // 不用先点 Ctrl 粘滞锁（那样要点两下才能复制）。
        if (key == "复制" || key == "粘贴") {
            sendCtrlKey(Vk.letter(if (key == "复制") 'C' else 'V'))
            return
        }
        // 键帽「上滑上档」：`Shift+X` = 按住 Shift 再按 X（上滑打大写字母 / 数字键的上档符号）。
        // 必须在这里展开：`vkFor("Shift+X")` 返回 null，会掉进下面的 Unicode 文本注入，
        // 把 "Shift+X" 这串字原样打到电脑上。
        if (key.startsWith("Shift+") && key.length > "Shift+".length) {
            val base = vkFor(key.substring("Shift+".length))
            if (base != null) {
                ifConnected {
                    client.sendKey(Vk.LSHIFT, true)
                    client.sendKey(base, true)
                    client.sendKey(base, false)
                    client.sendKey(Vk.LSHIFT, false)
                }
            }
            return
        }
        val vk = vkFor(key)
        if (vk == null) {
            // 中文标点、T9 数字串、表情等：走 Unicode 文本注入（协议 §2.7）
            val text = when (key) {
                "123" -> "123"
                "1 @" -> "1"
                "2 ABC" -> "2"
                "3 DEF" -> "3"
                "4 GHI" -> "4"
                "5 JKL" -> "5"
                "6 MNO" -> "6"
                "7 PQRS" -> "7"
                "8 TUV" -> "8"
                "9 WXYZ" -> "9"
                else -> key
            }
            if (text.isNotEmpty()) sendUnicode(text)
            return
        }
        // 粘滞修饰键必须先按下、最后抬起，否则组合键会变成裸按键
        val mods = keyboardSession.activeModifiers()
        ifConnected {
            mods.forEach { client.sendKey(it, true) }
            client.sendKey(vk, true)
            client.sendKey(vk, false)
            mods.reversed().forEach { client.sendKey(it, false) }
        }
    }

    // -----------------------------------------------------------------------
    // 陀螺仪传感器（沿用现有 AirMouseEngine，不改其内部逻辑）
    // -----------------------------------------------------------------------

    /** 陀螺仪只在鼠标页有意义：触控板 / 键盘页必须关掉，否则手一转手机光标就乱飞。 */
    private fun sensorShouldRun(): Boolean =
        foreground && state.connected && !state.paused && route == RedesignRoute.AIR_MOUSE

    /** 按「当前是否真的需要陀螺仪」重新决定注册 / 注销。页面切换、连上、断开、前后台都走它。 */
    private fun refreshSensors() {
        if (sensorShouldRun()) startSensors() else stopSensors()
    }

    private fun startSensors() {
        if (!sensorShouldRun()) return
        val sm = sensorManager ?: return
        val sensor = gyroSensor ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
            onHint("此设备没有陀螺仪，鼠标模式不可用")
            return
        }
        gyroSensor = sensor
        val periodUs = max(2000, sensor.minDelay)
        sm.registerListener(this, sensor, periodUs, sensorHandler)
    }

    private fun stopSensors() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "stopSensors", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 双保险：即便某一刻没来得及注销，也绝不在非鼠标页把姿态位移发出去。
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (state.paused || route != RedesignRoute.AIR_MOUSE) return
        when (val out = engine.update(event.values, event.timestamp)) {
            is AirMouseEngine.Output.Move -> sendMove(out.dx, out.dy)
            is AirMouseEngine.Output.Scroll -> ifConnected { client.sendScroll(out.amount) }
            null -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // -----------------------------------------------------------------------
    // AirTouchClient.Listener（回调都在后台线程 → post 回主线程）
    // -----------------------------------------------------------------------

    override fun onOpen() = Unit

    override fun onAuthenticating(mode: String) {
        mainHandler.post { state = state.copy(statusText = "正在认证…") }
    }

    override fun onWelcome(info: AirTouchClient.SessionInfo) {
        mainHandler.post {
            settings.lastHost = client.serverHost
            settings.lastPort = client.serverPort
            if (info.sid.isNotEmpty()) settings.lastSid = info.sid
            info.keyId?.let { settings.lastKeyId = it }
            client.token?.let { settings.lastToken = it }

            wifiLock.acquire()
            client.sendHello(client.listenerDeviceName, AppVersion.of(activity))
            settings.zoomPreference?.let { client.sendZoomMode(it) }

            state = state.copy(
                connected = true,
                connectionLabel = client.serverHost + ":" + client.serverPort,
                deviceIdentity = info.keyId?.takeIf { it.isNotEmpty() }
                    ?.let { "已信任 ${it.take(8)}" } ?: "已信任设备",
                // 首页不再显示「已连接 · 服务端 x.y.z」那一行：连接状态由顶部胶囊/延迟行表达。
                // statusText 仍然用于错误（"错误 code"）与断开（"已断开"），空串时首页那一行自动隐藏。
                statusText = "",
            )
            refreshSensors()
            if (!pingRunning) {
                pingRunning = true
                mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
            }
            onHint("已连接 ${state.connectionLabel}")
        }
    }

    override fun onFileAck(fileName: String, status: String, bytes: Long, savedPath: String?, message: String?) {
        mainHandler.post {
            state = state.copy(fileStatus = if (status.equals("ok", true)) "已发送 $fileName" else "发送失败: $fileName")
            onHint(state.fileStatus)
        }
    }

    override fun onError(code: String, message: String) {
        mainHandler.post {
            state = state.copy(statusText = "错误 $code")
            onHint("服务端错误 $code: $message")
        }
    }

    override fun onPong() {
        if (lastPingSentMs <= 0L) return
        val rtt = (SystemClock.elapsedRealtime() - lastPingSentMs).toInt()
        mainHandler.post { state = state.copy(latencyMs = rtt) }
    }

    override fun onZoomStatus(status: ZoomStatus) {
        Log.d(TAG, "zoom_status: $status")
    }

    override fun onUnknownText(type: String, raw: String) {
        Log.d(TAG, "unknown frame type=$type")
    }

    override fun onFileReceiveProgress(fileName: String, received: Long, total: Long) {
        val pct = if (total > 0) (received * 100 / total).toInt() else 0
        mainHandler.post { state = state.copy(fileStatus = "接收 $fileName $pct%") }
    }

    override fun onFileReceived(fileName: String, savedPath: String, bytes: Long, viewUri: Uri?) {
        mainHandler.post {
            val isImage = fileName.substringAfterLast('.', "").lowercase() in
                setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
            state = state.copy(
                fileStatus = "已收到 $fileName → $savedPath",
                screenshotAvailable = isImage,
            )
            onHint("已收到文件 $fileName")
        }
    }

    override fun onFileReceiveFailed(fileName: String, reason: String) {
        mainHandler.post {
            state = state.copy(fileStatus = "接收失败 $fileName")
            onHint("接收失败: $reason")
        }
    }

    override fun onReconnecting(attempt: Int, delayMs: Long) {
        mainHandler.post { state = state.copy(statusText = "自动重连中（第 $attempt 次）") }
    }

    override fun onClosed(code: Int, reason: String) {
        mainHandler.post {
            state = state.copy(connected = false, statusText = "连接已关闭", latencyMs = -1)
        }
        wifiLock.release()
    }

    override fun onFailure(t: Throwable) {
        mainHandler.post {
            state = state.copy(connected = false, statusText = "连接失败")
            onHint("连接失败: ${t.message}")
        }
    }

    // -----------------------------------------------------------------------
    // PinnedTrust.PinListener
    // -----------------------------------------------------------------------

    override fun onPinStored(id: String, pin: String) {
        mainHandler.post { onHint("已记录服务器证书指纹（TOFU）") }
    }

    override fun onPinMismatch(id: String, expectedPin: String, actualPin: String) {
        mainHandler.post { onHint("证书指纹不匹配，连接已中断，请重新扫码") }
    }

    // -----------------------------------------------------------------------
    // 内部工具
    // -----------------------------------------------------------------------

    private fun initialState(): RedesignUiState = RedesignUiState(
        darkTheme = prefs.darkTheme,
        accent = AccentPreset.entries.firstOrNull { it.key == prefs.accentKey } ?: AccentPreset.YELLOW,
        customAccent = prefs.customAccentArgb?.let { Color(it) },
        leftHandMode = prefs.leftHand,
        hapticEnabled = prefs.haptic,
        connected = false,
        sensitivity = settings.touchpadSensitivity,
        touchZeroLatency = settings.touchZeroLatency,
        airMouseInvertX = settings.airMouseInvertX,
        airMouseInvertY = settings.airMouseInvertY,
        t9Mode = settings.keyboardLayout != SettingsStore.DEFAULT_KEYBOARD_LAYOUT,
        connectionLabel = if (settings.lastHost.isNotEmpty()) {
            "${settings.lastHost}:${settings.lastPort}"
        } else "尚未连接",
        deviceIdentity = if (settings.lastKeyId.isNotEmpty()) {
            "已信任 ${settings.lastKeyId.take(8)}"
        } else "未信任设备",
        statusText = "未连接",
    )

    private fun stateTextOf(s: AirTouchClient.State): String = when (s) {
        AirTouchClient.State.IDLE -> "未连接"
        AirTouchClient.State.CONNECTING -> "正在连接…"
        AirTouchClient.State.AUTHENTICATING -> "正在认证…"
        AirTouchClient.State.CONNECTED -> "已连接"
        AirTouchClient.State.RECONNECTING -> "自动重连中"
        AirTouchClient.State.CLOSED -> "连接已关闭"
        AirTouchClient.State.FAILED -> "连接失败"
    }

    companion object {
        private const val TAG = "RedesignController"
        private const val PING_INTERVAL_MS = 2000L

        /** 供外部（如旧版扫码结果）显式启动时传入连接参数；键名保持历史兼容 */
        const val EXTRA_HOST = "com.airtouch.app.extra.HOST"
        const val EXTRA_PORT = "com.airtouch.app.extra.PORT"
        const val EXTRA_TOKEN = "com.airtouch.app.extra.TOKEN"
        const val EXTRA_SID = "com.airtouch.app.extra.SID"
        const val EXTRA_FP = "com.airtouch.app.extra.FP"
    }
}
