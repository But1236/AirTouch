package com.airtouch.app.net

import android.content.Context
import android.util.Log
import com.airtouch.app.settings.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * AirTouch 的 WebSocket 客户端（协议 v2）。
 *
 * ## 连接生命周期（与 v1 最大的区别）
 * v1 把「TCP/WS 握手成功」当成已连接，于是网络一抖就要重新扫码。v2 把状态拆开：
 *
 * ```
 * CONNECTING ──WS 打开──> AUTHENTICATING ──auth_challenge/auth_req/welcome──> CONNECTED
 *                              │                                              │
 *                              └──────── 失败（可重试）────────────────────────┘
 *                                              ↓
 *                                        RECONNECTING ──退避重试（用设备私钥，不再需要 token）
 * ```
 *
 * 只有收到 `welcome` 才算 [State.CONNECTED]，控制指令在此之前一律被丢弃；断线后只要不是
 * 「电脑端明确拒绝」（key_unknown / banned / …）就自动退避重连，用户完全不需要重新扫码。
 *
 * ## 线程模型
 * - 回调都在 OkHttp 的线程（连接线程 / 读线程）或重连调度线程上触发，本类**不切主线程**，
 *   界面实现必须自己 post 回主线程；
 * - 发送方法可从任意线程调用（OkHttp 的 `WebSocket.send` 线程安全）。
 */
class AirTouchClient(
    private val context: Context,
    private val store: SettingsStore,
    private val listener: Listener,
    private val pinListener: PinnedTrust.PinListener? = null
) {

    enum class State {
        IDLE,
        CONNECTING,
        AUTHENTICATING,
        CONNECTED,
        RECONNECTING,
        CLOSED,
        FAILED
    }

    /** 服务端 welcome 携带的全部会话信息（协议 §1.2 第 3 步）。 */
    data class SessionInfo(
        val serverVersion: String,
        val serverIp: String,
        val port: Int,
        val sid: String,
        val deviceName: String?,
        val keyId: String?,
        val authMode: String,
        val trustedCount: Int,
        val maxFileSize: Long,
        val chunkSize: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        /** 电脑主显示器刷新率（Hz，0 表示电脑没报）。感知上的帧率上限就是它。 */
        val displayHz: Int
    )

    /** 业务回调。全部在后台线程触发，实现方负责切主线程。 */
    interface Listener {

        /** WS 握手完成（还没有认证，不能发控制指令） */
        fun onOpen()

        /** 收到 auth_challenge，正在用设备私钥应答 */
        fun onAuthenticating(mode: String) {}

        /** 服务端 welcome：认证通过，会话可用 */
        fun onWelcome(info: SessionInfo)

        /** 服务端 file_ack（协议 §2.2）：对手机发出的文件的回执 */
        fun onFileAck(fileName: String, status: String, bytes: Long, savedPath: String?, message: String?)

        /** 服务端 error（协议 §2.2） */
        fun onError(code: String, message: String)

        /** 收到 pong */
        fun onPong()

        /** 未知 type：忽略即可，这里单独回调便于调试（协议 §2.3） */
        fun onUnknownText(type: String, raw: String)

        /** 文件接收进度（仅在服务端向手机发文件时触发） */
        fun onFileReceiveProgress(fileName: String, received: Long, total: Long)

        /**
         * 服务端发来的文件已保存。
         * @param savedPath 用户可照着找的路径（「内部存储/Download/x」）
         * @param viewUri 可直接交给系统「打开方式」的 content:// URI；可能为 null
         */
        fun onFileReceived(fileName: String, savedPath: String, bytes: Long, viewUri: android.net.Uri?)

        /** 服务端发来的文件接收失败 */
        fun onFileReceiveFailed(fileName: String, reason: String)

        /** 正在自动重连（第 attempt 次，等待 delayMs 毫秒） */
        fun onReconnecting(attempt: Int, delayMs: Long) {}

        /** 服务端通信测试结果（协议 §2.4） */
        fun onDiagReport(report: ServerDiagReport) {}

        /** 连接关闭（code == -1 表示本地主动关闭前没有对端关闭帧） */
        fun onClosed(code: Int, reason: String)

        /** 连接最终失败（不再重连） */
        fun onFailure(t: Throwable)
    }

    /** 连接状态变化回调，同样在后台线程触发 */
    fun interface StateListener {
        fun onStateChanged(state: State)
    }

    @Volatile
    var state: State = State.IDLE
        private set

    var stateListener: StateListener? = null

    /** 当前会话的 token（配对时为一次性票据；重连会话为空） */
    @Volatile
    var token: String? = null
        private set

    @Volatile
    var serverHost: String = ""
        private set

    @Volatile
    var serverPort: Int = PairingUri.DEFAULT_PORT
        private set

    @Volatile
    var session: SessionInfo? = null
        private set

    /** 认证失败/被拒绝的原因，供界面显示；连接成功后清空 */
    @Volatile
    var lastRejectionCode: String? = null
        private set

    @Volatile
    var lastRejectionMessage: String? = null
        private set

    private var webSocket: WebSocket? = null
    private val sendLock = Any()

    @Volatile
    private var receiver: com.airtouch.app.file.FileReceiver? = null

    /** 由 Activity 注入的设备名（上报给电脑端显示） */
    @Volatile
    var listenerDeviceName: String = "Android"

    private var currentUri: PairingUri? = null

    /** 是否允许自动重连（用户主动断开/致命错误后置 false） */
    @Volatile
    private var autoReconnect = true

    /** reconnect 时用的退避调度器（单线程守护线程，不持有界面引用）。 */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "airtouch-reconnect").apply { isDaemon = true }
    }

    private var pendingReconnect: ScheduledFuture<*>? = null

    private var reconnectAttempt = 0

    /** 最近一次 auth_challenge 里服务端的时间与本地时间的差（秒），用于校正手机时钟。 */
    @Volatile
    private var serverTimeOffsetSeconds: Long = 0L

    // ---------------- 连接 ----------------

    /**
     * 建立连接。同一实例重复调用会先关闭旧连接。
     * @return true 表示已发起连接（最终结果通过 [Listener] 回调）
     */
    fun connect(uri: PairingUri): Boolean {
        closeInternal()
        autoReconnect = true
        reconnectAttempt = 0
        lastRejectionCode = null
        lastRejectionMessage = null
        currentUri = uri
        session = null

        serverHost = uri.host
        serverPort = uri.port
        token = uri.token.ifEmpty { null }

        return openSocket(uri, State.CONNECTING)
    }

    private fun openSocket(uri: PairingUri, initialState: State): Boolean {
        // 每个目标 host 一套独立的证书固定策略（fp 严格校验 / 无 fp 时 TOFU）
        val trust = PinnedTrust(store, uri.host, uri.sid, uri.fp, pinListener)

        // 自签名证书 + 指纹固定：OkHttpClient 复用（连接池/线程池），只换 SSLSocketFactory。
        val client = baseBuilder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.x509TrustManager())
            .hostnameVerifier(trust.hostnameVerifier())
            .build()

        val request = try {
            Request.Builder().url(uri.toUrl()).build()
        } catch (e: Exception) {
            failFatally(e)
            return false
        }

        setState(initialState)
        val ws = client.newWebSocket(request, InnerListener())
        synchronized(sendLock) { webSocket = ws }
        return true
    }

    private fun baseBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // 读/写超时置 0（不限制）：wss 长连接空闲时本来就没有数据，靠 ping/pong 判活；
            // 大文件分块发送也不应被单次写超时打断。
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            // 客户端每 20s 发一个 WebSocket Ping 帧（比服务端的 30s 更密）
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(NoDelaySocketFactory)
    }

    /**
     * 关掉 Nagle 算法（`TCP_NODELAY`）的 socket 工厂。
     *
     * ## 为什么必须自己设
     *
     * OkHttp 4.12 **不会**调用 `setTcpNoDelay`（整个 jar 里没有这个符号），而 Linux/Android 的
     * 默认值是「Nagle 打开」。服务端那边的 Fleck 明确设了 `NoDelay = true` —— 于是这条链路成了
     * 「手机 Nagle 开、电脑 NoDelay 开」，方向恰好是最要命的那个：手机是发送方。
     *
     * ## 病历（用户实测：平均到达间隔 6ms，最大 236ms）
     *
     * 空中鼠标每帧只有约 40 字节的小包，每秒 144~500 个：
     * 1. Nagle 规则：有未确认数据时，**小包要等 ACK 才能发**；
     * 2. Windows 的延迟 ACK 最多等 200ms 才回 ACK；
     * 3. 于是手机攒着不发 → 电脑不回 ACK → 手机继续攒 … 直到延迟 ACK 定时器到期，
     *    一瞬间涌出一串帧。现象就是「平均 6ms 很流畅，但每隔一会儿冻结 200+ms」，
     *    实体鼠标（USB HID 中断传输，与 Nagle 无关）自然看起来连续得多。
     *
     * 关掉 Nagle 后每个小包立即出栈，这个 200ms 级的空洞就消失了。
     * 代价只是小包各占一个 TCP 段（局域网 + 每秒几百帧完全无所谓）。
     */
    private object NoDelaySocketFactory : javax.net.SocketFactory() {

        private fun Socket.tuned(): Socket = apply {
            try {
                tcpNoDelay = true
            } catch (e: Exception) {
                Log.w(TAG, "设置 TCP_NODELAY 失败（不影响连接，但可能出现 200ms 级停顿）", e)
            }
        }

        override fun createSocket(): Socket = Socket().tuned()

        override fun createSocket(host: String, port: Int): Socket = Socket(host, port).tuned()

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Socket(host, port, localHost, localPort).tuned()

        override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port).tuned()

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            Socket(address, port, localAddress, localPort).tuned()
    }

    // ---------------- 发送 ----------------

    fun sendText(json: String): Boolean {
        val ws = synchronized(sendLock) { webSocket } ?: return false
        return try {
            ws.send(json)
        } catch (e: Exception) {
            Log.w(TAG, "sendText failed", e)
            false
        }
    }

    /**
     * 发送二进制分块。协议 §3.2：帧里只有原始文件字节，不带任何长度前缀。
     * 最后一次可少于 CHUNK_SIZE。
     */
    fun sendBinary(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return true
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            Log.w(TAG, "sendBinary 参数越界 offset=$offset length=$length size=${bytes.size}")
            return false
        }
        val ws = synchronized(sendLock) { webSocket } ?: return false
        return try {
            val bs = bytes.toByteString(offset, length)
            ws.send(bs)
        } catch (e: Exception) {
            Log.w(TAG, "sendBinary failed", e)
            false
        }
    }

    fun sendMove(dx: Int, dy: Int, seq: Int? = null): Boolean =
        sendText(Protocol.buildMove(dx, dy, seq))

    fun sendMoveTo(x: Int, y: Int, seq: Int? = null): Boolean =
        sendText(Protocol.buildMoveTo(x, y, seq))

    fun sendClick(button: String): Boolean = sendText(Protocol.buildClick(button))

    fun sendScroll(amount: Int): Boolean = sendText(Protocol.buildScroll(amount))

    fun sendReset(): Boolean = sendText(Protocol.buildReset())

    fun sendFileHeader(name: String, size: Long): Boolean =
        sendText(Protocol.buildFileHeader(name, size))

    fun sendFileEnd(): Boolean = sendText(Protocol.buildFileEnd())

    fun sendPing(): Boolean = sendText(Protocol.buildPing())

    fun sendHello(deviceName: String, appVersion: String): Boolean =
        sendText(Protocol.buildHello(deviceName, appVersion))

    fun sendDiagBegin(testKind: String, mode: String, planned: Int, targetHz: Int, circleMs: Int): Boolean =
        sendText(Protocol.buildDiagBegin(testKind, mode, planned, targetHz, circleMs))

    fun sendDiagReport(
        testKind: String,
        mode: String,
        sent: Int,
        planned: Int,
        firstSeq: Int,
        lastSeq: Int,
        elapsedMs: Long,
        targetHz: Int,
        circleMs: Int,
        maxSendGapMs: Int
    ): Boolean = sendText(
        Protocol.buildDiagReport(
            testKind, mode, sent, planned, firstSeq, lastSeq, elapsedMs, targetHz, circleMs, maxSendGapMs
        )
    )

    // ---------------- 关闭 / 重连 ----------------

    /** 用户主动断开：停止自动重连，取消进行中的接收，发正常关闭帧。 */
    fun close() {
        autoReconnect = false
        cancelReconnect()
        abortReceive("连接已关闭，取消接收")
        val ws = synchronized(sendLock) { webSocket }
        if (ws != null) {
            ws.close(1000, "client closing")
        }
        synchronized(sendLock) { webSocket = null }
        setState(State.CLOSED)
    }

    /** 关闭旧 socket 但保留自动重连策略（内部重连时使用）。 */
    private fun closeInternal() {
        abortReceive("重新连接，取消上一次接收")
        val ws = synchronized(sendLock) { webSocket }
        if (ws != null) {
            ws.cancel()
        }
        synchronized(sendLock) { webSocket = null }
    }

    /** 立即重连一次（界面上的「重试」按钮）。 */
    fun reconnectNow(): Boolean {
        val uri = currentUri ?: return false
        cancelReconnect()
        reconnectAttempt = 0
        autoReconnect = true
        closeInternal()
        // 重连永远走「无 token」的 resume 路径：一次性票据早就被销毁了
        return openSocket(uri.copy(token = ""), State.CONNECTING)
    }

    private fun scheduleReconnect(reason: String) {
        if (!autoReconnect) return
        val uri = currentUri ?: return
        if (pendingReconnect?.isDone == false) return

        reconnectAttempt++
        // 1s、2s、4s、8s…最多 15s；电脑关机/换网时不要打得太密。
        val delayMs = minOf(15_000L, 1_000L shl minOf(reconnectAttempt - 1, 4))
        setState(State.RECONNECTING)
        listener.onReconnecting(reconnectAttempt, delayMs)
        Log.i(TAG, "将在 ${delayMs}ms 后重连（第 $reconnectAttempt 次）：$reason")

        pendingReconnect = scheduler.schedule({
            if (!autoReconnect) return@schedule
            val resumeUri = uri.copy(token = "")
            openSocket(resumeUri, State.CONNECTING)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelReconnect() {
        pendingReconnect?.cancel(false)
        pendingReconnect = null
    }

    private fun abortReceive(reason: String) {
        val r = receiver
        receiver = null
        if (r != null) {
            try {
                r.abort()
            } catch (e: Exception) {
                Log.w(TAG, "receiver.abort failed", e)
            }
        }
    }

    private fun setState(s: State) {
        state = s
        val l = stateListener
        if (l != null) {
            try {
                l.onStateChanged(s)
            } catch (e: Exception) {
                Log.w(TAG, "stateListener threw", e)
            }
        }
    }

    private fun failFatally(t: Throwable) {
        autoReconnect = false
        cancelReconnect()
        setState(State.FAILED)
        listener.onFailure(t)
    }

    /** 释放调度线程。Activity 销毁时必须调用。 */
    fun shutdown() {
        autoReconnect = false
        cancelReconnect()
        scheduler.shutdownNow()
        closeInternal()
    }

    // ---------------- OkHttp 回调 ----------------

    private inner class InnerListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 注意：这里**不是**已连接 —— 还要等 auth_challenge / welcome（协议 §1.2）
            setState(State.AUTHENTICATING)
            listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                routeText(text)
            } catch (e: Exception) {
                // 坏帧不能打断读线程（协议 §2.3：未知内容一律忽略）
                Log.w(TAG, "routeText failed: $text", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                handleBinary(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "handleBinary failed", e)
                abortReceive("文件写入失败：" + (e.message ?: e.javaClass.simpleName))
                listener.onFileReceiveFailed("", e.message ?: "写入失败")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            abortReceive("连接已关闭")
            val rejection = lastRejectionCode
            if (shouldReconnectAfter(code, rejection)) {
                scheduleReconnect("closed $code $reason")
            } else {
                setState(State.CLOSED)
                listener.onClosed(code, reason)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            abortReceive("连接失败")
            val translated = translateFailure(t)

            // 证书固定失败 / 认证被明确拒绝 → 不再重试，直接告诉用户怎么办
            if (translated is CertificateException || rejectionIsFatal(lastRejectionCode)) {
                failFatally(translated)
                return
            }

            if (autoReconnect) {
                scheduleReconnect("failure: ${translated.message}")
            } else {
                setState(State.FAILED)
                listener.onFailure(translated)
            }
        }
    }

    /**
     * 断线后是否自动重连。
     *
     * 1000/1001 是对端正常关闭（例如用户点了「断开」），不重连；被电脑端明确拒绝的码
     * （未信任/封禁/协议不支持）也不重连 —— 那种情况必须重新扫码，重试只会刷屏。
     */
    private fun shouldReconnectAfter(closeCode: Int, rejection: String?): Boolean {
        if (!autoReconnect) return false
        if (closeCode == 1000) return false
        if (rejectionIsFatal(rejection)) return false
        return true
    }

    /** 这些错误重试也没有意义，必须人工处理（重新扫码 / 等待 / 改配置）。 */
    private fun rejectionIsFatal(code: String?): Boolean = when (code) {
        "key_unknown", "banned", "auth_unsupported", "bad_key", "sig_invalid", "trust_revoked" -> true
        else -> false
    }

    private fun translateFailure(t: Throwable): Throwable {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is CertificateException) return cur
            if (cur is SSLHandshakeException && cur.cause is CertificateException) {
                return cur.cause as CertificateException
            }
            cur = cur.cause
            depth++
        }
        return t
    }

    // ---------------- 消息路由 ----------------

    private fun routeText(text: String) {
        val o = Protocol.parseObject(text)
        if (o == null) {
            listener.onUnknownText("", text)
            return
        }
        val type = o.optString(Protocol.FIELD_TYPE, "")
        when (type) {
            Protocol.TYPE_AUTH_CHALLENGE -> handleChallenge(o)

            Protocol.TYPE_WELCOME -> handleWelcome(o)

            Protocol.TYPE_FILE_HEADER_FROM_SERVER -> startReceive(o)

            Protocol.TYPE_FILE_END_FROM_SERVER -> finishReceive(declaredEnd = true)

            Protocol.TYPE_FILE_ACK -> listener.onFileAck(
                fileName = o.optString("fileName", ""),
                status = o.optString("status", "error"),
                bytes = optLong(o, "bytes", 0L),
                savedPath = if (o.has("savedPath")) o.optString("savedPath", "") else null,
                message = if (o.has("message")) o.optString("message", "") else null
            )

            Protocol.TYPE_ERROR -> {
                val code = o.optString("code", "unknown")
                val message = o.optString("message", "")
                lastRejectionCode = code
                lastRejectionMessage = message
                // 配对票据失效。/ 未信任：直接改成「用设备私钥重连」重试一次，用户无感。
                if (token != null && (code == "token_expired" || code == "token_invalid")) {
                    Log.i(TAG, "配对票据已失效（$code），改为设备密钥重连")
                }
                listener.onError(code, message)
            }

            Protocol.TYPE_PONG -> listener.onPong()

            "diag_report" -> listener.onDiagReport(parseDiagReport(o))

            else -> listener.onUnknownText(type, text)
        }
    }

    /** 协议 §1.2 第 2 步：用设备私钥对 nonce 签一次名。 */
    private fun handleChallenge(o: JSONObject) {
        val sid = o.optString("sid", "")
        val nonce = o.optString("nonce", "")
        val serverTime = optLong(o, "serverTime", 0L)
        val mode = o.optString("mode", "resume")

        if (sid.isEmpty() || nonce.isEmpty()) {
            failFatally(IllegalStateException("服务端下发的 auth_challenge 缺少 sid/nonce"))
            return
        }

        // 用服务端时间校正本机时钟：手机时间偏几分钟也不至于被判 stale_auth。
        if (serverTime > 0) {
            serverTimeOffsetSeconds = serverTime - AuthCrypto.unixNow()
            if (kotlin.math.abs(serverTimeOffsetSeconds) > 5) {
                Log.w(TAG, "本机时钟与服务端相差 ${serverTimeOffsetSeconds}s，已用服务端时间签名")
            }
        }

        listener.onAuthenticating(mode)

        val identity = try {
            DeviceIdentity.get(context)
        } catch (e: Exception) {
            failFatally(e)
            return
        }

        val request = try {
            identity.buildAuthRequest(
                sid = sid,
                nonce = nonce,
                // 只有配对模式才带 token；重连模式服务端根本不看 token
                token = if (mode == "pair") token else null,
                deviceName = listenerDeviceName,
                appVersion = AppVersion.of(context),
                serverTimeOffsetSeconds = serverTimeOffsetSeconds
            )
        } catch (e: Exception) {
            failFatally(e)
            return
        }

        Log.i(TAG, "应答 auth_challenge（mode=$mode，keyId=${identity.keyId}）")
        sendText(request)
    }

    private fun handleWelcome(o: JSONObject) {
        val info = SessionInfo(
            serverVersion = o.optString("serverVersion", ""),
            serverIp = o.optString("serverIp", ""),
            port = o.optInt("port", serverPort),
            sid = o.optString("sid", ""),
            deviceName = o.optString("deviceName", "").ifEmpty { null },
            keyId = o.optString("keyId", "").ifEmpty { null },
            authMode = o.optString("authMode", ""),
            trustedCount = o.optInt("trustedCount", 0),
            maxFileSize = optLong(o, "maxFileSize", Protocol.MAX_FILE_SIZE),
            chunkSize = o.optInt("chunkSize", Protocol.CHUNK_SIZE),
            screenWidth = o.optInt("screenWidth", 0),
            screenHeight = o.optInt("screenHeight", 0),
            displayHz = o.optInt("displayHz", 0)
        )

        session = info
        lastRejectionCode = null
        lastRejectionMessage = null
        reconnectAttempt = 0
        cancelReconnect()

        // 把这次用到的身份/固定值记进设置：下一次重连（不带 token、可能换了 IP）就靠它们。
        if (info.sid.isNotEmpty()) {
            store.lastSid = info.sid
        }

        val trustKey = if (info.sid.isNotEmpty()) "sid:${info.sid}" else "host:${serverHost.lowercase()}"
        store.getServerPin(trustKey)?.let { store.lastPin = it }
        if (info.keyId != null) store.lastKeyId = info.keyId

        setState(State.CONNECTED)
        listener.onWelcome(info)
    }

    private fun parseDiagReport(o: JSONObject): ServerDiagReport = ServerDiagReport(
        testKind = o.optString("testKind", "circle"),
        mode = o.optString("mode", "abs"),
        planned = o.optInt("planned", 0),
        received = o.optInt("received", 0),
        firstSeq = o.optInt("firstSeq", 0),
        lastSeq = o.optInt("lastSeq", 0),
        lost = o.optInt("lost", 0),
        duplicated = o.optInt("duplicated", 0),
        outOfOrder = o.optInt("outOfOrder", 0),
        maxGapMs = o.optInt("maxGapMs", 0),
        meanGapMs = o.optInt("meanGapMs", 0),
        durationMs = optLong(o, "durationMs", 0L),
        clientSent = o.optInt("clientSent", 0),
        clientElapsedMs = optLong(o, "clientElapsedMs", 0L),
        clientMaxSendGapMs = o.optInt("clientMaxSendGapMs", 0),
        targetHz = o.optInt("targetHz", 0),
        sendInputFailures = optLong(o, "sendInputFailures", 0L),
        startX = o.optInt("startX", 0),
        startY = o.optInt("startY", 0),
        endX = o.optInt("endX", 0),
        endY = o.optInt("endY", 0),
        cursorUpdates = o.optInt("cursorUpdates", 0),
        cursorPollHz = o.optInt("cursorPollHz", 0),
        cursorMaxGapMs = o.optInt("cursorMaxGapMs", 0),
        displayHz = o.optInt("displayHz", 0)
    )

    private fun startReceive(o: JSONObject) {
        // 一个方向同一时刻只允许一个进行中的传输（协议 §3.2）
        abortReceive("新的接收开始，丢弃上一次未完成的文件")

        val name = o.optString(Protocol.FIELD_FILE_NAME, "")
        val size = optLong(o, Protocol.FIELD_FILE_SIZE, 0L)
        if (size <= 0L) {
            listener.onFileReceiveFailed(name, "file_header 中的 fileSize 无效")
            return
        }
        if (size > Protocol.MAX_FILE_SIZE) {
            listener.onFileReceiveFailed(name, "文件超过 512 MiB 上限")
            return
        }
        val id = if (o.has(Protocol.FIELD_FILE_ID)) o.optString(Protocol.FIELD_FILE_ID, "") else null
        val r = com.airtouch.app.file.FileReceiver(context)
        receiver = r
        try {
            r.begin(name, size, id, object : com.airtouch.app.file.FileReceiver.Callback {
                override fun onProgress(fileName: String, received: Long, total: Long) {
                    listener.onFileReceiveProgress(fileName, received, total)
                }

                override fun onCompleted(fileName: String, savedPath: String, bytes: Long, viewUri: android.net.Uri?) {
                    receiver = null
                    listener.onFileReceived(fileName, savedPath, bytes, viewUri)
                }

                override fun onFailed(fileName: String, reason: String) {
                    receiver = null
                    listener.onFileReceiveFailed(fileName, reason)
                }
            })
        } catch (e: Exception) {
            receiver = null
            listener.onFileReceiveFailed(name, e.message ?: "无法创建临时文件")
        }
    }

    private fun handleBinary(bytes: ByteString) {
        val r = receiver
        if (r == null) {
            Log.w(TAG, "收到没有 file_header 的二进制帧，已丢弃 ${bytes.size} 字节")
            return
        }
        r.write(bytes.toByteArray())
    }

    private fun finishReceive(declaredEnd: Boolean) {
        val r = receiver ?: return
        r.end(declaredEnd)
    }

    private fun optLong(o: JSONObject, key: String, def: Long): Long {
        val v = o.opt(key)
        return when (v) {
            null, JSONObject.NULL -> def
            is Number -> v.toLong()
            else -> v.toString().toDoubleOrNull()?.toLong() ?: def
        }
    }

    companion object {
        private const val TAG = "AirTouchClient"
    }
}
