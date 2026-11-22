package com.airtouch.app.net

import org.json.JSONObject

/**
 * 协议编解码工具（唯一事实来源：docs/PROTOCOL.md v1）。
 *
 * 约定：
 * - 全部使用 org.json.JSONObject 构造，绝不手工拼接 JSON 字符串（文件名里的引号/反斜杠
 *   由 JSONObject 负责转义）。
 * - 所有数值在序列化前都会做类型强制与非数字兜底（协议 §2.3：解析失败按 0 处理），
 *   并按协议规定的范围截断。
 * - 接收侧一律用 opt* 读取，未知字段自动忽略，未知 type 由调用方忽略。
 */
object Protocol {

    // ---- 客户端 → 服务端 的 type 常量 ----
    const val TYPE_MOVE = "move"
    const val TYPE_CLICK = "click"
    const val TYPE_BUTTON = "button"
    const val TYPE_SCROLL = "scroll"
    const val TYPE_TOUCH = "touch"
    const val TYPE_RESET = "reset"
    const val TYPE_KEY = "key"
    const val TYPE_TEXT = "text"
    const val TYPE_FILE_HEADER = "file_header"
    const val TYPE_FILE_END = "file_end"
    const val TYPE_HELLO = "hello"
    const val TYPE_PING = "ping"

    /**
     * 设置电脑端的缩放偏好（协议 §2.1）：`native` = 优先原生触控注入，`wheel` = 强制滚轮回退，
     * `auto` = 由电脑端自己挑（缺省）。只影响电脑端用哪个 API，不影响线上的 `touch` 几何流。
     */
    const val TYPE_ZOOM_MODE = "zoom_mode"

    /**
     * 请电脑端**立刻重新探测**触控能力（协议 §2.1）：用户点「重新检测」时发（例如刚插上 USB 触摸屏）。
     * 探测到 `SM_MAXIMUMTOUCHES > 0` 后电脑端会热切换到 `InjectTouchInput`，不用重启也不用重连。
     */
    const val TYPE_ZOOM_PROBE = "zoom_probe"

    /** 协议 §1.2：认证三步握手 */
    const val TYPE_AUTH_REQ = "auth_req"

    // ---- 服务端 → 客户端 的 type 常量 ----
    const val TYPE_AUTH_CHALLENGE = "auth_challenge"
    const val TYPE_WELCOME = "welcome"
    const val TYPE_FILE_ACK = "file_ack"
    const val TYPE_ERROR = "error"
    const val TYPE_PONG = "pong"

    /**
     * 电脑端**当前实际生效**的缩放后端（协议 §2.2）。设置页的状态指示器就靠它：
     * `native_touch` = 已激活原生触控栈，`ctrl_wheel_fallback` = 滚轮兼容模式。
     */
    const val TYPE_ZOOM_STATUS = "zoom_status"
    const val TYPE_FILE_HEADER_FROM_SERVER = "file_header"
    const val TYPE_FILE_END_FROM_SERVER = "file_end"

    // ---- 服务端 → 客户端 file_header 里可能出现的额外字段 ----
    const val FIELD_TYPE = "type"
    const val FIELD_DX = "dx"
    const val FIELD_DY = "dy"
    const val FIELD_BUTTON = "button"
    const val FIELD_AMOUNT = "amount"
    const val FIELD_DELTA = "delta"
    const val FIELD_AXIS = "axis"
    const val FIELD_PHASE = "phase"
    const val FIELD_SCALE = "scale"
    const val FIELD_VK = "vk"
    const val FIELD_DOWN = "down"
    const val FIELD_EXT = "ext"
    const val FIELD_TEXT = "s"
    const val FIELD_FILE_NAME = "fileName"
    const val FIELD_FILE_SIZE = "fileSize"
    const val FIELD_FILE_ID = "fileId"
    const val FIELD_DEVICE_NAME = "deviceName"
    const val FIELD_APP_VERSION = "appVersion"

    // ---- 缩放后端状态（zoom_status 帧）与 welcome 里的初始快照 ----
    const val FIELD_MODE = "mode"
    const val FIELD_AVAILABLE = "available"
    const val FIELD_REASON = "reason"
    const val FIELD_PREFERENCE = "preference"
    const val FIELD_MAX_TOUCHES = "maxTouches"
    const val FIELD_DIGITIZER = "digitizer"
    const val FIELD_ZOOM_MODE = "zoomMode"
    const val FIELD_ZOOM_AVAILABLE = "zoomAvailable"
    const val FIELD_ZOOM_REASON = "zoomReason"
    const val FIELD_ZOOM_PREFERENCE = "zoomPreference"

    /** 电脑端当前生效的后端（zoom_status 的 mode） */
    const val ZOOM_BACKEND_NATIVE = "native_touch"
    const val ZOOM_BACKEND_WHEEL = "ctrl_wheel_fallback"

    /** 缩放偏好（zoom_mode 的 mode / zoom_status 的 preference） */
    const val ZOOM_PREF_AUTO = "auto"
    const val ZOOM_PREF_NATIVE = "native"
    const val ZOOM_PREF_WHEEL = "wheel"

    // ---- 认证字段 ----
    const val FIELD_AUTH_VERSION = "auth_v"
    const val FIELD_KEY_ID = "keyId"
    const val FIELD_SIGN = "sign"
    const val FIELD_TS = "ts"
    const val FIELD_TOKEN = "token"
    const val FIELD_PUBLIC_KEY = "pubKey"

    const val BUTTON_LEFT = "left"
    const val BUTTON_RIGHT = "right"

    /** 滚轮轴：`axis` 缺省为纵向 */
    const val AXIS_VERTICAL = "v"
    const val AXIS_HORIZONTAL = "h"

    /** touch 帧的三个阶段 */
    const val PHASE_DOWN = "down"
    const val PHASE_MOVE = "move"
    const val PHASE_UP = "up"

    /** 一格滚轮 = 120 个原生滚轮单位（Windows 的 WHEEL_DELTA）；delta 可以远小于它 */
    const val SCROLL_UNITS_PER_NOTCH = 120

    /** 协议 §2.1：dx/dy 被服务端截断到 [-4096, 4096]，客户端主动先截断 */
    const val MOVE_LIMIT = 4096

    /** 协议 §2.1：amount 截断到 [-120, 120] */
    const val SCROLL_LIMIT = 120

    /** 协议 §2.1：delta（原生滚轮单位）截断到 [-1200, 1200]，也就是一次最多十格 */
    const val SCROLL_DELTA_LIMIT = 1200

    /** 协议 §2.1：touch 帧的 scale（间距倍数）截断到 [0.05, 20] */
    const val SCALE_MIN = 0.05
    const val SCALE_MAX = 20.0

    /** 协议 §2.6：vk 是 Win32 虚拟键码，0 表示「没有按键」，超过 255 被截断 */
    const val MAX_VIRTUAL_KEY = 255

    /** 协议 §2.7：一个 `text` 帧最多 256 个 UTF-16 码元，超出截断 */
    const val MAX_TEXT_LENGTH = 256

    /**
     * 长文本分片长度（协议 §2.7）：中文一句话就几十个字，一次发一整段会让服务端的单次注入
     * 调用处理太多字符；分片后每帧都很小，也更容易在丢包时定位。
     */
    const val TEXT_CHUNK_LENGTH = 120

    /** 协议 §3.2：分块大小 64 KiB */
    const val CHUNK_SIZE = 65536

    /** 协议 §3.4/§3.5：单次文件大小上限 512 MiB */
    const val MAX_FILE_SIZE = 512L * 1024L * 1024L

    /** 兜底：非数字输入统一按 0 处理（协议 §2.3） */
    private fun safeInt(v: Number?): Int {
        if (v == null) return 0
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is Float -> v.toInt()
            else -> v.toString().toDoubleOrNull()?.toInt() ?: 0
        }
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = when {
        v < lo -> lo
        v > hi -> hi
        else -> v
    }

    /**
     * 兜底：非数字按 0 处理；文件大小必须用 64 位读取，否则 >2 GiB 的值会被 [safeInt] 截断成
     * 负数或完全错误的数字（协议 §3.2 的 fileSize 是整数，未规定上限）。
     */
    private fun safeLong(v: Number?): Long {
        if (v == null) return 0L
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is Float -> v.toLong()
            else -> v.toString().toDoubleOrNull()?.toLong() ?: 0L
        }
    }

    /** 归一化按钮名：协议规定 button 缺省视为 left，非 left/right 一律按 left */
    fun normalizeButton(button: String?): String =
        if (button != null && button.equals(BUTTON_RIGHT, ignoreCase = true)) BUTTON_RIGHT else BUTTON_LEFT

    fun buildMove(dx: Number?, dy: Number?): String {
        val x = clamp(safeInt(dx), -MOVE_LIMIT, MOVE_LIMIT)
        val y = clamp(safeInt(dy), -MOVE_LIMIT, MOVE_LIMIT)
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_MOVE)
        o.put(FIELD_DX, x)
        o.put(FIELD_DY, y)
        return o.toString()
    }

    /**
     * 认证应答（协议 §1.2 第 2 步）。
     *
     * - 首次配对：带 `token` + `pubKey`，服务端据此把公钥写进信任列表；
     * - 免扫码重连：**不带** token，也不带 pubKey（服务端用 keyId 从信任列表里取公钥），
     *   这样重复发送大公钥的开销也省掉了。
     */
    fun buildAuthRequest(
        authVersion: Int,
        keyId: String,
        token: String?,
        publicKey: String?,
        signature: String,
        timestamp: Long,
        deviceName: String?,
        appVersion: String?
    ): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_AUTH_REQ)
        o.put(FIELD_AUTH_VERSION, authVersion)
        o.put(FIELD_KEY_ID, keyId)
        o.put(FIELD_SIGN, signature)
        o.put(FIELD_TS, timestamp)
        if (!token.isNullOrEmpty()) o.put(FIELD_TOKEN, token)
        if (!publicKey.isNullOrEmpty()) o.put(FIELD_PUBLIC_KEY, publicKey)
        if (!deviceName.isNullOrEmpty()) o.put(FIELD_DEVICE_NAME, deviceName)
        if (!appVersion.isNullOrEmpty()) o.put(FIELD_APP_VERSION, appVersion)
        return o.toString()
    }

    fun buildClick(button: String?): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_CLICK)
        o.put(FIELD_BUTTON, normalizeButton(button))
        return o.toString()
    }

    /**
     * 鼠标按键的一次**状态变化**（协议 §2.1 的 `button`）。
     *
     * 「按下」和「抬起」必须能分两次发出，否则做不了拖拽：拖一个文件要先把左键按住，中间发一串
     * `move`，最后才松开。`click` 办不到这件事——它把按下与抬起放在同一次注入里，中间插不进任何移动。
     *
     * **抬起是调用方的责任**：电脑侧只在连接断开或收到 `reset` 时代为释放所有按键。手机上任何
     * 「拖到一半被打断」的路径（手指抬起、切页、onPause、断线）都必须补一次 `down = false`。
     *
     * @param button "left" / "right"（其它值一律按 left 处理，与 `click` 一致）
     * @param down true = 按下，false = 抬起
     */
    fun buildButton(button: String?, down: Boolean): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_BUTTON)
        o.put(FIELD_BUTTON, normalizeButton(button))
        o.put(FIELD_DOWN, down)
        return o.toString()
    }

    fun buildScroll(amount: Number?): String {
        val a = clamp(safeInt(amount), -SCROLL_LIMIT, SCROLL_LIMIT)
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_SCROLL)
        o.put(FIELD_AMOUNT, a)
        return o.toString()
    }

    /**
     * **高精度滚轮**（协议 §2.1 的 `delta` / `axis`）：delta 是原生滚轮单位（120 = 一格）。
     *
     * 为什么不用 [buildScroll] 的「整格」形式：一格 120 单位在 Windows 上是「一格就是一大跳」，
     * 触控板要的是像素级平滑滚动，所以发 15 这种小增量，由应用自己累加。两指横滑走
     * `MOUSEEVENTF_HWHEEL`（`axis:"h"`），纵向留空。
     *
     * @param delta 原生滚轮单位，正数上滚 / 右滚，负数下滚 / 左滚
     * @param horizontal true = 横向滚动
     */
    fun buildScrollDelta(delta: Int, horizontal: Boolean = false): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_SCROLL)
        o.put(FIELD_DELTA, clamp(delta, -SCROLL_DELTA_LIMIT, SCROLL_DELTA_LIMIT))
        if (horizontal) o.put(FIELD_AXIS, AXIS_HORIZONTAL)
        return o.toString()
    }

    /**
     * **双指捏合/张开的几何流**（协议 §2.1 的 `touch`）。
     *
     * 客户端只报「两指间距是手势开始时的几倍」和「手势中心移动了多少」，**具体用哪个 Windows API
     * 去实现缩放由服务端决定**：有触摸输入栈的机器上走 `InjectTouchInput` 的真双指触摸（应用得到的是
     * 和真实触摸屏一样的无级缩放），没有触摸栈的机器上服务端自动回退到 Ctrl + 高精度滚轮。
     * 这样分工的好处是：手机端不需要知道对面有没有触摸屏，协议也不用为两种实现各来一套。
     *
     * @param phase [PHASE_DOWN] / [PHASE_MOVE] / [PHASE_UP]
     * @param scale 当前两指间距 ÷ 手势开始时间距（相对值，跨设备成立）；非 move 阶段无意义
     * @param dx 手势中心相对上一帧的横向移动（屏幕像素）
     * @param dy 手势中心相对上一帧的纵向移动（屏幕像素）
     */
    fun buildTouch(phase: String, scale: Double, dx: Int, dy: Int): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_TOUCH)
        o.put(FIELD_PHASE, phase)
        if (phase == PHASE_MOVE) {
            o.put(FIELD_SCALE, scale.coerceIn(SCALE_MIN, SCALE_MAX))
            o.put(FIELD_DX, clamp(dx, -MOVE_LIMIT, MOVE_LIMIT))
            o.put(FIELD_DY, clamp(dy, -MOVE_LIMIT, MOVE_LIMIT))
        }
        return o.toString()
    }

    /**
     * 设置电脑端的缩放偏好（协议 §2.1 的 `zoom_mode`）。
     *
     * 只传 [ZOOM_PREF_NATIVE] / [ZOOM_PREF_WHEEL] / [ZOOM_PREF_AUTO] 三个值；其它值原样发出去
     * 也会被电脑端整帧忽略，但客户端不该制造这种东西，所以这里做一次白名单兜底。
     */
    fun buildZoomMode(mode: String): String {
        val m = when (mode) {
            ZOOM_PREF_NATIVE -> ZOOM_PREF_NATIVE
            ZOOM_PREF_WHEEL -> ZOOM_PREF_WHEEL
            else -> ZOOM_PREF_AUTO
        }
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_ZOOM_MODE)
        o.put(FIELD_MODE, m)
        return o.toString()
    }

    /** 请求电脑端立刻重新探测触控能力（协议 §2.1 的 `zoom_probe`），无字段 */
    fun buildZoomProbe(): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_ZOOM_PROBE)
        return o.toString()
    }

    fun buildReset(): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_RESET)
        return o.toString()
    }

    /**
     * 键盘按键的一次**状态变化**（协议 §2.6）。
     *
     * 一次 `Ctrl+C` 就是四次调用（Ctrl 按下 / C 按下 / C 抬起 / Ctrl 抬起），和真实键盘产生的事件
     * 序列一致。之所以不定义「组合键」消息：长按与粘滞锁定都要求把「按下」和「抬起」放在两个不同
     * 的时刻发出（例如「C 抬起之后 Ctrl 还按着」），组合消息表达不了这种状态。
     *
     * @param vk Win32 虚拟键码，截断到 `[0, 255]`；`0` 会被服务端忽略
     * @param down true = KEY_DOWN，false = KEY_UP
     * @param extended 显式指定 `KEYEVENTF_EXTENDEDKEY`。**默认 null，由服务端按 vk 的标准扩展键
     *   集合推断**（方向键、Delete、PrintScreen、右 Ctrl、Win、小键盘 `/` 等）；只有虚拟键码无法
     *   区分的键位才需要显式给出——小键盘回车与主回车共用 `VK_RETURN(0x0D)`。
     */
    fun buildKey(vk: Int, down: Boolean, extended: Boolean? = null): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_KEY)
        o.put(FIELD_VK, clamp(vk, 0, MAX_VIRTUAL_KEY))
        o.put(FIELD_DOWN, down)
        if (extended != null) o.put(FIELD_EXT, extended)
        return o.toString()
    }

    /**
     * 一段 Unicode 文本（协议 §2.7）。
     *
     * 键盘上打出来的中文、九键选中的候选字、以及「纯文本输入框」里的一整段话，都走这一帧。
     * 它和 `key` 帧的分工是明确的：**能用虚拟键码表达的用 `key`，不能的用 `text`**。
     * 中文没法用虚拟键码表达（那不是某个键位），所以只能按 Unicode 码元直接注入。
     *
     * 与 `key` 不同，这一帧不携带按键状态，因此不参与协议 §2.6 的卡键保护。
     *
     * @return 编码后的帧；[text] 为空时返回 null（调用方跳过，不要发一个空帧）
     */
    fun buildText(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val safe = truncateText(text, MAX_TEXT_LENGTH)
        if (safe.isEmpty()) return null
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_TEXT)
        o.put(FIELD_TEXT, safe)
        return o.toString()
    }

    /**
     * 按 UTF-16 码元截断到 [limit]，且**绝不切开一个代理对**。
     *
     * 切在代理对中间会留下一个孤立的高代理（半个 emoji），服务端注入的就是一个乱码字符；
     * 少一个字比多一个乱码好。
     */
    fun truncateText(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        var end = limit
        if (Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end)
    }

    fun buildFileHeader(name: String?, size: Number?): String {
        val safeName = if (name.isNullOrEmpty()) "unnamed.bin" else name
        // fileSize 用 Long 传输，避免 >2GiB 时的截断（协议规定为整数）
        val safeSize = safeLong(size).coerceAtLeast(0L)
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_FILE_HEADER)
        o.put(FIELD_FILE_NAME, safeName)
        o.put(FIELD_FILE_SIZE, safeSize)
        return o.toString()
    }

    fun buildFileEnd(fileId: String? = null): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_FILE_END)
        if (!fileId.isNullOrEmpty()) o.put(FIELD_FILE_ID, fileId)
        return o.toString()
    }

    fun buildHello(deviceName: String?, appVersion: String?): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_HELLO)
        if (!deviceName.isNullOrEmpty()) o.put(FIELD_DEVICE_NAME, deviceName)
        if (!appVersion.isNullOrEmpty()) o.put(FIELD_APP_VERSION, appVersion)
        return o.toString()
    }

    fun buildPing(): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_PING)
        return o.toString()
    }

    /**
     * 把原始文本帧解析成 JSONObject；解析失败（非 JSON 或不是对象）返回 null，调用方忽略。
     * 这里不抛异常，避免坏帧打断读线程。
     */
    fun parseObject(text: String): JSONObject? {
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }
}
