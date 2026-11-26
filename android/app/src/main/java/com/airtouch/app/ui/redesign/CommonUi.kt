package com.airtouch.app.ui.redesign

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 双击判定窗口：两次轻点的间隔超过它就不算一组（模式 B 用 500ms = 系统双击时间）。
 */
private const val DoubleTapMillis = 500L

/** 模式 A 的**观察窗**：第一下轻点抬起后先扣住 click 看这么久，没有后续动作就补发。 */
private const val ObserveMillis = 160L

/** 模式 A：第二下按住超过这么久（还没抬起）就算拖拽。 */
private const val Tap2HoldMillis = 120L

/** 模式 A：第二下滑动超过这么多像素就算拖拽。 */
private const val TapSlidePx = 4f

/**
 * 模式 B：第二下按下后，电脑端光标累积位移达到这么多像素就发出去、紧接着按下左键。
 * 3px 是为了走出 Windows 默认的 4×4px「双击判定框」（`SM_CXDOUBLECLK`）。代价是拖拽起点偏这 3px。
 */
private const val EscapePx = 3f

/**
 * [trackToUp] 的哨兵返回值：手势里出现多指（双指滚动 / 捏合），这个状态机当场放弃。
 * 双指滚动曾经被误判成「Tap2 滑动 → 拖拽」，于是滚动的同时左键被按住，用户看到的就是
 * 「双指滑动没了」。
 */
private const val MultiTouch = -1L

/**
 * 双击第二下**判定期间**的位移闸门：true = 先扣住轴移处理器发出来的位移不发
 * （否则光标会先溜一小段，拖拽起点就偏了）。故意不用 Compose state：判定期每帧都在读写，
 * 不该触发重组。
 */
private class DragArming {
    var armed = false
}

/**
 * 跟踪一根手指到它抬手，返回 (抬手时刻, 手指走过的距离)。
 *
 * 写成 [AwaitPointerEventScope] 的顶层扩展而不是局部 suspend 函数：pointerInput 的手势作用域是
 * `@RestrictsSuspension` 的，局部 suspend 函数在里面不允许调用 `awaitPointerEvent`。
 */
private suspend fun AwaitPointerEventScope.trackToUp(id: PointerId, start: Offset): Pair<Long, Float> {
    var last = start
    var moved = 0f
    while (true) {
        val event = awaitPointerEvent()
        // 多指（双指滚动 / 捏合）不归这个状态机管：立刻放弃，绝不要把滚动当成拖拽。
        if (event.changes.count { it.pressed } > 1) return MultiTouch to 0f
        val change = event.changes.firstOrNull { it.id == id }
        if (change != null) {
            moved += (change.position - last).getDistance()
            last = change.position
        }
        if (change == null || !change.pressed) {
            // 只在「一个按着的指针都没有」时才算抬手：某个事件里这个 id 整帧缺席
            // （多指、父层手势都会）不能当成抬手，否则就成了「按下后立刻松开」。
            if (event.changes.none { it.pressed }) {
                return (change?.uptimeMillis ?: 0L) to moved
            }
            continue
        }
    }
    @Suppress("UNREACHABLE_CODE") return 0L to moved
}

/**
 * 点阵触控板表面（DotMatrixCanvas）：内凹槽 + 点阵背景 + 跟随手指的强调色涟漪。
 * 第二步已接真实接口：
 *  - 单指拖动 → [RedesignAction.TouchpadMove]（协议 move）；
 *  - 轻点 / 双击 → [RedesignAction.TouchpadClick]（左键 / 右键）；
 *  - 双指上下 / 左右滑 → [RedesignAction.TouchpadScroll]（高精度滚轮）。
 * 键盘页的「悬浮微触控板气泡」也复用它，是唯一允许出现在扁平键盘之上的拟物元素。
 */
@Composable
fun DotMatrixTouchpad(
    modifier: Modifier = Modifier,
    corner: Dp = AirTouchDimens.radiusCard,
    showCoordsLabel: Boolean = true,
    /** 设置项「触摸零延时」：true = 模式 B（单击零延时 + 约 3px 逃逸拖拽），false = 模式 A（0px 精准拖拽） */
    zeroLatency: Boolean = false,
    /** 触摸板灵敏度：模式 B 判断「电脑端光标累积位移」要用它换算 */
    sensitivity: Float = 1f,
    onAction: (RedesignAction) -> Unit = redesignLogAction,
) {
    val colors = AirTouchTokens.colors
    var ripple by remember { mutableStateOf<Offset?>(null) }
    // 当前按下的指针数：1 = 移动光标，>=2 = 高精度滚轮 / 捏合
    var pointerCount by remember { mutableIntStateOf(0) }
    // 捏合倍数：协议 §2.1 要求 scale = 「当前两指间距 ÷ 手势开始时的间距」，也就是**从 down 开始累计**。
    // detectTransformGestures 给的 zoom 是「相对上一帧」的倍数，直接当 scale 发就永远在 1.0 附近
    // → 电脑上缩放一直卡在 90%~100%。所以这里自己乘起来。
    var pinchScale by remember { mutableStateOf(1f) }
    // 「按住不动 → 左键按住（拖拽）」进行中，以及这次拖拽已经发了多少移动帧。
    // 只被右下角那个独立小 composable 读，所以每帧写也不会让点阵区重组。
    var dragActive by remember { mutableStateOf(false) }
    var dragMoves by remember { mutableIntStateOf(0) }
    // 双击第二下的「观望期」（见 DragArming）。
    val arming = remember { DragArming() }
    // 拖拽开始的「咔」声震动（物理键手感）。开关关闭时是空操作。
    val haptics = rememberAirTouchHaptics()

    NeumorphicWell(modifier = modifier, corner = corner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dotMatrixBackground()
                // 统计同时按下的指针数（不消费事件，供下面的手势判别使用），
                // 同时把捏合手势的 down / up 阶段发出去 —— 协议要求 down → move* → up，
                // 服务端靠这三个阶段 TouchBegin / TouchMove / TouchEnd（AirTouchServer §2.1）。
                // 以前只发 move，缩放手势在电脑端从来没有真正开始过，所以一卡一卡。
                .pointerInput(Unit) {
                    while (true) {
                        awaitPointerEventScope {
                            val event = awaitPointerEvent()
                            val n = event.changes.count { it.pressed }
                            if (n >= 2 && pointerCount < 2) {
                                pinchScale = 1f
                                onAction(RedesignAction.TouchpadPinch("down", 1.0, 0, 0))
                            } else if (n < 2 && pointerCount >= 2) {
                                pinchScale = 1f
                                onAction(RedesignAction.TouchpadPinch("up", 1.0, 0, 0))
                            }
                            pointerCount = n
                        }
                    }
                }
                // pan → 单指 move / 双指 scroll；zoom → 双指捏合 touch 帧
                .pointerInput(Unit) {
                    detectTransformGestures(
                        panZoomLock = false,
                        onGesture = { centroid, pan, zoom, _ ->
                            ripple = centroid
                            if (pointerCount >= 2) {
                                if (zoom != 1f) {
                                    // 捏合优先：这一刻不再发滚轮，否则缩放和滚轮同时作用，更卡。
                                    // 发的是**累计**倍数（相对本次手势开始），不是本帧增量。
                                    pinchScale = (pinchScale * zoom).coerceIn(0.05f, 20f)
                                    onAction(
                                        RedesignAction.TouchpadPinch(
                                            "move",
                                            pinchScale.toDouble(),
                                            pan.x.roundToInt(),
                                            pan.y.roundToInt(),
                                        )
                                    )
                                } else if (abs(pan.y) >= abs(pan.x)) {
                                    val d = (pan.y / 3f).roundToInt().coerceIn(-60, 60)
                                    if (d != 0) onAction(RedesignAction.TouchpadScroll(d, false))
                                } else {
                                    val d = (pan.x / 3f).roundToInt().coerceIn(-60, 60)
                                    if (d != 0) onAction(RedesignAction.TouchpadScroll(d, true))
                                }
                            } else {
                                val dx = pan.x.roundToInt()
                                val dy = pan.y.roundToInt()
                                if (dx != 0 || dy != 0) {
                                    if (!arming.armed) {
                                        // 观望期（双击第二下）里扣住不发：等判定结果，
                                        // 判定成拖拽时手势那边会把这段净位移补上。
                                        dragMoves++
                                        onAction(RedesignAction.TouchpadMove(dx, dy))
                                    }
                                }
                            }
                        },
                    )
                }
                // 单指手势状态机：两套模式，由设置项「触摸零延时」切换。
                //
                // 模式 A（关闭，默认 · 0px 精准拖拽）：第一下轻点抬起**先扣住 click**，开 160ms 观察窗；
                //   窗内没有后续动作 → 补发那一下 click（就是普通单击，只是晚 160ms）；窗内第二次手指落下
                //   → 第二下没怎么动就抬起 = 普通双击（连发两次 click）；第二下按住超 120ms 或滑动 ≥4px
                //   = **吞掉第一下的 click**，只在原点按一次左键进拖拽。因为没有「点击在前、按下在后」，
                //   Windows 根本不会把这次按下当成双击第二下，起点就是像素级的。
                //
                // 模式 B（开启 · 单击零延时）：第一下抬起立刻 click（零延迟）。500ms 内第二下落下后先不发左键：
                //   没动就抬起 → 补发一次 click（普通双击）；滑动到「电脑端光标累积位移 ≥3px」（刚好走出
                //   Windows 默认 4×4px 双击判定框）→ **先发这 3px、紧接着按下左键**进拖拽（不回位，
                //   否则会有反向跳动）。代价就是这约 3px 的起点偏移。
                .pointerInput(zeroLatency, sensitivity) {
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    val slop = viewConfiguration.touchSlop
                    var lastTapUp = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        ripple = down.position
                        val isSecond = lastTapUp != 0L &&
                            down.uptimeMillis - lastTapUp <= DoubleTapMillis &&
                            (down.position - lastTapPos).getDistance() <= slop * 3f

                        // 跟踪到这一根手指抬手
                        if (zeroLatency) {
                            // ---------------- 模式 B ----------------
                            if (!isSecond) {
                                // 第一下：抬手立刻发 click（零延时）
                                val (upTime, moved) = trackToUp(down.id, down.position)
                                if (upTime > 0L && upTime - down.uptimeMillis <= longPressTimeout &&
                                    moved <= slop
                                ) {
                                    lastTapUp = upTime
                                    lastTapPos = down.position
                                    onAction(RedesignAction.TouchpadClick("left"))
                                }
                                return@awaitEachGesture
                            }

                            // 第二下：先不发左键。判定期间扣住轴移处理器的位移，别让光标先溜。
                            var last = down.position
                            var accX = 0f
                            var accY = 0f
                            var holding = false
                            var multi = false
                            arming.armed = true
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) {
                                    // 双指滚动 / 捏合：放弃判定（第一下的 click 早就发出去了）
                                    multi = true
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null) {
                                    accX += (change.position.x - last.x) * sensitivity
                                    accY += (change.position.y - last.y) * sensitivity
                                    last = change.position
                                }
                                if (change == null || !change.pressed) {
                                    if (event.changes.none { it.pressed }) break
                                    continue
                                }
                                if (!holding &&
                                    (abs(accX) >= EscapePx || abs(accY) >= EscapePx)
                                ) {
                                    // 刚好走出双击判定框：先发这 3px，紧接着按下左键（不回位）
                                    arming.armed = false
                                    val dx = accX.roundToInt()
                                    val dy = accY.roundToInt()
                                    onAction(RedesignAction.TouchpadMove(dx, dy))
                                    onAction(RedesignAction.SetButton("left", true))
                                    haptics.click()
                                    holding = true
                                    dragActive = true
                                    dragMoves = 1
                                }
                            }
                            arming.armed = false
                            if (holding) {
                                dragActive = false
                                onAction(RedesignAction.SetButton("left", false))
                            } else if (!multi) {
                                // 没怎么动就抬起了 → 普通双击的第二下
                                lastTapUp = 0L
                                onAction(RedesignAction.TouchpadClick("left"))
                            }
                            return@awaitEachGesture
                        }

                        // ---------------- 模式 A ----------------
                        val (upTime, moved) = trackToUp(down.id, down.position)
                        if (upTime <= 0L || upTime - down.uptimeMillis > longPressTimeout || moved > slop) {
                            // 不是轻点（按久了 / 滑了 / 多指）：什么都不发，交给轴移处理器
                            return@awaitEachGesture
                        }

                        // 第一下是轻点：**扣住 click**，开观察窗
                        var secondDown: PointerInputChange? = null
                        var multiTouch = false
                        val closed = withTimeoutOrNull(ObserveMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size > 1) {
                                    // 双指/多指：用户在做滚动或捏合，补上扣住的单击但不进拖拽
                                    multiTouch = true
                                    return@withTimeoutOrNull true
                                }
                                if (pressed.size == 1) {
                                    secondDown = pressed.first()
                                    return@withTimeoutOrNull true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        if (closed == null || multiTouch) {
                            // 超时 = 一次普通单击（补发第一下）；多指 = 同上，但绝不进拖拽
                            onAction(RedesignAction.TouchpadClick("left"))
                            return@awaitEachGesture
                        }

                        val second = secondDown ?: return@awaitEachGesture
                        // 第二下：判定期间扣住位移，拖拽起点才是 0px
                        var last2 = second.position
                        var moved2 = 0f
                        var drag = false
                        var up2 = 0L
                        var multi2 = false
                        arming.armed = true
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) {
                                // 第二根手指也下来了 = 双指滚动 / 捏合：放弃这次判定
                                multi2 = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == second.id }
                            if (change != null) {
                                moved2 += (change.position - last2).getDistance()
                                last2 = change.position
                            }
                            if (change == null || !change.pressed) {
                                if (event.changes.none { it.pressed }) {
                                    up2 = change?.uptimeMillis ?: 0L
                                    break
                                }
                                continue
                            }
                            if (moved2 >= TapSlidePx ||
                                change.uptimeMillis - second.uptimeMillis >= Tap2HoldMillis
                            ) {
                                drag = true
                                break
                            }
                        }
                        if (!drag && up2 > 0L && up2 - second.uptimeMillis >= Tap2HoldMillis) {
                            drag = true
                        }
                        arming.armed = false

                        if (multi2) {
                            // 多指：把扣住的第一下单击补上（用户确实点了一下），但不进拖拽、也不补第二下
                            onAction(RedesignAction.TouchpadClick("left"))
                            return@awaitEachGesture
                        }

                        if (!drag) {
                            // 第二下没怎么动就抬起 → 普通双击：连发两次 click
                            lastTapUp = 0L
                            onAction(RedesignAction.TouchpadClick("left"))
                            onAction(RedesignAction.TouchpadClick("left"))
                            return@awaitEachGesture
                        }

                        // 拖拽：**吞掉第一下的 click**，只在原点按一次左键（0px 起点）
                        val stillDown = up2 == 0L
                        dragActive = true
                        dragMoves = 0
                        onAction(RedesignAction.SetButton("left", true))
                        haptics.click()
                        if (stillDown) {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                        dragActive = false
                        onAction(RedesignAction.SetButton("left", false))
                    }
                },
        ) {
            ripple?.let { pos ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(colors.accentGlow, radius = 20.dp.toPx(), center = pos)
                    drawCircle(
                        colors.accent,
                        radius = 20.dp.toPx(),
                        center = pos,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            if (showCoordsLabel) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.bgBase.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    TouchpadStatusLabel {
                        when {
                            dragActive -> "拖拽中 · 左键按住 · 已发 $dragMoves 帧"
                            ripple != null -> "dx:${ripple!!.x.toInt()} dy:${ripple!!.y.toInt()}"
                            else -> if (zeroLatency) {
                                "零延时模式：单击即时 · 双击拖拽起点偏移约 3px"
                            } else {
                                "精准模式：单击延后 160ms · 双击拖拽 0px 偏移"
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 触控板右下角那行小字。**必须是独立 composable**：它读的 ripple / 拖拽发帧数每帧都在变，
 * 写在 DotMatrixTouchpad 里就等于每帧重组整个点阵区（连手势一起卷进去）。
 * 放在这里，重组范围就只有这一行字。
 */
@Composable
private fun TouchpadStatusLabel(text: () -> String) {
    Text(
        text = text(),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = AirTouchTokens.colors.textMuted,
    )
}

/** 第一步统一日志：所有静态按钮只打 Log，不触碰任何业务接口。 */
fun logRedesignClick(label: String) {
    Log.d("AirTouchRedesign", "click: $label")
}

/** 默认回调，供 6 个页面的 `onAction` 使用。 */
val redesignLogAction: (RedesignAction) -> Unit = { action ->
    Log.d("AirTouchRedesign", "action: $action")
}

/** 鼠标 / 触控板页顶部的紧凑连接状态胶囊。延迟取自 Ping/Pong 实测 RTT（[RedesignUiState.latencyMs]）。 */
@Composable
fun ConnectionPill(
    modifier: Modifier = Modifier,
    connected: Boolean = true,
    latencyMs: Int = -1,
) {
    val colors = AirTouchTokens.colors
    NeumorphicWell(
        modifier = modifier,
        corner = AirTouchDimens.radiusPill,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (connected) colors.success else colors.danger),
            )
            Text(
                text = when {
                    !connected -> "未连接 · -- ms"
                    latencyMs < 0 -> "已连接 · -- ms"
                    else -> "已连接 · ${latencyMs}ms"
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
        }
    }
}

/** 灵敏度调节块（鼠标页 / 触控板页共用）：标签 + 数值 + 内凹滑块。 */
@Composable
fun SensitivityBlock(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("灵敏度调节", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
            Text(
                text = String.format("%.1fx", value),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
        }
        Spacer(Modifier.size(AirTouchDimens.rowGap))
        TactileSlider(value = value, onValueChange = onValueChange)
    }
}

/**
 * 共享底部控制坞：返回 / 暂停 / 传文件 三键。
 * 「鼠标 / 触控板 / 键盘」一体式滑块已上移到 [RedesignScreenHost] 层，由三个模式页共享同一个实例，
 * 这样切页时滑块的 animateDpAsState 弹簧动画能真正从旧位置滑到新位置。
 */
@Composable
fun BottomControlDock(
    paused: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { logRedesignClick("返回主页") },
    onTogglePause: () -> Unit = { logRedesignClick("暂停/恢复传感") },
    onFile: () -> Unit = { logRedesignClick("打开文件传输面板") },
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TactileCircleIconButton(onClick = onBack, size = AirTouchDimens.iconButtonSize) {
                    BackArrowIcon(modifier = Modifier.size(AirTouchDimens.iconSize))
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TactileCircleIconButton(
                    onClick = onTogglePause,
                    size = AirTouchDimens.iconButtonSize,
                    activeRing = paused,
                ) {
                    Text(
                        text = if (paused) "▶" else "||",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AirTouchTokens.colors.textPrimary,
                    )
                }
            }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TactileCircleIconButton(onClick = onFile, size = AirTouchDimens.iconButtonSize) {
                FolderIcon(modifier = Modifier.size(AirTouchDimens.iconSize))
            }
        }
    }
}

/**
 * 「灵敏度调节 + 返回 / 暂停 / 传文件」的标准组合（全拟物样式）。
 * 鼠标页、触控板页、竖屏键盘页都用这同一个 composable，保证三者完全一样：
 * 鼠标 / 触控板放在页面底部，键盘页放在键盘 Deck 正上方（挨着键盘）。
 */
@Composable
fun ModeControls(
    sensitivity: Float,
    paused: Boolean,
    onSensitivityChange: (Float) -> Unit,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AirTouchDimens.blockGap),
    ) {
        SensitivityBlock(value = sensitivity, onValueChange = onSensitivityChange)
        BottomControlDock(
            paused = paused,
            onBack = onBack,
            onTogglePause = onTogglePause,
            onFile = onFile,
        )
    }
}
