package com.airtouch.app.ui.redesign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.rememberRedesignUiState
import kotlin.math.roundToInt

/*
 * Compose Route: "keyboard_landscape"
 *
 * 布局：**上 30% 控制区 / 下 70% 键盘区**（兄弟布局，绝不叠层遮挡）。
 *
 * 上 30%：
 *  - 4:3 的新拟物点阵小触控板，可在该区域内**自由拖动**；
 *  - 另外两个按钮（触控开关、取消全屏）与小触控板编成一组，随它一起移动、互不遮挡；
 *  - 默认小触控板紧贴屏幕右侧边缘，两个按钮挨着它的左边缘；
 *    拖到左半区时顺序翻转，两个按钮改到触控板右侧，且都在左侧靠近屏幕边缘。
 *
 * 下 70%：
 *  - 全键盘按键铺满，右侧小键盘可一键收起/展开；
 *  - F1–F12 是更小的按钮，中间留弹性空隙，使 Del 的右端与 Bksp / Enter 等右端对齐；
 *  - 功能行与主打字行同处一个 Column，收起小键盘后一起铺满全宽。
 */

@Composable
fun LandscapeKeyboardScreen(
    modifier: Modifier = Modifier,
    state: RedesignUiState = RedesignUiState(),
    onAction: (RedesignAction) -> Unit = redesignLogAction,
) {
    val colors = AirTouchTokens.colors
    var numpadVisible by remember { mutableStateOf(true) }
    var touchpadVisible by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .padding(AirTouchDimens.pageMargin),
    ) {
        // ---- 上 30%：可自由移动的控制区 ----
        // 状态栏隐藏后多出来的纵向空间全部给上面这块（顶栏/浮动触控板）：
        // 30/70 → 33/67，键盘 Deck 的绝对高度不变，顶栏更高、触控板更大。
        TopControlArea(
            onAction = onAction,
            touchpadVisible = touchpadVisible,
            onToggleTouchpad = { touchpadVisible = !touchpadVisible },
            zeroLatency = state.touchZeroLatency,
            sensitivity = state.sensitivity,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.33f),
        )

        // ---- 「最近发送按键」：全屏键盘上也要看得到"按下去之后字符的变化" ----
        // 位置选在控制区与键盘 Deck **之间**：上面那片是自由拖动的浮动触控板 + 按钮组，
        // 塞进去迟早互相遮挡；这里是一条不参与拖动的固定横条，紧贴键盘（打字时视线就在这），
        // 高度只有一行，只从上下两个 weight 区里各拿一点高度，不改变原有比例关系。
        LastKeyCard(
            text = state.lastKeyLog,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AirTouchDimens.rowGap),
        )

        // ---- 下 67%：键盘按键 ----
        LandscapeKeyboardDeck(
            state = state,
            onAction = onAction,
            numpadVisible = numpadVisible,
            onToggleNumpad = { numpadVisible = !numpadVisible },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.67f),
        )
    }
}

// ---------------------------------------------------------------------------
// 「最近发送按键」横条：与半屏键盘页那块卡片同一套视觉
// ---------------------------------------------------------------------------

/**
 * 全屏（横屏）键盘页顶部的「最近发送按键」横条。
 *
 * 半屏键盘页有一块同名卡片（显示 `RedesignUiState.lastKeyLog`），全屏页原来什么都没有，
 * 于是全屏打字时**看不到按下去的字符变化**（用户反馈）。这里用同一套 token 复刻：
 * `kbBg` 底色 + `kbKeyBorder` 1dp 描边 + 16dp 圆角 + Monospace 小字，压成一行以省高度。
 */
@Composable
private fun LastKeyCard(text: String, modifier: Modifier = Modifier) {
    val colors = AirTouchTokens.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.kbBg)
            .border(1.dp, colors.kbKeyBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "最近发送按键",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textMuted,
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 上 30%：三个物体（触控板 + 两个按钮）成组自由移动
// ---------------------------------------------------------------------------

@Composable
private fun TopControlArea(
    onAction: (RedesignAction) -> Unit,
    touchpadVisible: Boolean,
    onToggleTouchpad: () -> Unit,
    zeroLatency: Boolean,
    sensitivity: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val areaH = maxHeight
        // 4:3 矩形：尽量占满整个上区高度（不再留大内边距/文字行），宽度 = 高 × 4/3
        val rawH = areaH - 4.dp
        val padH = if (rawH < 60.dp) 60.dp else rawH
        val padW = padH * 4f / 3f

        val buttonsW = AirTouchDimens.iconButtonSize * 2 + AirTouchDimens.rowGap
        val groupW = if (touchpadVisible) buttonsW + AirTouchDimens.rowGap + padW else buttonsW
        val areaWpx = with(density) { maxWidth.toPx() }
        val groupWpx = with(density) { groupW.toPx() }
        val maxDragPx = (areaWpx - groupWpx).coerceAtLeast(0f)

        // 0 = 默认贴右侧；负值 = 向左拖
        var dragX by remember { mutableFloatStateOf(0f) }
        // 组中心是否越过屏幕中线：决定两个按钮放在触控板哪一侧
        val onLeft = (areaWpx - groupWpx / 2f + dragX) < areaWpx / 2f

        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .pointerInput(maxDragPx) {
                        detectDragGestures(
                            onDragEnd = {},
                            onDragCancel = {},
                            onDrag = { change, drag ->
                                dragX = (dragX + drag.x).coerceIn(-maxDragPx, 0f)
                                change.consume()
                            },
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onLeft) {
                    if (touchpadVisible) {
                        MiniTouchpadBubble(
                            modifier = Modifier
                                .width(padW)
                                .height(padH),
                            onAction = onAction,
                            zeroLatency = zeroLatency,
                            sensitivity = sensitivity,
                            onDrag = { dx -> dragX = (dragX + dx).coerceIn(-maxDragPx, 0f) },
                        )
                    }
                    ControlButtons(
                        onAction = onAction,
                        touchpadVisible = touchpadVisible,
                        onToggleTouchpad = onToggleTouchpad,
                    )
                } else {
                    ControlButtons(
                        onAction = onAction,
                        touchpadVisible = touchpadVisible,
                        onToggleTouchpad = onToggleTouchpad,
                    )
                    if (touchpadVisible) {
                        MiniTouchpadBubble(
                            modifier = Modifier
                                .width(padW)
                                .height(padH),
                            onAction = onAction,
                            zeroLatency = zeroLatency,
                            sensitivity = sensitivity,
                            onDrag = { dx -> dragX = (dragX + dx).coerceIn(-maxDragPx, 0f) },
                        )
                    }
                }
            }
        }
    }
}

/** 两个圆钮：触控开关 + 取消全屏，始终紧挨触控板的内侧边缘。 */
@Composable
private fun ControlButtons(
    onAction: (RedesignAction) -> Unit,
    touchpadVisible: Boolean,
    onToggleTouchpad: () -> Unit,
) {
    val colors = AirTouchTokens.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TactileCircleIconButton(
            onClick = onToggleTouchpad,
            size = AirTouchDimens.iconButtonSize,
            accentLit = touchpadVisible,
        ) {
            HandPointerIcon(
                modifier = Modifier.size(AirTouchDimens.iconSize),
                tint = colors.textPrimary,
            )
        }
        TactileCircleIconButton(
            onClick = { onAction(RedesignAction.Click("退出全屏")) },
            size = AirTouchDimens.iconButtonSize,
        ) {
            ExitFullscreenIcon(
                modifier = Modifier.size(AirTouchDimens.iconSize),
                tint = colors.textPrimary,
            )
        }
    }
}

// MiniTouchpadBubble 已抽到 MiniTouchpad.kt（竖屏键盘页也要用同一块，见 r31 任务 5）。

// ---------------------------------------------------------------------------
// 下 70%：全键盘（功能行 + 5 行主键 + 可收起的小键盘）
// ---------------------------------------------------------------------------

@Composable
private fun LandscapeKeyboardDeck(
    state: RedesignUiState,
    onAction: (RedesignAction) -> Unit,
    numpadVisible: Boolean,
    onToggleNumpad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    val gap = AirTouchDimens.keyGap
    val locked: (String) -> Boolean = { it in state.stickyModifiers }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(colors.kbBg)
            .border(1.dp, colors.kbKeyBorder, RoundedCornerShape(22.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            // 功能行与主打字行放在同一个 Column：Del 右端与 Bksp / Enter 右端严格对齐
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                FunctionRow(
                    onAction = onAction,
                    modifier = Modifier.weight(1f),
                )
                FlatKeyRow(
                    specs = buildList {
                        add(key("`"))
                        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=").forEach { add(key(it, fontSize = 11.sp)) }
                        add(key("⌫ Bksp", key = "Backspace", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.8f))
                    },
                    modifier = Modifier.weight(1f),
                    spacing = gap,
                    onAction = onAction,
                    stickyLocked = locked,
                )
                FlatKeyRow(
                    specs = buildList {
                        add(key("Tab", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.4f))
                        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]").forEach { add(key(it, fontSize = 11.sp)) }
                        add(key("\\", fontSize = 11.sp, weight = 1.4f))
                    },
                    modifier = Modifier.weight(1f),
                    spacing = gap,
                    onAction = onAction,
                    stickyLocked = locked,
                )
                FlatKeyRow(
                    specs = buildList {
                        add(key("Caps", mod = "Caps", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.7f))
                        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'").forEach { add(key(it, fontSize = 11.sp)) }
                        add(key("Enter ↵", key = "Enter", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 2.1f))
                    },
                    modifier = Modifier.weight(1f),
                    spacing = gap,
                    onAction = onAction,
                    stickyLocked = locked,
                )
                // ZXCV 行：只保留左 Shift；右 Shift 的位置拆成两半 —— 左半 ↑，右半 Num 展开/收起
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    FlatKeycap(
                        label = "Shift",
                        onClick = { onAction(RedesignAction.ToggleSticky("Shift")) },
                        modifier = Modifier
                            .weight(2.2f)
                            .fillMaxHeight(),
                        variant = FlatKeyVariant.FUNCTION,
                        stickyLocked = locked("Shift"),
                        fontSize = 10.sp,
                    )
                    listOf("Z", "X", "C", "V", "B", "N", "M", ",", ".", "/").forEach { ch ->
                        FlatKeycap(
                            label = ch,
                            onClick = { onAction(RedesignAction.PressKey(ch)) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            fontSize = 11.sp,
                        )
                    }
                    FlatKeycap(
                        label = "▲",
                        onClick = { onAction(RedesignAction.PressKey("↑")) },
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        variant = FlatKeyVariant.FUNCTION,
                        fontSize = 10.sp,
                    )
                    FlatKeycap(
                        label = if (numpadVisible) "Num◀" else "Num▶",
                        onClick = onToggleNumpad,
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        variant = FlatKeyVariant.FUNCTION,
                        fontSize = 8.sp,
                    )
                }
                FlatKeyRow(
                    specs = listOf(
                        key("Ctrl", mod = "Ctrl", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.2f),
                        key("Win", mod = "Win", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.1f),
                        key("Alt", mod = "Alt", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.1f),
                        key("Space", weight = 5.4f, fontSize = 10.sp),
                        key("Alt", mod = "Alt", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.1f),
                        key("Ctrl", mod = "Ctrl", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp, weight = 1.1f),
                        // 四个方向键都单独给一个键。原来这里只有一个 `▲▼`（标签写着上下、实际只发 ↑），
                        // 所以全屏键盘**没有真正的「下」**（用户原话：「全屏键盘没有下这个按钮」；
                        // `RedesignController` 里 `"↓" / "▼" -> Vk.DOWN` 的映射一直都在，只是没人发它）。
                        key("◀", key = "←", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                        key("▲", key = "↑", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                        key("▼", key = "↓", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                        key("▶", key = "→", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                    ),
                    modifier = Modifier.weight(1f),
                    spacing = gap,
                    onAction = onAction,
                    stickyLocked = locked,
                )
            }

            if (numpadVisible) {
                CompactNumpad(
                    onAction = onAction,
                    modifier = Modifier
                        .width(132.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 功能行：Esc + **更小的 F1–F12** + 弹性空隙 + PrtSc + Del。
 * Del 位于行尾，右端与下面各行的 Bksp / Enter 右端对齐；收起小键盘后整行一起铺满全宽。
 */
@Composable
private fun FunctionRow(
    onAction: (RedesignAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = AirTouchDimens.keyGap
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        FlatKeycap(
            label = "Esc",
            onClick = { onAction(RedesignAction.PressKey("Esc")) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            variant = FlatKeyVariant.FUNCTION,
            fontSize = 10.sp,
        )
        repeat(12) { i ->
            FlatKeycap(
                label = "F${i + 1}",
                onClick = { onAction(RedesignAction.PressKey("F${i + 1}")) },
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight(),
                variant = FlatKeyVariant.FUNCTION,
                fontSize = 8.sp,
            )
        }
        // 弹性空隙：把 PrtSc / Del 推到行尾，保证 Del 右端对齐
        Spacer(modifier = Modifier.weight(1f))
        FlatKeycap(
            label = "PrtSc",
            onClick = { onAction(RedesignAction.PressKey("PrtSc")) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            variant = FlatKeyVariant.FUNCTION,
            fontSize = 9.sp,
        )
        FlatKeycap(
            label = "Del",
            onClick = { onAction(RedesignAction.PressKey("Delete")) },
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight(),
            variant = FlatKeyVariant.FUNCTION,
            fontSize = 10.sp,
        )
    }
}

private fun key(
    label: String,
    key: String = label,
    weight: Float = 1f,
    variant: FlatKeyVariant = FlatKeyVariant.NORMAL,
    mod: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
) = FlatKeySpec(label, key, weight, variant, mod, null, fontSize)

/** 右侧紧凑数字小键盘（4 列 × 5 行，含 + / Ent 跨两行、0 跨两列）。 */
@Composable
private fun CompactNumpad(
    onAction: (RedesignAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = AirTouchDimens.keyGap
    FixedGrid(
        columns = 4,
        rows = 5,
        modifier = modifier,
        hSpacing = gap,
        vSpacing = gap,
    ) {
        @Composable
        fun num(
            label: String,
            col: Int,
            row: Int,
            colSpan: Int = 1,
            rowSpan: Int = 1,
            fn: Boolean = false,
            fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
        ) {
            FlatKeycap(
                label = label,
                onClick = {
                    val vk = when (label) {
                        "Num" -> "NumLock"
                        "Ent" -> "NumEnter"
                        "+" -> "NumAdd"
                        "-" -> "NumSubtract"
                        "*" -> "NumMultiply"
                        "/" -> "NumDivide"
                        "." -> "NumDecimal"
                        else -> "Num$label"
                    }
                    onAction(RedesignAction.PressKey(vk))
                },
                modifier = Modifier.gridCell(col, row, colSpan, rowSpan),
                variant = if (fn) FlatKeyVariant.FUNCTION else FlatKeyVariant.NORMAL,
                fontSize = fontSize,
            )
        }
        num("Num", 0, 0, fn = true, fontSize = 9.sp)
        num("/", 1, 0, fn = true, fontSize = 10.sp)
        num("*", 2, 0, fn = true, fontSize = 10.sp)
        num("-", 3, 0, fn = true, fontSize = 10.sp)

        num("7", 0, 1)
        num("8", 1, 1)
        num("9", 2, 1)
        num("+", 3, 1, rowSpan = 2, fn = true, fontSize = 11.sp)

        num("4", 0, 2)
        num("5", 1, 2)
        num("6", 2, 2)

        num("1", 0, 3)
        num("2", 1, 3)
        num("3", 2, 3)
        num("Ent", 3, 3, rowSpan = 2, fn = true, fontSize = 9.sp)

        num("0", 0, 4, colSpan = 2)
        num(".", 2, 4)
    }
}

@Preview(name = "06 · 全屏横屏键盘 (Light)", showBackground = true, widthDp = 780, heightDp = 360)
@Composable
private fun LandscapeKeyboardScreenPreview() {
    val (state, onAction) = rememberRedesignUiState()
    AirTouchTheme(darkTheme = state.darkTheme, accent = state.accent) {
        LandscapeKeyboardScreen(state = state, onAction = onAction)
    }
}
