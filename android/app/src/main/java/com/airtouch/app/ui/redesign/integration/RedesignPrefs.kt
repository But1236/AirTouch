package com.airtouch.app.ui.redesign.integration

import android.content.Context
import android.content.SharedPreferences

/**
 * 重构 UI 自己的外观偏好。刻意**不改** [com.airtouch.app.settings.SettingsStore]：
 * 那份 SharedPreferences 存的是连接/信任/灵敏度等业务数据，与外观无关，混进去会污染
 * 备份规则与既有读取逻辑。这里单独一个文件，只放 4 个外观开关。
 */
class RedesignPrefs(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK, value).apply()

    var accentKey: String
        get() = prefs.getString(KEY_ACCENT, "yellow") ?: "yellow"
        set(value) = prefs.edit().putString(KEY_ACCENT, value).apply()

    /** 自由选色（ARGB）；null 表示使用 4 个预设之一 */
    var customAccentArgb: Int?
        get() = if (prefs.contains(KEY_CUSTOM_ACCENT)) prefs.getInt(KEY_CUSTOM_ACCENT, 0) else null
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_CUSTOM_ACCENT) else putInt(KEY_CUSTOM_ACCENT, value)
        }.apply()

    var leftHand: Boolean
        get() = prefs.getBoolean(KEY_LEFT_HAND, false)
        set(value) = prefs.edit().putBoolean(KEY_LEFT_HAND, value).apply()

    var haptic: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    private companion object {
        const val PREFS_NAME = "airtouch_redesign_ui"
        const val KEY_DARK = "dark_theme"
        const val KEY_ACCENT = "accent_key"
        const val KEY_CUSTOM_ACCENT = "custom_accent_argb"
        const val KEY_LEFT_HAND = "left_hand"
        const val KEY_HAPTIC = "haptic"
    }
}
