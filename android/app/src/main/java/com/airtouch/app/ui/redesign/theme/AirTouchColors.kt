package com.airtouch.app.ui.redesign.theme

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/*
 * 本文件是 docs/airtouch_android_ui_blueprint.html 中 CSS Variables 的 1:1 Kotlin 转写。
 * 规范来源：蓝图 :root / [data-theme="dark"] / [data-accent="*"] 三段。
 * 严禁凭感觉改色值 —— 第二步联调前，视觉比对以蓝图浏览器渲染为准。
 */

/** 4 套强调色预设，对应蓝图 `[data-accent="yellow|coral|emerald|blue"]`。默认亮黄 #F5B800。 */
enum class AccentPreset(
    val key: String,
    val label: String,
    val accent: Color,
    val accentLight: Color,
    val accentDark: Color,
    val accentText: Color,
) {
    YELLOW(
        key = "yellow",
        label = "YELLOW",
        accent = Color(0xFFF5B800),
        accentLight = Color(0xFFFFD447),
        accentDark = Color(0xFFD19A00),
        accentText = Color(0xFF14171D),
    ),
    CORAL(
        key = "coral",
        label = "CORAL",
        accent = Color(0xFFF25C3B),
        accentLight = Color(0xFFFF7E61),
        accentDark = Color(0xFFCC4123),
        accentText = Color(0xFFFFFFFF),
    ),
    EMERALD(
        key = "emerald",
        label = "EMERALD",
        accent = Color(0xFF10B981),
        accentLight = Color(0xFF34D399),
        accentDark = Color(0xFF059669),
        accentText = Color(0xFFFFFFFF),
    ),
    BLUE(
        key = "blue",
        label = "BLUE",
        accent = Color(0xFF3B82F6),
        accentLight = Color(0xFF60A5FA),
        accentDark = Color(0xFF2563EB),
        accentText = Color(0xFFFFFFFF),
    );

    /** 蓝图 `--accent-glow: rgba(..., 0.38)`。 */
    val accentGlow: Color get() = accent.copy(alpha = 0.38f)

    fun toTokens(): AccentTokens = AccentTokens(accent, accentLight, accentDark, accentGlow, accentText)
}

/**
 * 一组强调色派生值。除了 4 个固定预设，用户也可以用色相滑杆自由选任意颜色，
 * 这时由 [customAccentTokens] 从基色派生出亮部/暗部/光晕/文字色。
 */
data class AccentTokens(
    val accent: Color,
    val accentLight: Color,
    val accentDark: Color,
    val accentGlow: Color,
    val accentText: Color,
)

/** 从任意基色派生一整套强调色（HSV 调整明度/饱和度 + 亮度决定文字色）。 */
fun customAccentTokens(base: Color): AccentTokens {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(base.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1]
    val value = hsv[2]

    val light = Color(
        AndroidColor.HSVToColor(
            floatArrayOf(hue, (sat * 0.72f).coerceIn(0f, 1f), (value * 1.28f).coerceIn(0f, 1f))
        )
    )
    val dark = Color(
        AndroidColor.HSVToColor(
            floatArrayOf(hue, (sat * 1.12f).coerceIn(0f, 1f), (value * 0.70f).coerceIn(0f, 1f))
        )
    )
    // 深底用白字、浅底用深字，保证对比度
    val r = ((base.toArgb() shr 16) and 0xFF) / 255f
    val g = ((base.toArgb() shr 8) and 0xFF) / 255f
    val b = (base.toArgb() and 0xFF) / 255f
    val luminance = 0.299f * r + 0.587f * g + 0.114f * b
    val text = if (luminance > 0.62f) Color(0xFF14171D) else Color(0xFFFFFFFF)

    return AccentTokens(
        accent = base,
        accentLight = light,
        accentDark = dark,
        accentGlow = base.copy(alpha = 0.38f),
        accentText = text,
    )
}

/** 由 Hue(0..360) 生成一个饱和、明亮的强调基色。 */
fun accentFromHue(hue: Float): Color =
    Color(AndroidColor.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), 0.82f, 0.94f)))

/**
 * 完整色彩 Token 集合。
 * Material3 的 ColorScheme 没有 bg-canvas / bg-well / shadow-light / shadow-dark 等拟物专用槽位，
 * 因此这里自定义一套，通过 [LocalAirTouchColors] 下发，而不是硬塞进 ColorScheme。
 */
data class AirTouchColors(
    // 新拟物基底
    val bgCanvas: Color,
    val bgBase: Color,
    val bgSurfaceTop: Color,
    val bgSurfaceBottom: Color,
    val bgWell: Color,
    val shadowLight: Color,
    val shadowDark: Color,
    val borderSubtle: Color,
    val borderContrast: Color,
    // 文本
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val dotColor: Color,
    // 纯扁平键盘
    val kbBg: Color,
    val kbKeyBg: Color,
    val kbKeyFn: Color,
    val kbKeyBorder: Color,
    val kbKeyPressed: Color,
    // 强调色
    val accent: Color,
    val accentLight: Color,
    val accentDark: Color,
    val accentGlow: Color,
    val accentText: Color,
    // 语义色（蓝图内联固定值）
    val success: Color,
    val danger: Color,
    val cameraBackdrop: Color,
    val isLight: Boolean,
)

/** 浅色模式（Cool Off-White Base）—— 蓝图 `:root`。 */
fun lightAirTouchColors(preset: AccentPreset = AccentPreset.YELLOW): AirTouchColors =
    lightAirTouchColors(preset.toTokens())

fun lightAirTouchColors(tokens: AccentTokens): AirTouchColors = AirTouchColors(
    bgCanvas = Color(0xFFE2E7EE),
    bgBase = Color(0xFFEDF1F5),
    bgSurfaceTop = Color(0xFFF6F8FB),
    bgSurfaceBottom = Color(0xFFE4E9F0),
    bgWell = Color(0xFFE4E9F0),
    shadowLight = Color(0xFFFFFFFF),
    shadowDark = Color(0xFFC8D1DC),
    borderSubtle = Color(0xFFFFFFFF).copy(alpha = 0.78f),
    borderContrast = Color(0xFF0F172A).copy(alpha = 0.08f),
    textPrimary = Color(0xFF181C24),
    textSecondary = Color(0xFF5B6678),
    textMuted = Color(0xFF8C97A8),
    dotColor = Color(0xFF181C24).copy(alpha = 0.24f),
    kbBg = Color(0xFFE3E8EF),
    kbKeyBg = Color(0xFFF6F8FB),
    kbKeyFn = Color(0xFFD9E0EA),
    kbKeyBorder = Color(0xFF0F172A).copy(alpha = 0.09f),
    kbKeyPressed = Color(0xFFCFD8E3),
    accent = tokens.accent,
    accentLight = tokens.accentLight,
    accentDark = tokens.accentDark,
    accentGlow = tokens.accentGlow,
    accentText = tokens.accentText,
    success = Color(0xFF10B981),
    danger = Color(0xFFEF4444),
    cameraBackdrop = Color(0xFF0F172A),
    isLight = true,
)

/** 深色模式（Deep Slate Base）—— 蓝图 `[data-theme="dark"]`。 */
fun darkAirTouchColors(preset: AccentPreset = AccentPreset.YELLOW): AirTouchColors =
    darkAirTouchColors(preset.toTokens())

fun darkAirTouchColors(tokens: AccentTokens): AirTouchColors = AirTouchColors(
    bgCanvas = Color(0xFF121418),
    bgBase = Color(0xFF1C1F24),
    bgSurfaceTop = Color(0xFF23272E),
    bgSurfaceBottom = Color(0xFF181B20),
    bgWell = Color(0xFF15181C),
    shadowLight = Color(0xFF272B33),
    shadowDark = Color(0xFF0E1013),
    borderSubtle = Color(0xFFFFFFFF).copy(alpha = 0.07f),
    borderContrast = Color(0xFFFFFFFF).copy(alpha = 0.09f),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF9CA6B8),
    textMuted = Color(0xFF646E80),
    dotColor = Color(0xFFF1F5F9).copy(alpha = 0.22f),
    kbBg = Color(0xFF14161A),
    kbKeyBg = Color(0xFF252A32),
    kbKeyFn = Color(0xFF1D2128),
    kbKeyBorder = Color(0xFFFFFFFF).copy(alpha = 0.07f),
    kbKeyPressed = Color(0xFF323945),
    accent = tokens.accent,
    accentLight = tokens.accentLight,
    accentDark = tokens.accentDark,
    accentGlow = tokens.accentGlow,
    accentText = tokens.accentText,
    success = Color(0xFF10B981),
    danger = Color(0xFFEF4444),
    cameraBackdrop = Color(0xFF0F172A),
    isLight = false,
)
