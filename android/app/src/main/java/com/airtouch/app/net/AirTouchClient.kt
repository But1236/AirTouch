package com.airtouch.app.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * AirTouch 的 WebSocket 客户端（协议 v1）。
 *
 * 线程模型（重要）：
 * - 所有回调都在 OkHttp 自己的线程上触发（连接线程 / 读线程）；
 * - **本类不切主线程**，[Listener] 的实现（Activity/Fragment）必须自己用 Handler/Looper
 *   把界面更新 post 到主线程；
 * - 发送方法可从任意线程调用（OkHttp 的 WebSocket.send 是线程安全的），内部仍做了 null 检查。
 */
class AirTouchClient(
    private val context: android.content.Context,
    private val store: com.airtouch.app.settings.SettingsStore,
    private val listener: Listener,
    private val pinListener: PinnedTrust.PinListener? = null
) {

    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        CLOSED,
        FAILED
    }

    /** 业务回调。全部在 OkHttp 线程上触发，实现方负责切主线程。 */
    interface Listener {

        /** WebSocket 握手成功（此时还可以发送 hello） */
        fun onOpen()

        /** 服务端 welcome（协议 §1.2） */
        fun onWelcome(
            serverVersion: String,
            serverIp: String,
            port: Int,
            boundIp: String,
            maxFileSize: Long,
            chunkSize: Int
        )

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

        /** 服务端发来的文件已保存 */
        fun onFileReceived(fileName: String, savedPath: String, bytes: Long)

        /** 服务端发来的文件接收失败 */
        fun onFileReceiveFailed(fileName: String, reason: String)

        /** 连接关闭（code == -1 表示本地主动关闭前没有对端关闭帧） */
        fun onClosed(code: Int, reason: String)

        /** 连接失败（含 TLS 指纹校验失败） */
        fun onFailure(t: Throwable)
    }

    /** 连接状态变化回调，同样在 OkHttp 线程触发 */
    fun interface StateListener {
        fun onStateChanged(state: State)
    }

    @Volatile
    var state: State = State.IDLE
        private set

    var stateListener: StateListener? = null

    /** 当前会话的 token（连接成功后由 ControlActivity 交给 SettingsStore 保存） */
    @Volatile
    var token: String? = null
        private set

    @Volatile
    var serverHost: String = ""
        private set

    @Volatile
    var serverPort: Int = PairingUri.DEFAULT_PORT
        private set

    private var webSocket: WebSocket? = null
    private val sendLock = Any()

    /** 服务端 → 手机 的文件接收器；为空表示当前没有进行中的接收 */
    @Volatile
    private var receiver: com.airtouch.app.file.FileReceiver? = null

    // 说明：本类不为「通用 HTTP」保留 OkHttpClient 字段。OkHttp 的 newWebSocket() 内部
    // 会 clone 出一份客户端（共享连接池/线程池）并持有它，WebSocket 存活期间不会泄漏；
    // 目标 host 在 connect() 时才确定（指纹固定是按 host 的），所以每次连接现场构建
    // 一个带 PinnedTrust 的 client 是正确的做法。

    private fun baseBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            // 连接超时：握手/建连阶段的硬上限
            .connectTimeout(10, TimeUnit.SECONDS)
            // 读写超时置 0（不限制）：wss 长连接在空闲时本来就没有数据流动，
            // 设置读超时会在空闲期把连接判死（协议 §1.3 的空闲判活由 ping/pong 负责，
            // 见下面的 pingInterval）。写超时同理——大文件分块发送时不应被单次写超时打断。
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            // 客户端每 20s 发一个 WebSocket Ping 帧（比服务端的 30s 更密），
            // 保活并能在链路故障时尽快触发 onFailure；OkHttp 自动回 Pong（协议 §1.3）。
            .pingInterval(20, TimeUnit.SECONDS)
    }

    /**
     * 建立连接。同一实例重复调用会先关闭旧连接。
     * @return true 表示已发起连接（最终结果通过 [Listener] 回调）
     */
    fun connect(uri: PairingUri): Boolean {
        closeInternal(notify = false)

        serverHost = uri.host
        serverPort = uri.port
        token = uri.token

        // 每个目标 host 一套独立的指纹固定策略（TOFU）
        val trust = PinnedTrust(store, uri.host, pinListener)

        val client = baseBuilder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.x509TrustManager())
            .hostnameVerifier(trust.hostnameVerifier())
            .build()

        val request = try {
            Request.Builder().url(uri.toUrl()).build()
        } catch (e: Exception) {
            setState(State.FAILED)
            listener.onFailure(e)
            return false
        }

        setState(State.CONNECTING)
        val ws = client.newWebSocket(request, InnerListener())
        synchronized(sendLock) { webSocket = ws }
        return true
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
            // okio 的 ByteArray.toByteString(offset, byteCount) 只拷贝需要的切片，
            // 避免为大文件再复制整个缓冲（OkHttp 4.12 / okio 3.x 上的标准写法）。
            val bs = bytes.toByteString(offset, length)
            ws.send(bs)
        } catch (e: Exception) {
            Log.w(TAG, "sendBinary failed", e)
            false
        }
    }

    fun sendMove(dx: Int, dy: Int): Boolean = sendText(Protocol.buildMove(dx, dy))

    fun sendClick(button: String): Boolean = sendText(Protocol.buildClick(button))

    fun sendScroll(amount: Int): Boolean = sendText(Protocol.buildScroll(amount))

    fun sendReset(): Boolean = sendText(Protocol.buildReset())

    fun sendFileHeader(name: String, size: Long): Boolean =
        sendText(Protocol.buildFileHeader(name, size))

    fun sendFileEnd(): Boolean = sendText(Protocol.buildFileEnd())

    fun sendPing(): Boolean = sendText(Protocol.buildPing())

    fun sendHello(deviceName: String, appVersion: String): Boolean =
        sendText(Protocol.buildHello(deviceName, appVersion))

    // ---------------- 关闭 ----------------

    /** 主动断开：先取消进行中的文件接收（删掉 .part），再发正常关闭帧 */
    fun close() {
        abortReceive("连接已关闭，取消接收")
        val ws = synchronized(sendLock) { webSocket }
        if (ws != null) {
            ws.close(1000, "client closing")
        }
        synchronized(sendLock) { webSocket = null }
        setState(State.CLOSED)
    }

    private fun closeInternal(notify: Boolean) {
        abortReceive("重新连接，取消上一次接收")
        val ws = synchronized(sendLock) { webSocket }
        if (ws != null) {
            ws.cancel()
        }
        synchronized(sendLock) { webSocket = null }
        if (notify) setState(State.CLOSED)
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

    // ---------------- OkHttp 回调 ----------------

    private inner class InnerListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            setState(State.CONNECTED)
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
            setState(State.CLOSED)
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            abortReceive("连接失败")
            setState(State.FAILED)
            listener.onFailure(translateFailure(t))
        }
    }

    /**
     * TLS/证书类异常在 OkHttp 里会被包成 SSLHandshakeException 或直接抛出
     * CertificateException，这里统一转成用户可读的说明。
     */
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
            Protocol.TYPE_WELCOME -> listener.onWelcome(
                serverVersion = o.optString("serverVersion", ""),
                serverIp = o.optString("serverIp", ""),
                port = o.optInt("port", serverPort),
                boundIp = o.optString("boundIp", ""),
                maxFileSize = optLong(o, "maxFileSize", Protocol.MAX_FILE_SIZE),
                chunkSize = o.optInt("chunkSize", Protocol.CHUNK_SIZE)
            )

            Protocol.TYPE_FILE_HEADER -> startReceive(o)

            Protocol.TYPE_FILE_END -> finishReceive(declaredEnd = true)

            Protocol.TYPE_FILE_ACK -> listener.onFileAck(
                fileName = o.optString("fileName", ""),
                status = o.optString("status", "error"),
                bytes = optLong(o, "bytes", 0L),
                savedPath = if (o.has("savedPath")) o.optString("savedPath", "") else null,
                message = if (o.has("message")) o.optString("message", "") else null
            )

            Protocol.TYPE_ERROR -> listener.onError(
                o.optString("code", "unknown"),
                o.optString("message", "")
            )

            Protocol.TYPE_PONG -> listener.onPong()

            else -> listener.onUnknownText(type, text)
        }
    }

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
            // 协议 §3.4：超限直接拒绝。这里没有反向 ack 通道，只提示并丢弃后续二进制块。
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

                override fun onCompleted(fileName: String, savedPath: String, bytes: Long) {
                    receiver = null
                    listener.onFileReceived(fileName, savedPath, bytes)
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
            // 没有生效中的 file_header：整帧丢弃并计数（协议 §3.3：多余字节丢弃，不写盘）
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
