package com.airtouch.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置存储（SharedPreferences 封装）。
 *
 * 安全权衡说明（README 中同样有记录）：
 * - [lastToken] 会持久化保存上一次配对成功的 token。这样「重连」时无需重新扫码，
 *   代价是明文 token 会留在 App 私有 SharedPreferences 中（未加密）。
 *   这是刻意的取舍：服务端的 token 是「一次性 + IP 绑定 + 3 次失败即封禁」，
 *   且仅在局域网内有效；App 私有目录在未 root 设备上其他应用无法读取。
 * - 证书指纹按 host 单独保存，用于 TOFU 校验（见 PinnedTrust）。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- 最近一次连接 ----------------

    /** 上次成功连接的主机（IP 或域名），没有则为空串 */
    var lastHost: String
        get() = prefs.getString(KEY_LAST_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_HOST, value).apply()

    /** 上次使用的端口，默认 8443（协议 §1.1） */
    var lastPort: Int
        get() = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_LAST_PORT, value).apply()

    /** 上次连接使用的 token（见类注释中的取舍说明），没有则为空串 */
    var lastToken: String
        get() = prefs.getString(KEY_LAST_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_TOKEN, value).apply()

    // ---------------- 触摸板 ----------------

    /** 触摸板灵敏度倍数，范围 [0.5, 3.0]，默认 1.0 */
    var touchpadSensitivity: Float
        get() = clamp(prefs.getFloat(KEY_TOUCHPAD_SENS, 1.0f), MIN_TOUCHPAD_SENS, MAX_TOUCHPAD_SENS)
        set(value) = prefs.edit()
            .putFloat(KEY_TOUCHPAD_SENS, clamp(value, MIN_TOUCHPAD_SENS, MAX_TOUCHPAD_SENS))
            .apply()

    /** 滚轮模式：开启后触摸板单指上下滑动 = 滚轮，而不是移动光标 */
    var scrollMode: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_MODE, value).apply()

    // ---------------- 空中鼠标 ----------------

    /** 空中鼠标灵敏度倍数，范围 [0.1, 10.0]，默认 1.0 */
    var airMouseSensitivity: Float
        get() = clamp(prefs.getFloat(KEY_AIRMOUSE_SENS, 1.0f), MIN_AIRMOUSE_SENS, MAX_AIRMOUSE_SENS)
        set(value) = prefs.edit()
            .putFloat(KEY_AIRMOUSE_SENS, clamp(value, MIN_AIRMOUSE_SENS, MAX_AIRMOUSE_SENS))
            .apply()

    var airMouseInvertX: Boolean
        get() = prefs.getBoolean(KEY_AIRMOUSE_INV_X, false)
        set(value) = prefs.edit().putBoolean(KEY_AIRMOUSE_INV_X, value).apply()

    var airMouseInvertY: Boolean
        get() = prefs.getBoolean(KEY_AIRMOUSE_INV_Y, false)
        set(value) = prefs.edit().putBoolean(KEY_AIRMOUSE_INV_Y, value).apply()

    // ---------------- 证书指纹（TOFU） ----------------

    /** 读取指定 host 已固定的证书 SHA-256 指纹（十六进制小写），没有则返回 null */
    fun getCertificatePin(host: String): String? {
        if (host.isEmpty()) return null
        val v = prefs.getString(pinKey(host), null)
        return if (v.isNullOrEmpty()) null else v
    }

    /** 首次连接时记录指纹 */
    fun setCertificatePin(host: String, fingerprintHex: String) {
        if (host.isEmpty()) return
        prefs.edit().putString(pinKey(host), fingerprintHex).apply()
    }

    /** 只清除某个 host 的指纹 */
    fun resetCertificatePin(host: String) {
        if (host.isEmpty()) return
        prefs.edit().remove(pinKey(host)).apply()
    }

    /** 清除全部已保存的证书指纹，返回清除的条数 */
    fun resetAllCertificatePins(): Int {
        var count = 0
        val editor = prefs.edit()
        for ((k, _) in prefs.all) {
            if (k != null && k.startsWith(PIN_PREFIX)) {
                editor.remove(k)
                count++
            }
        }
        editor.apply()
        return count
    }

    /** 当前保存的指纹条数，用于「重置证书信任」按钮的空状态提示 */
    fun certificatePinCount(): Int {
        var count = 0
        for ((k, _) in prefs.all) {
            if (k != null && k.startsWith(PIN_PREFIX)) count++
        }
        return count
    }

    private fun pinKey(host: String): String = PIN_PREFIX + host.lowercase()

    private fun clamp(v: Float, lo: Float, hi: Float): Float {
        if (v.isNaN()) return lo
        return if (v < lo) lo else if (v > hi) hi else v
    }

    companion object {
        const val PREFS_NAME = "airtouch_settings"

        const val DEFAULT_PORT = 8443

        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val KEY_TOUCHPAD_SENS = "touchpad_sensitivity"
        private const val KEY_SCROLL_MODE = "scroll_mode"
        private const val KEY_AIRMOUSE_SENS = "airmouse_sensitivity"
        private const val KEY_AIRMOUSE_INV_X = "airmouse_invert_x"
        private const val KEY_AIRMOUSE_INV_Y = "airmouse_invert_y"
        private const val PIN_PREFIX = "cert_pin_"

        const val MIN_TOUCHPAD_SENS = 0.5f
        const val MAX_TOUCHPAD_SENS = 3.0f
        const val MIN_AIRMOUSE_SENS = 0.1f
        const val MAX_AIRMOUSE_SENS = 10.0f
    }
}
