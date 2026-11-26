package com.airtouch.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.airtouch.app.net.Protocol

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
     * 注意不要再把默认值调到 1.0 以上：Compose 版 `RedesignController.sendMove` 会把指针位移
     * 再乘一次这个倍数，默认 1.0 时与 Windows 手感接近。
     */
    var touchpadSensitivity: Float
        get() = clamp(prefs.getFloat(KEY_TOUCHPAD_SENS, 1.0f), MIN_TOUCHPAD_SENS, MAX_TOUCHPAD_SENS)
        set(value) = prefs.edit()
            .putFloat(KEY_TOUCHPAD_SENS, clamp(value, MIN_TOUCHPAD_SENS, MAX_TOUCHPAD_SENS))
            .apply()

    /**
     * **触摸零延时**（默认关闭）。
     *
     * - 关闭（默认）：手势状态机走「观察窗」模式 —— 第一下轻点先扣住 click 160ms 再补发（单击略有延迟），
     *   换来的是双击拖拽**零起点偏移**（第一下的 click 被吞掉，不存在「点击在前、按下在后」，Windows
     *   不会把那次按下当成双击第二下）。
     * - 开启：单击零延迟（抬手立刻点击），但双击拖拽时为了走出 Windows 的 4×4px 双击判定框，
     *   需要先把光标推约 3px 再按下左键 —— 拖拽起点会有这约 3px 的偏移。
     */
    var touchZeroLatency: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_ZERO_LATENCY, false)
        set(value) = prefs.edit().putBoolean(KEY_TOUCH_ZERO_LATENCY, value).apply()

    /**
     * 触摸板的滚轮模式：开启后触摸板单指上下滑动 = 滚轮，而不是移动光标。
     *
     * 注意这是**触摸板自己的**开关。鼠标页有独立的 [airMouseScrollMode]，
     * 两者互不影响 —— 早期版本共用一个开关，导致在「鼠标」页按下滚轮模式会把触摸板
     * 静默切成滚轮模式，回到触摸板页发现「怎么拖都不动光标」，看起来像功能坏了。
     */
    var scrollMode: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_MODE, value).apply()

    /**
     * 双指缩放的后端偏好（协议 §2.1 的 `zoom_mode`）：`native` = 优先原生触控注入，
     * `wheel` = 强制 Ctrl+滚轮回退，`auto` = 由电脑端自己挑。
     *
     * **null 表示用户从没动过这个开关**，此时连接后不发 `zoom_mode`，电脑端保持自己的 `auto`
     * 判断（有触摸栈就用原生）。一旦用户拨过开关，偏好就固定下来并在每次连接后重发 ——
     * 否则换台电脑或服务端重启后，用户「明明关过」的开关又会自己打开。
     *
     * 这是**电脑端**的设置，不是本机设置：两台手机连同一台电脑时以最后写入者为准。
     */
    var zoomPreference: String?
        get() = prefs.getString(KEY_ZOOM_PREFERENCE, null)?.let {
            when (it) {
                Protocol.ZOOM_PREF_NATIVE -> Protocol.ZOOM_PREF_NATIVE
                Protocol.ZOOM_PREF_WHEEL -> Protocol.ZOOM_PREF_WHEEL
                else -> null
            }
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_ZOOM_PREFERENCE) else putString(KEY_ZOOM_PREFERENCE, value)
        }.apply()

    // ---------------- 鼠标页 ----------------
    //
    // 内部命名沿用 airMouse*（历史原因）：SharedPreferences 的 key 一旦改名就等于丢掉用户设置，
    // 所以只改界面上显示的名字，不动 key 与属性名。

    /** 鼠标灵敏度倍数，范围 [0.1, 10.0]，默认 1.0 */
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
     * 「一直得勾着这个开关」。为了不让升级后手感突然反过来，v2.1 做了一次性迁移：
     * 把老值取反并写入迁移标记（老 `true` = 需要反向 → 新语义下应为 `false`）。
     *
     * **v2.1 的迁移有 bug（v2.2 修）**：它没有先判断「这个键到底有没有被存过」。
     * 全新安装 / 清过数据的机器上 `getBoolean(KEY, false)` 返回 `false`，
     * 取反后变成 `true` —— 也就是默认就替用户勾上了「上下反向」，方向自然一直是反的。
     * 现在 [migrateInvertY] 分两步：只在**确实存在**老值时按老语义取反，
     * 再把 v2.1 那次错误迁移的结果一次性纠正回 `false`。
     * 用户在设置页里手动拨过开关之后（setter 会写 V22 标记）不再被任何迁移覆盖。
     */
    var airMouseInvertY: Boolean
        get() {
            migrateInvertY()
            return prefs.getBoolean(KEY_AIRMOUSE_INV_Y, false)
        }
        set(value) = prefs.edit()
            .putBoolean(KEY_AIRMOUSE_INV_Y, value)
            .putBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED, true)
            .putBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED_V22, true)
            .apply()

    /** 见 [airMouseInvertY] 的注释；只在首次读取时各跑一次。 */
    private fun migrateInvertY() {
        // 第一步：v2.1 的老语义迁移 —— 只有老值真的存在过才取反。
        if (!prefs.getBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED, false)) {
            val migrated = if (prefs.contains(KEY_AIRMOUSE_INV_Y)) {
                !prefs.getBoolean(KEY_AIRMOUSE_INV_Y, false)
            } else {
                false
            }
            prefs.edit()
                .putBoolean(KEY_AIRMOUSE_INV_Y, migrated)
                .putBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED, true)
                .apply()
        }
        // 第二步：纠正 v2.1 在「没存过这个键」的机器上误判出来的「反向」。
        if (!prefs.getBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED_V22, false)) {
            prefs.edit()
                .putBoolean(KEY_AIRMOUSE_INV_Y, false)
                .putBoolean(KEY_AIRMOUSE_INV_Y_MIGRATED_V22, true)
                .apply()
        }
    }

    /**
     * 鼠标的滚轮模式：开启后**上下俯仰手机** = 滚轮（上下滚动），不再移动光标。
     * 与触摸板的 [scrollMode] 相互独立。
     */
    var airMouseScrollMode: Boolean
        get() = prefs.getBoolean(KEY_AIRMOUSE_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_AIRMOUSE_SCROLL, value).apply()

    // ---------------- 键盘模式 ----------------

    /**
     * 竖屏键盘当前的布局 id（Compose 版用 "qwerty" / "t9" 两个值）。
     * 全屏（横屏）模式固定使用完整 104 键布局，与这个值无关。
     */
    var keyboardLayout: String
        get() = prefs.getString(KEY_KEYBOARD_LAYOUT, DEFAULT_KEYBOARD_LAYOUT) ?: DEFAULT_KEYBOARD_LAYOUT
        set(value) = prefs.edit().putString(KEY_KEYBOARD_LAYOUT, value).apply()

    /**
     * 全屏键盘相对「水平居中、贴底」默认位置的手动偏移（dp）。
     *
     * 用 dp 而不是像素存：同一台设备旋转后密度不变但尺寸变了，px 会让偏移量失去意义；
     * 而且 dp 让「手掌大小的微调」在不同手机上可复现。恢复时会重新夹到屏幕范围内。
     */
    var keyboardOffsetXDp: Float
        get() = clampOffset(prefs.getFloat(KEY_KEYBOARD_OFFSET_X, 0f))
        set(value) = prefs.edit().putFloat(KEY_KEYBOARD_OFFSET_X, clampOffset(value)).apply()

    var keyboardOffsetYDp: Float
        get() = clampOffset(prefs.getFloat(KEY_KEYBOARD_OFFSET_Y, 0f))
        set(value) = prefs.edit().putFloat(KEY_KEYBOARD_OFFSET_Y, clampOffset(value)).apply()

    private fun clampOffset(v: Float): Float {
        if (v.isNaN()) return 0f
        return v.coerceIn(-MAX_KEYBOARD_OFFSET_DP, MAX_KEYBOARD_OFFSET_DP)
    }

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

        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val KEY_LAST_SID = "last_sid"
        private const val KEY_LAST_PIN = "last_pin"
        private const val KEY_LAST_KEY_ID = "last_key_id"
        private const val KEY_TOUCHPAD_SENS = "touchpad_sensitivity"
        private const val KEY_TOUCH_ZERO_LATENCY = "touch_zero_latency"
        private const val KEY_SCROLL_MODE = "scroll_mode"
        private const val KEY_ZOOM_PREFERENCE = "zoom_preference"
        private const val KEY_AIRMOUSE_SENS = "airmouse_sensitivity"
        private const val KEY_AIRMOUSE_INV_X = "airmouse_invert_x"
        private const val KEY_AIRMOUSE_INV_Y = "airmouse_invert_y"

        /** 上下方向默认值翻正的一次性迁移标记（见 [airMouseInvertY]） */
        private const val KEY_AIRMOUSE_INV_Y_MIGRATED = "airmouse_invert_y_migrated_v21"

        /** v2.2：纠正 v2.1 迁移在「从没存过该键」的机器上误置的「上下反向」 */
        private const val KEY_AIRMOUSE_INV_Y_MIGRATED_V22 = "airmouse_invert_y_migrated_v22"
        private const val KEY_AIRMOUSE_SCROLL = "airmouse_scroll_mode"

        // ---- 键盘模式 ----
        const val DEFAULT_KEYBOARD_LAYOUT = "qwerty"
        private const val KEY_KEYBOARD_LAYOUT = "keyboard_layout"
        private const val KEY_KEYBOARD_OFFSET_X = "keyboard_offset_x_dp"
        private const val KEY_KEYBOARD_OFFSET_Y = "keyboard_offset_y_dp"

        /** 全屏键盘位置偏移的存盘上限（dp）：比这更大的偏移在任何手机上都会被夹回屏幕内，存了也没用 */
        const val MAX_KEYBOARD_OFFSET_DP = 400f
        private const val PIN_PREFIX = "cert_pin_"
        private const val CERT_PREFIX = "cert_hex_"

        const val MIN_TOUCHPAD_SENS = 0.5f
        const val MAX_TOUCHPAD_SENS = 3.0f
        const val MIN_AIRMOUSE_SENS = 0.1f
        const val MAX_AIRMOUSE_SENS = 10.0f
    }
}
