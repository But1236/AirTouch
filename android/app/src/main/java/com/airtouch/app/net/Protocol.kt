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
    const val TYPE_MOVE_TO = "moveto"
    const val TYPE_CLICK = "click"
    const val TYPE_SCROLL = "scroll"
    const val TYPE_RESET = "reset"
    const val TYPE_FILE_HEADER = "file_header"
    const val TYPE_FILE_END = "file_end"
    const val TYPE_HELLO = "hello"
    const val TYPE_PING = "ping"

    /** 协议 §1.2：认证三步握手 */
    const val TYPE_AUTH_REQ = "auth_req"
    const val TYPE_DIAG_BEGIN = "diag_begin"
    const val TYPE_DIAG_REPORT = "diag_report"

    // ---- 服务端 → 客户端 的 type 常量 ----
    const val TYPE_AUTH_CHALLENGE = "auth_challenge"
    const val TYPE_WELCOME = "welcome"
    const val TYPE_FILE_ACK = "file_ack"
    const val TYPE_ERROR = "error"
    const val TYPE_PONG = "pong"
    const val TYPE_FILE_HEADER_FROM_SERVER = "file_header"
    const val TYPE_FILE_END_FROM_SERVER = "file_end"

    // ---- 服务端 → 客户端 file_header 里可能出现的额外字段 ----
    const val FIELD_TYPE = "type"
    const val FIELD_DX = "dx"
    const val FIELD_DY = "dy"
    const val FIELD_X = "x"
    const val FIELD_Y = "y"
    const val FIELD_SEQ = "seq"
    const val FIELD_BUTTON = "button"
    const val FIELD_AMOUNT = "amount"
    const val FIELD_FILE_NAME = "fileName"
    const val FIELD_FILE_SIZE = "fileSize"
    const val FIELD_FILE_ID = "fileId"
    const val FIELD_DEVICE_NAME = "deviceName"
    const val FIELD_APP_VERSION = "appVersion"

    // ---- 认证与诊断字段 ----
    const val FIELD_AUTH_VERSION = "auth_v"
    const val FIELD_KEY_ID = "keyId"
    const val FIELD_SIGN = "sign"
    const val FIELD_TS = "ts"
    const val FIELD_TOKEN = "token"
    const val FIELD_PUBLIC_KEY = "pubKey"
    const val FIELD_TEST_KIND = "testKind"
    const val FIELD_MODE = "mode"
    const val FIELD_PLANNED = "planned"
    const val FIELD_TARGET_HZ = "targetHz"
    const val FIELD_CIRCLE_MS = "circleMs"
    const val FIELD_SENT = "sent"
    const val FIELD_FIRST_SEQ = "firstSeq"
    const val FIELD_LAST_SEQ = "lastSeq"
    const val FIELD_ELAPSED_MS = "elapsedMs"
    const val FIELD_MAX_SEND_GAP_MS = "maxSendGapMs"

    const val BUTTON_LEFT = "left"
    const val BUTTON_RIGHT = "right"

    /** 协议 §2.1：dx/dy 被服务端截断到 [-4096, 4096]，客户端主动先截断 */
    const val MOVE_LIMIT = 4096

    /** 协议 §2.1：moveto 的硬上限，超出直接截断（PC 端还会再夹到主屏范围） */
    const val ABSOLUTE_LIMIT = 32767

    /** 协议 §2.1：amount 截断到 [-120, 120] */
    const val SCROLL_LIMIT = 120

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

    fun buildMove(dx: Number?, dy: Number?, seq: Int? = null): String {
        val x = clamp(safeInt(dx), -MOVE_LIMIT, MOVE_LIMIT)
        val y = clamp(safeInt(dy), -MOVE_LIMIT, MOVE_LIMIT)
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_MOVE)
        o.put(FIELD_DX, x)
        o.put(FIELD_DY, y)
        if (seq != null) o.put(FIELD_SEQ, seq)
        return o.toString()
    }

    /**
     * 绝对定位（协议 §2.1 的 `moveto`）。
     *
     * 与 `move` 的区别是 PC 端用 SetCursorPos 直接落到坐标上：**不经过 Windows 的指针加速曲线**，
     * 所以「画圆测试」能得到和手机算出来的完全一致的轨迹；相对位移在累加之后会被加速曲线拉偏。
     */
    fun buildMoveTo(x: Number?, y: Number?, seq: Int? = null): String {
        val px = clamp(safeInt(x), -ABSOLUTE_LIMIT, ABSOLUTE_LIMIT)
        val py = clamp(safeInt(y), -ABSOLUTE_LIMIT, ABSOLUTE_LIMIT)
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_MOVE_TO)
        o.put(FIELD_X, px)
        o.put(FIELD_Y, py)
        if (seq != null) o.put(FIELD_SEQ, seq)
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

    /** 通信测试开始（协议 §2.4）。 */
    fun buildDiagBegin(testKind: String, mode: String, planned: Int, targetHz: Int, circleMs: Int): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_DIAG_BEGIN)
        o.put(FIELD_TEST_KIND, testKind)
        o.put(FIELD_MODE, mode)
        o.put(FIELD_PLANNED, planned)
        o.put(FIELD_TARGET_HZ, targetHz)
        o.put(FIELD_CIRCLE_MS, circleMs)
        return o.toString()
    }

    /** 通信测试结束，把手机侧的自测数据交回 PC（协议 §2.4）。 */
    fun buildDiagReport(
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
    ): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_DIAG_REPORT)
        o.put(FIELD_TEST_KIND, testKind)
        o.put(FIELD_MODE, mode)
        o.put(FIELD_SENT, sent)
        o.put(FIELD_PLANNED, planned)
        o.put(FIELD_FIRST_SEQ, firstSeq)
        o.put(FIELD_LAST_SEQ, lastSeq)
        o.put(FIELD_ELAPSED_MS, elapsedMs)
        o.put(FIELD_TARGET_HZ, targetHz)
        o.put(FIELD_CIRCLE_MS, circleMs)
        o.put(FIELD_MAX_SEND_GAP_MS, maxSendGapMs)
        return o.toString()
    }

    fun buildClick(button: String?): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_CLICK)
        o.put(FIELD_BUTTON, normalizeButton(button))
        return o.toString()
    }

    fun buildScroll(amount: Number?): String {
        val a = clamp(safeInt(amount), -SCROLL_LIMIT, SCROLL_LIMIT)
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_SCROLL)
        o.put(FIELD_AMOUNT, a)
        return o.toString()
    }

    fun buildReset(): String {
        val o = JSONObject()
        o.put(FIELD_TYPE, TYPE_RESET)
        return o.toString()
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
