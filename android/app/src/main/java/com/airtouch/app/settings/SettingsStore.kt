package com.airtouch.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置存储（SharedPreferences 封装）。
 *
 * 安全说明（README「已知限制」有同样记录）：
 * - 证书固定值按 **服务器身份 sid** 保存（[getServerPin]），只有拿到二维码里的 `fp` 才能通过
 *   严格校验；按 sid 保存也让「电脑换 IP / 手机换 Wi-Fi」不再导致信任失效。
 * - [lastToken] 仍然保存上一次扫码用的 token，但它已经**不再用于重连**：token 是一次性配对票据，
 *   服务端配对成功后立刻销毁并轮换。保留它只是为了在界面上显示「上次配对用的票据」便于排错。
 * - 设备私钥不在本类里：它保存在 AndroidKeyStore（见 DeviceIdentity），这里只存不敏感元数据。
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

    /** 上次扫码拿到的一次性 token（不用于重连，仅展示/排错） */
    var lastToken: String
        get() = prefs.getString(KEY_LAST_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_TOKEN, value).apply()

    /** 上次连接的服务端身份 sid（22 个字符的 base64url） */
    var lastSid: String
        get() = prefs.getString(KEY_LAST_SID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SID, value).apply()

    /** 上次连接的服务端证书固定值 fp（22 个字符的 base64url） */
    var lastPin: String
        get() = prefs.getString(KEY_LAST_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PIN, value).apply()

    /** 上次连接时电脑端报告的设备 keyId（展示用） */
    var lastKeyId: String
        get() = prefs.getString(KEY_LAST_KEY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_KEY_ID, value).apply()

    // ---------------- 触摸板 ----------------

    /**
     * 触摸板灵敏度倍数，范围 [0.5, 3.0]，默认 1.0。
     *
     * 注意不要再把默认值调到 1.0 以上：View 内部还有一层 [com.airtouch.app.input.TouchpadView.BASE_SENSITIVITY]
     * （1dp → 1.4 像素），两层相乘才是最终比例。默认 1.0 时 1dp ≈ 1.4px，与 Windows 手感接近。
     */
    var touchpadSensitivity: Float
        get() = clamp(prefs.getFloat(KEY_TOUCHPAD_SENS, 1.0f), MIN_TOUCHPAD_SENS, MAX_TOUCHPAD_SENS)
        set(value) = prefs.edit()
            .putFloat(KEY_TOUCHPAD_SENS, clamp(value, MIN_TOUCHPAD_SENS, MAX_TOUCHPAD_SENS))
            .apply()

    /**
     * 触摸板的滚轮模式：开启后触摸板单指上下滑动 = 滚轮，而不是移动光标。
     *
     * 注意这是**触摸板自己的**开关。空中鼠标页有独立的 [airMouseScrollMode]，
     * 两者互不影响 —— 早期版本共用一个开关，导致在「空中鼠标」页按下滚轮模式会把触摸板
     * 静默切成滚轮模式，回到触摸板页发现「怎么拖都不动光标」，看起来像功能坏了。
     */
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

    /** 反向（左右） */
    var airMouseInvertX: Boolean
        get() = prefs.getBoolean(KEY_AIRMOUSE_INV_X, false)
        set(value) = prefs.edit().putBoolean(KEY_AIRMOUSE_INV_X, value).apply()

    /**
     * 反向（上下）。
     *
     * v2.1 把上下方向的**默认值翻正了**：以前默认是反的，必须勾上才正常，用户反馈
     * 「一直得勾着这个开关」。为了不让升级后手感突然反过来，这里做一次性迁移：
     * 首次读取时把老值取反并写入迁移标记（老 `true` = 需要反向 → 新语义下应为 `false`）。
     */
    var airMouseInvertY: Boolean
        get() {
            if (!prefs.getBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED, false)) {
                val migrated = !prefs.getBoolean(KEY_AIRMOUSE_INV_Y, false)
                prefs.edit()
                    .putBoolean(KEY_AIRMOUSE_INV_Y, migrated)
                    .putBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED, true)
                    .apply()
                return migrated
            }
            return prefs.getBoolean(KEY_AIRMOUSE_INV_Y, false)
        }
        set(value) = prefs.edit()
            .putBoolean(KEY_AIRMOUSE_INV_Y, value)
            .putBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED, true)
            .apply()

    /**
     * 空中鼠标的滚轮模式：开启后**上下俯仰手机** = 滚轮（上下滚动），不再移动光标。
     * 与触摸板的 [scrollMode] 相互独立。
     */
    var airMouseScrollMode: Boolean
        get() = prefs.getBoolean(KEY_AIRMOUSE_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_AIRMOUSE_SCROLL, value).apply()

    // ---------------- 通信测试 ----------------

    /** 画圆测试的目标频率（Hz），默认 100 */
    var diagTargetHz: Int
        get() = prefs.getInt(KEY_DIAG_HZ, DEFAULT_DIAG_HZ)
        set(value) = prefs.edit().putInt(KEY_DIAG_HZ, value.coerceIn(30, 240)).apply()

    /** 画圆测试每圈耗时（毫秒），默认 2400 */
    var diagCircleMs: Int
        get() = prefs.getInt(KEY_DIAG_CIRCLE_MS, DEFAULT_DIAG_CIRCLE_MS)
        set(value) = prefs.edit().putInt(KEY_DIAG_CIRCLE_MS, value.coerceIn(600, 10000)).apply()

    /** 画圆测试是否使用绝对定位（moveto）；关闭则用相对位移（move） */
    var diagUseAbsolute: Boolean
        get() = prefs.getBoolean(KEY_DIAG_ABS, true)
        set(value) = prefs.edit().putBoolean(KEY_DIAG_ABS, value).apply()

    // ---------------- 证书固定（按服务器身份） ----------------

    /**
     * 读取某个信任键的 SPKI 固定值（base64url），没有则返回 null。
     * @param trustKey `sid:<sid>` 或 `host:<host>`
     */
    fun getServerPin(trustKey: String): String? {
        if (trustKey.isEmpty()) return null
        val v = prefs.getString(pinKey(trustKey), null)
        return if (v.isNullOrEmpty()) null else v
    }

    /** 读取该信任键对应的整证书 SHA-256（小写十六进制），仅用于界面核对。 */
    fun getServerCertHex(trustKey: String): String? {
        if (trustKey.isEmpty()) return null
        val v = prefs.getString(certKey(trustKey), null)
        return if (v.isNullOrEmpty()) null else v
    }

    /** 记录/刷新固定值。 */
    fun setServerPin(trustKey: String, pin: String, certHex: String?) {
        if (trustKey.isEmpty() || pin.isEmpty()) return
        val editor = prefs.edit().putString(pinKey(trustKey), pin)
        if (!certHex.isNullOrEmpty()) editor.putString(certKey(trustKey), certHex)
        editor.apply()
    }

    /** 只清除某个信任键的固定值 */
    fun resetServerPin(trustKey: String) {
        if (trustKey.isEmpty()) return
        prefs.edit().remove(pinKey(trustKey)).remove(certKey(trustKey)).apply()
    }

    /** 清除全部固定值，返回清除的条数 */
    fun resetAllServerPins(): Int {
        var count = 0
        val editor = prefs.edit()
        for ((k, _) in prefs.all) {
            if (k != null && (k.startsWith(PIN_PREFIX) || k.startsWith(CERT_PREFIX))) {
                editor.remove(k)
                if (k.startsWith(PIN_PREFIX)) count++
            }
        }
        editor.apply()
        return count
    }

    /** 当前保存的固定值条数，用于「重置证书信任」按钮的空状态提示 */
    fun serverPinCount(): Int {
        var count = 0
        for ((k, _) in prefs.all) {
            if (k != null && k.startsWith(PIN_PREFIX)) count++
        }
        return count
    }

    private fun pinKey(trustKey: String): String = PIN_PREFIX + trustKey.lowercase()

    private fun certKey(trustKey: String): String = CERT_PREFIX + trustKey.lowercase()

    private fun clamp(v: Float, lo: Float, hi: Float): Float {
        if (v.isNaN()) return lo
        return if (v < lo) lo else if (v > hi) hi else v
    }

    companion object {
        const val PREFS_NAME = "airtouch_settings"

        const val DEFAULT_PORT = 8443

        const val DEFAULT_DIAG_HZ = 100
        const val DEFAULT_DIAG_CIRCLE_MS = 2400

        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val KEY_LAST_SID = "last_sid"
        private const val KEY_LAST_PIN = "last_pin"
        private const val KEY_LAST_KEY_ID = "last_key_id"
        private const val KEY_TOUCHPAD_SENS = "touchpad_sensitivity"
        private const val KEY_SCROLL_MODE = "scroll_mode"
        private const val KEY_AIRMOUSE_SENS = "airmouse_sensitivity"
        private const val KEY_AIRMOUSE_INV_X = "airmouse_invert_x"
        private const val KEY_AIRMOUSE_INV_Y = "airmouse_invert_y"

        /** 上下方向默认值翻正的一次性迁移标记（见 [airMouseInvertY]） */
        private const val KEY_AIRMOUSE_INV_Y_MIGRATED = "airmouse_invert_y_migrated_v21"
        private const val KEY_AIRMOUSE_SCROLL = "airmouse_scroll_mode"
        private const val KEY_DIAG_HZ = "diag_target_hz"
        private const val KEY_DIAG_CIRCLE_MS = "diag_circle_ms"
        private const val KEY_DIAG_ABS = "diag_use_absolute"
        private const val PIN_PREFIX = "cert_pin_"
        private const val CERT_PREFIX = "cert_hex_"

        const val MIN_TOUCHPAD_SENS = 0.5f
        const val MAX_TOUCHPAD_SENS = 3.0f
        const val MIN_AIRMOUSE_SENS = 0.1f
        const val MAX_AIRMOUSE_SENS = 10.0f
    }
}
