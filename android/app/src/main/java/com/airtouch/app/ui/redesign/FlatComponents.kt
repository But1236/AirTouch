package com.airtouch.app.ui.redesign

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * ============================================================================
 * 纯扁平设计（Flat Design）键盘组件库
 * 适用：竖屏键盘 / 横屏全屏键盘 —— 与 TactileComponents.kt 严格隔离。
 * 规范（蓝图 .flat-key）：
 *  - 零阴影、零渐变、6dp 统一圆角（--radius-key: 6px）；
 *  - 仅 1dp 低对比描边；按下 = kb-key-pressed + scale(0.95)；
 *  - Ctrl / Shift / Alt / Win 单击粘滞锁定，锁定态整块填全局强调色；
 *  - 功能键用 kb-key-fn 底色 + text-secondary。
 * ============================================================================
 */

enum class FlatKeyVariant { NORMAL, FUNCTION, ACCENT }

/** 一键位描述；[modifierName] 非空时该键为粘滞修饰键。 */
data class FlatKeySpec(
    val label: String,
    val key: String = label,
    val weight: Float = 1f,
    val variant: FlatKeyVariant = FlatKeyVariant.NORMAL,
    val modifierName: String? = null,
    val subLabel: String? = null,
    val fontSize: TextUnit = 12.sp,
    /**
     * 九键数字键：非空 = 这个键属于九键拼音输入，值就是它对应的数字。
     *
     * 它**不产生任何协议帧** —— 数字只是拼音的输入码（`5` = jkl 三个字母），按键时交给
     * `RedesignAction.T9Digit`。若当成普通键发出去，九键立刻退化成数字小键盘。
     */
    val t9Digit: Int? = null,
    /**
     * 键帽**上滑**依次发送的内容（空 = 该键不支持上滑）。多于一个时循环，见 [FlatKeycap]。
     * 留空则由 [defaultSwipeUpKeys] 自动推导。
     */
    val swipeUpKeys: List<String> = emptyList(),
)

/**
 * 键帽「上滑」内容的默认推导：
 *  - **九键数字键**（[FlatKeySpec.t9Digit] 非空）→ 该键上的字母循环，**从第二个字母开始**
 *    （2=abc → b、c、a、b…）：这样不经过拼音也能直接敲字母（用户要求）；
 *  - **QWERTY 字母键**（单个 A–Z / a–z）→ 大写，形式 `Shift+X`（控制器展开成按住 Shift 再按 X）；
 *  - **QWERTY 数字键** → 上档符号，形式 `Shift+1` → `!`（键盘布局按美式算）；
 *  - 其它键（空格 / 退格 / 功能键 / 标点）→ 空，上滑无效果。
 */
internal fun defaultSwipeUpKeys(spec: FlatKeySpec): List<String> {
    spec.t9Digit?.let { d ->
        val letters = T9_SWIPE_LETTERS[d].orEmpty()
        if (letters.isNotEmpty()) {
            // drop(1) + first()：第一次上滑发 b，再滑发 c，之后回到 a 循环。
            // 用大写字母**但不加 Shift** —— `vkFor("B")` 就是 VK_B，不带修饰键按下去得到的是小写 b，
            // 和 QWERTY 键帽按 "Q" 得到 q 是同一个道理。
            return (letters.drop(1) + letters.first()).map { it.uppercaseChar().toString() }
        }
    }
    if (spec.modifierName != null) return emptyList()
    val k = spec.key
    if (k.length != 1) return emptyList()
    val c = k[0]
    return when {
        c in 'A'..'Z' -> listOf("Shift+$c")
        c in 'a'..'z' -> listOf("Shift+${c.uppercaseChar()}")
        c in '1'..'9' -> listOf("Shift+$c")
        c == '0' -> listOf("Shift+0")
        else -> emptyList()
    }
}

/** 九键每个数字键上的字母（小写）。 */
private val T9_SWIPE_LETTERS: Map<Int, String> = mapOf(
    2 to "abc", 3 to "def", 4 to "ghi", 5 to "jkl",
    6 to "mno", 7 to "pqrs", 8 to "tuv", 9 to "wxyz",
)

/** 单个扁平键帽：零阴影、6dp 圆角；支持粘滞锁定高亮。 */
@Composable
fun FlatKeycap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: FlatKeyVariant = FlatKeyVariant.NORMAL,
    stickyLocked: Boolean = false,
    subLabel: String? = null,
    fontSize: TextUnit = 12.sp,
    corner: Dp = AirTouchDimens.radiusKey,
    icon: (@Composable () -> Unit)? = null,
    /**
     * **上滑（按住键往上方滑出阈值）**依次发送的内容，多于一个时循环。空 = 该键不支持上滑。
     * 手势在 [FlatKeycap] 内部处理：上滑一旦触发，这一次手势就**不再**发点按。
     */
    swipeUpKeys: List<String> = emptyList(),
    /** 上滑触发时回调（参数是 [swipeUpKeys] 里轮到的那一项）。 */
    onSwipeUpKey: (String) -> Unit = {},
) {
    val colors = AirTouchTokens.colors
    // 键帽按下震动：以前这里**没有任何 haptic 调用**，所以竖屏/全屏键盘按下去从来不震。
    val haptics = rememberAirTouchHaptics()
    var pressed by remember { mutableStateOf(false) }
    // 上滑循环游标：九键一个键上有多个字母，连续上滑要依次发 b、c、a…（跨手势保留）
    var swipeCycle by remember { mutableIntStateOf(0) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "flatKeyScale")

    val accentKey = stickyLocked || variant == FlatKeyVariant.ACCENT
    val baseBg = when {
        accentKey -> colors.accent
        variant == FlatKeyVariant.FUNCTION -> colors.kbKeyFn
        else -> colors.kbKeyBg
    }
    val targetBg = if (pressed && !accentKey) colors.kbKeyPressed else baseBg
    val bg by animateColorAsState(targetBg, label = "flatKeyBg")

    val contentColor = when {
        accentKey -> colors.accentText
        variant == FlatKeyVariant.FUNCTION -> colors.textSecondary
        else -> colors.textPrimary
    }
    val borderColor = if (accentKey) Color.Transparent else colors.kbKeyBorder
    val shape = RoundedCornerShape(corner)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .pointerInput(onClick, swipeUpKeys) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptics.tick()
                    var riseY = 0f          // 累加的纵向位移（向上为负）
                    var swiped = false
                    var liftedInside = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        riseY += change.positionChange().y
                        if (!swiped && swipeUpKeys.isNotEmpty() && riseY <= -slop) {
                            // 上滑上档：取循环里的下一项，然后这次手势就交给它，不再算点按
                            swiped = true
                            onSwipeUpKey(swipeUpKeys[swipeCycle % swipeUpKeys.size])
                            swipeCycle++
                            haptics.click()
                        }
                        if (swiped) change.consume()
                        if (!change.pressed) {
                            liftedInside = change.position.x in 0f..size.width.toFloat() &&
                                change.position.y in 0f..size.height.toFloat()
                            break
                        }
                    }
                    pressed = false
                    // 只有「没触发上滑」且「在键帽内抬手」才算点按（滑出去松手 = 取消，和原来一致）
                    if (!swiped && liftedInside) onClick()
                }
            }
            .padding(horizontal = 2.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (subLabel != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = subLabel,
                    fontSize = 9.sp,
                    color = contentColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        } else if (icon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon()
                if (label.isNotEmpty()) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Text(
                text = label,
                fontSize = fontSize,
                fontWeight = if (accentKey) FontWeight.Bold
                else if (variant == FlatKeyVariant.FUNCTION) FontWeight.SemiBold
                else FontWeight.Medium,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** 一行键位；[keyHeight] 为空时撑满父容器高度（横屏全键盘用）。 */
@Composable
fun FlatKeyRow(
    specs: List<FlatKeySpec>,
    onAction: (RedesignAction) -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp? = null,
    spacing: Dp = AirTouchDimens.keyGap,
    stickyLocked: (String) -> Boolean = { false },
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        specs.forEach { spec ->
            val keyModifier = Modifier
                .weight(spec.weight)
                .then(if (keyHeight != null) Modifier.height(keyHeight) else Modifier.fillMaxHeight())
            FlatKeycap(
                label = spec.label,
                subLabel = spec.subLabel,
                variant = spec.variant,
                fontSize = spec.fontSize,
                stickyLocked = spec.modifierName?.let(stickyLocked) ?: false,
                onClick = {
                    if (spec.modifierName != null) {
                        onAction(RedesignAction.ToggleSticky(spec.modifierName))
                    } else {
                        onAction(RedesignAction.PressKey(spec.key))
                    }
                },
                swipeUpKeys = spec.swipeUpKeys.ifEmpty { defaultSwipeUpKeys(spec) },
                onSwipeUpKey = { onAction(RedesignAction.PressKey(it)) },
                modifier = keyModifier,
            )
        }
    }
}

// 「鼠标 / 触控板 / 键盘」一体式滑块已统一为 TactileSegmentedControl，
// 并由 RedesignScreenHost 在三个模式页之间共享同一个实例（切页时滑块有弹簧滑动动画）。
// 扁平版分段控件已删除，避免出现「键盘页滑块和别人不一样」的情况。

/**
 * 第 5 项：夸克浏览器式「一字形滑动回弹摇杆」，取代原来的十字方向键。
 * - 横向胶囊滑轨 + 中心可拖动滑块（纯扁平，键盘页风格隔离）；
 * - **只识别水平方向**：按住左右拖过阈值触发 ← / →（支持连续步进）。纵向位移一律丢弃、
 *   小球也不跟着上下跑，松手回中 —— 以前上下拨动会发 ↑ / ↓，用户明确不要；
 * - 每次触发用 [AirTouchHapticStrength.CLICK] 档震动（真马达，见 [rememberAirTouchHaptics]）；
 * - 松手用 Animatable + spring 物理动画自动回弹归位。
 */
@Composable
fun LinearSwipeJoystick(
    onDirection: (String) -> Unit,
    modifier: Modifier = Modifier
        .width(88.dp)
        .height(AirTouchDimens.portraitKeyHeight),
) {
    val colors = AirTouchTokens.colors
    val haptics = rememberAirTouchHaptics()
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var stepX by remember { mutableFloatStateOf(0f) }

    fun tick(dir: String) {
        onDirection(dir)
        haptics.click()
    }

    fun springBack() {
        scope.launch {
            offsetX.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
        haptics.click()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.kbKeyFn)
            .border(1.dp, colors.kbKeyBorder, RoundedCornerShape(50))
            .pointerInput(Unit) {
                val threshold: Float = 26.dp.toPx()
                val maxOffset: Float = 26.dp.toPx()
                detectDragGestures(
                    onDragStart = { stepX = 0f },
                    onDragEnd = { springBack() },
                    onDragCancel = { springBack() },
                    onDrag = { change, drag ->
                        change.consume()
                        stepX += drag.x
                        // 纵向分量（drag.y）直接忽略：这个摇杆只发左右
                        scope.launch {
                            offsetX.snapTo(
                                (offsetX.value + drag.x * 0.55f).coerceIn(-maxOffset, maxOffset)
                            )
                        }
                        while (stepX >= threshold) { stepX -= threshold; tick("→") }
                        while (stepX <= -threshold) { stepX += threshold; tick("←") }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◀", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.textMuted)
            Text("▶", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.textMuted)
        }
        // 小圆球滑块：零文字、正圆、只占轨道高度的一小部分
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accent)
                .border(1.dp, colors.accentText.copy(alpha = 0.55f), RoundedCornerShape(50)),
        )
    }
}

// ---------------------------------------------------------------------------
// 定长网格（用于 5×4 九键、横屏数字小键盘的 rowspan / colspan）
// ---------------------------------------------------------------------------

private data class GridCell(val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int)

private class GridCellModifier(
    private val col: Int,
    private val row: Int,
    private val colSpan: Int,
    private val rowSpan: Int,
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = GridCell(col, row, colSpan, rowSpan)
}

fun Modifier.gridCell(col: Int, row: Int, colSpan: Int = 1, rowSpan: Int = 1): Modifier =
    this.then(GridCellModifier(col, row, colSpan, rowSpan))

/**
 * 固定行列网格，子项通过 [gridCell] 声明位置与跨度。
 * 用于精确复刻蓝图 `grid-cols-5` 九键与 `row-span-2` 数字键盘。
 */
@Composable
fun FixedGrid(
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    hSpacing: Dp = 4.dp,
    vSpacing: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hs = hSpacing.toPx()
        val vs = vSpacing.toPx()
        val cellW = ((constraints.maxWidth - hs * (columns - 1)) / columns).coerceAtLeast(0f)
        val cellH = ((constraints.maxHeight - vs * (rows - 1)) / rows).coerceAtLeast(0f)

        val items = measurables.map { measurable ->
            val cell = measurable.parentData as? GridCell ?: GridCell(0, 0, 1, 1)
            val w = (cellW * cell.colSpan + hs * (cell.colSpan - 1)).roundToInt().coerceAtLeast(1)
            val h = (cellH * cell.rowSpan + vs * (cell.rowSpan - 1)).roundToInt().coerceAtLeast(1)
            measurable.measure(Constraints.fixed(w, h)) to cell
        }

        layout(constraints.maxWidth, constraints.maxHeight) {
            items.forEach { (placeable, cell) ->
                placeable.placeRelative(
                    x = (cellW * cell.col + hs * cell.col).roundToInt(),
                    y = (cellH * cell.row + vs * cell.row).roundToInt(),
                )
            }
        }
    }
}
