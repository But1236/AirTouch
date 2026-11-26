package com.airtouch.app.ui.redesign

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.airtouch.app.ui.redesign.integration.RedesignPrefs

/*
 * 触感（震动马达）真正落地的实现。
 *
 * 为什么以前"从来没管用过"：
 *  - 旧代码只有 Compose 的 `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.TextHandleMove)`，
 *    它最终走的是 `View.performHapticFeedback(TEXT_HANDLE_MOVE)`：一是这个常量本来就是给文本光标手柄用的
 *    极轻微 tick，很多 ROM / 机型直接映射成"什么都不做"；二是它受系统「触摸反馈 / 触摸时振动」开关与
 *    `View.isHapticFeedbackEnabled` 管，关掉就静默失效；三是**扁平键盘的键帽（[FlatKeycap]）压根没有任何
 *    haptic 调用**，所以竖屏/全屏键盘按下去永远不会震。
 *  - 这里改成直接拿 `Vibrator` 注入 `VibrationEffect`：不再依赖"触摸反馈"开关，只受系统总振动开关约束。
 *
 * 安全降级：没有马达（`hasVibrator()` 为 false）、拿不到服务、ROM 不支持预定义触感、
 * 系统关掉振动、马达被别的应用占用 —— 一律静默失败，绝不抛异常。
 */

/** 两档强度：轻 tick 用于按键/点击，稍强的 click 用于滚轮刻度一类"有段落感"的动作。 */
enum class AirTouchHapticStrength {
    /** 轻 tick：键帽、圆钮、开关、滑块的按下与点击。 */
    TICK,

    /** 稍强 click：滚轮刻度、九宫格方向步进等。 */
    CLICK,
}

/**
 * 触感播放器。**所有方法在开关关闭或没有马达时都是空操作**，调用点不需要自己判断。
 *
 * 用 [rememberAirTouchHaptics] 拿实例（它会读 `AirTouchTokens.hapticEnabled`）。
 */
class AirTouchHaptics internal constructor(
    private val vibrator: Vibrator?,
    /** 只读偏好：开关值**每次调用现读**，不走 Compose 状态（见 [enabled] 注释）。 */
    private val prefs: RedesignPrefs,
) {
    /**
     * 用户开关的**当前**值：每次调用时从 `SharedPreferences` 现读。
     *
     * 为什么不用 Compose 状态、也不在构造时传入：
     *  1) 大量调用点是在 `pointerInput(Unit)` / `remember` 里**长期捕获**这个实例的（键帽、物理键、
     *     滚轮都是），那些 lambda 不会因为开关变化而重建 —— 构造时定死就会永远停在旧值；
     *  2) r33 改成用 `SideEffect` 把 `AirTouchTokens.hapticEnabled` 同步进实例，用户实测**仍然**
     *     "设置里试振能震、按鼠标/键盘都不震"，说明那条状态链路本身没把值送到（开关只写了 prefs）。
     * 现在直接读 `prefs.haptic`（`RedesignAction.SetHaptic` 写的就是这个键），是唯一不会再骗人的来源。
     */
    val enabled: Boolean get() = runCatching { prefs.haptic }.getOrDefault(true)

    /** 本机是否真有振动马达（与用户开关无关）。false 时任何软件都震不了。 */
    val hasMotor: Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    /** 有马达、且用户开关是开的：false 时 [tick] / [click] 什么都不做。 */
    val available: Boolean get() = enabled && hasMotor

    fun tick() = play(AirTouchHapticStrength.TICK)

    fun click() = play(AirTouchHapticStrength.CLICK)

    /** 诊断/自检用：一次性长振（80ms、最大振幅）。设置页打开开关时放一次，用来确认马达到底会不会动。 */
    fun test() {
        // 刻意不看 enabled：这个调用就是用户"打开开关"那一下本身，此时旧实例的 enabled 还是 false。
        if (!hasMotor) return
        val motor = vibrator ?: return
        try {
            motor.vibrate(VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
            // 静默失败
        }
    }

    fun play(strength: AirTouchHapticStrength) {
        if (!available) return
        val motor = vibrator ?: return
        val ms = when (strength) {
            AirTouchHapticStrength.TICK -> 15L
            AirTouchHapticStrength.CLICK -> 25L
        }
        val amplitude = when (strength) {
            AirTouchHapticStrength.TICK -> 120
            AirTouchHapticStrength.CLICK -> 200
        }
        try {
            // 注意：**不要**用 `VibrationEffect.createPredefined(EFFECT_TICK/CLICK)`。
            // 预定义触感受系统「触摸反馈 / 触摸时振动」开关和 `View.isHapticFeedbackEnabled` 管，
            // 很多 ROM 把它直接映射成极弱甚至无动作 —— r31 就是用预定义做的，用户反馈"还是震动不了"。
            // 自定时长 + 显式振幅走的是普通振动通道，只受系统总振动开关约束，明显更可靠。
            motor.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        } catch (_: Throwable) {
            // 系统总振动开关关着 / 马达被占用 / 权限被撤：静默失败。
        }
    }
}

/**
 * 记住一个 [AirTouchHaptics]。**读的是 [AirTouchTokens.hapticEnabled]**（也就是设置页
 * 「线性马达震动反馈」那个开关，由 `AirTouchTheme` 通过 `LocalHapticEnabled` 提供）：
 * 开关关闭时返回的实例 `available == false`，所有调用点自动完全不振。
 */
@Composable
fun rememberAirTouchHaptics(): AirTouchHaptics {
    val context = LocalContext.current
    // 实例只跟 context 绑定：开关值不参与构造，被 pointerInput 长期捕获的同一个实例也不会失效。
    return remember(context) {
        AirTouchHaptics(
            vibrator = obtainVibrator(context.applicationContext),
            prefs = RedesignPrefs(context.applicationContext),
        )
    }
}

/** API 31+ 走 `VibratorManager.defaultVibrator`，低版本走老的 `VIBRATOR_SERVICE`。拿不到就返回 null。 */
private fun obtainVibrator(context: Context): Vibrator? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
} catch (_: Throwable) {
    null
}
