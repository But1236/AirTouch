package com.airtouch.app.ui.redesign

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.rememberRedesignUiState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/*
 * Compose Route: "keyboard_portrait"
 * 蓝图规范：键盘区域「零拟物阴影」，纯扁平 6dp 圆角键帽；
 * Ctrl/Shift/Alt/Win 支持单击粘滞锁定（高亮强调色）；支持一键切换 5×4 九键与全键盘。
 * 键盘键帽与输入区一律用 FlatComponents 的扁平样式；只有页面级圆钮（返回 / 全屏 / 小触控板开关 /
 * 传文件 / 设置 / 删除）用拟物圆钮，和鼠标页 / 触控板页顶部那几个按钮保持同一套外观。
 */

/** 用户要求：非全屏（竖屏）键盘整体高度压到 0.83 倍。只作用于本页，全屏横屏键盘不受影响。 */
private const val KeyboardScale = 0.83f

/** 键盘左右到屏幕的空隙。原来是和页面边距一样的 20dp，用户觉得两边留白太大。 */
private val KeyboardSidePad = 8.dp

@Composable
fun FlatKeyboardScreen(
    modifier: Modifier = Modifier,
    state: RedesignUiState = RedesignUiState(),
    onAction: (RedesignAction) -> Unit = redesignLogAction,
) {
    val colors = AirTouchTokens.colors
    var screenshotToast by remember { mutableStateOf(false) }
    // 小触控板（r31 任务 5）：默认显示，和全屏（横屏）键盘页的 touchpadVisible = true 保持一致；
    // 由「传文件」左边的圆钮开关；位置可拖动。
    // 用 rememberSaveable：进设置页再返回时路由会重建组合，普通 remember 会把小触控板的
    // 显示状态与拖动位置丢掉（r33：设置页返回已改成回到来时的页面，这个丢失就变得很显眼了）。
    var miniTouchpadVisible by rememberSaveable { mutableStateOf(true) }
    // 拖动偏移。**NaN = 用户还没拖过**，此时显示在最右（右侧留白 = 键盘 Deck 的水平留白），
    // 真实数值要等 BoxWithConstraints 量出可用宽度才知道，所以用 NaN 当「未初始化」哨兵。
    var touchpadDragX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(screenshotToast) {
        if (screenshotToast) {
            delay(3600)
            screenshotToast = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 页面底色与鼠标页 / 触控板页 / 设置页 / 首页保持一致（也都是 bgCanvas，
            // RedesignScreenHost 背后铺的也是 bgCanvas）。这里原来是 bgBase，比别的页面浅一档，
            // 加上下面按钮行又用了 bgCanvas，就成了「半屏界面颜色深浅不一」。
            .background(colors.bgCanvas),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 上半部分：纯扁平留白区 ----
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 系统输入法一弹出，这一块会被压缩：内容改成可滚动，宁可滚动也不把
                    // 下面「小触控板 + 按钮排 + 键盘」挤出屏幕（那三块已经移到这个可压缩区之外）。
                    .verticalScroll(rememberScrollState())
                    .padding(AirTouchDimens.phonePadding),
                verticalArrangement = Arrangement.spacedBy(AirTouchDimens.blockGap),
            ) {
                // 顶部：状态胶囊 + 设置（与鼠标页/触控板页同一行布局）。
                // 必须显式传 state.latencyMs：ConnectionPill 的默认值是 -1，不传会显示「-- ms」。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionPill(connected = state.connected, latencyMs = state.latencyMs)
                    TactileCircleIconButton(
                        onClick = { onAction(RedesignAction.Click("打开设置")) },
                        size = AirTouchDimens.iconButtonSize,
                    ) {
                        GearIcon(modifier = Modifier.size(AirTouchDimens.iconSize), tint = colors.textPrimary)
                    }
                }

                // （原来这里有一行装饰性英文标签 `PURE FLAT MODE`：没有任何功能，用户看不懂，已按
                //   「这啥啊删掉吧」删掉。延迟信息由上面那行胶囊负责，不需要再挂一行字。）
                // 最近发送按键 + 系统输入法输入区：**拼成一个圆角矩形**（外面一圈共用描边，
                // 中间一条 hairline 分隔，两块之间不留缝），下面那块高度约两个字符高。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.kbBg)
                        .border(
                            width = 1.dp,
                            color = colors.kbKeyBorder,
                            shape = RoundedCornerShape(16.dp),
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        Text("最近发送按键", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textMuted)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            keyLogText(state.lastKeyLog),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.kbKeyBorder),
                    )
                    // 同一个圆角矩形里的下半块：点一下就地调起系统输入法（不弹窗、不跳页）
                    SystemImeInputBar(
                        onText = { onAction(RedesignAction.PressKey(it)) },
                        // 系统输入法里按退格：以前这里被 onValueChange 吞掉，电脑上的字删不掉（r31 任务 6）
                        onBackspace = { onAction(RedesignAction.PressKey("Backspace")) },
                    )
                }

            }

            // ---- 小触控板：**固定在按钮行正上方**（4:3，与全屏横屏键盘页同一块）----
            // 放在 weight(1f) 那个可压缩区**之外**：系统输入法把上半区撑高/压缩时，
            // 这块和下面的按钮排都不会被挤走或盖住（用户要求它们始终可见可点）。
            if (miniTouchpadVisible) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 左右留白 = 键盘 Deck 的水平留白（KeyboardSidePad），
                        // 所以「停最右」时触控板右边缘与键盘右边缘严格对齐
                        .padding(horizontal = KeyboardSidePad),
                    contentAlignment = Alignment.Center,
                ) {
                    val density = LocalDensity.current
                    val padH = 132.dp
                    val padW = padH * 4f / 3f
                    val areaWpx = with(density) { maxWidth.toPx() }
                    val padWpx = with(density) { padW.toPx() }
                    // 拖动范围：整块触控板不能越过键盘左右留白
                    val maxDragPx = ((areaWpx - padWpx) / 2f).coerceAtLeast(0f)
                    // 用户没拖过 → 停在最右（+maxDragPx）；拖过 → 用用户的位置，
                    // 并且每次布局都夹一遍（换屏/旋转后旧值可能超界）
                    val dragX = (if (touchpadDragX.isNaN()) maxDragPx else touchpadDragX)
                        .coerceIn(-maxDragPx, maxDragPx)
                    Box(
                        modifier = Modifier.offset { IntOffset(dragX.roundToInt(), 0) },
                    ) {
                        MiniTouchpadBubble(
                            modifier = Modifier
                                .width(padW)
                                .height(padH),
                            onAction = onAction,
                            zeroLatency = state.touchZeroLatency,
                            sensitivity = state.sensitivity,
                            onDrag = { dx ->
                                val base = if (touchpadDragX.isNaN()) maxDragPx else touchpadDragX
                                touchpadDragX = (base + dx).coerceIn(-maxDragPx, maxDragPx)
                            },
                        )
                    }
                }
            }

        // ---- 键盘正上方：从左到右依次是 返回 / 传文件 / 小触控板开关 / 全屏 四个拟态圆钮 ----
        // 整排再往下挪约 0.4 个按钮高度：上半留白加大、下半留白归零（整行只高 0.4h−rowGap，
        // 不会盖住下面的键盘 Deck）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgCanvas)
                .padding(
                    start = KeyboardSidePad,
                    end = KeyboardSidePad,
                    top = AirTouchDimens.rowGap + AirTouchDimens.iconButtonSize * 0.4f,
                    bottom = 0.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TactileCircleIconButton(
                    onClick = { onAction(RedesignAction.Click("返回主页")) },
                    size = AirTouchDimens.iconButtonSize,
                ) {
                    BackArrowIcon(modifier = Modifier.size(AirTouchDimens.iconSize))
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TactileCircleIconButton(
                    onClick = { onAction(RedesignAction.OpenFilePicker) },
                    size = AirTouchDimens.iconButtonSize,
                ) {
                    FolderIcon(modifier = Modifier.size(AirTouchDimens.iconSize))
                }
            }
            // ---- 小触控板开关（样式/尺寸与本页其它圆钮一致）----
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TactileCircleIconButton(
                    onClick = { miniTouchpadVisible = !miniTouchpadVisible },
                    size = AirTouchDimens.iconButtonSize,
                    accentLit = miniTouchpadVisible,
                ) {
                    HandPointerIcon(modifier = Modifier.size(AirTouchDimens.iconSize))
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TactileCircleIconButton(
                    onClick = { onAction(RedesignAction.Click("展开全屏横屏键盘")) },
                    size = AirTouchDimens.iconButtonSize,
                ) {
                    FullscreenIcon(modifier = Modifier.size(AirTouchDimens.iconSize))
                }
            }
        }

            // ---- 纯扁平键盘 Deck ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.kbBg)
                    .padding(
                        start = KeyboardSidePad,
                        end = KeyboardSidePad,
                        top = 8.dp,
                        bottom = AirTouchDimens.blockGap,
                    ),
                verticalArrangement = Arrangement.spacedBy(AirTouchDimens.keyGap),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.kbKeyBorder),
                )
                KeyboardToolbar(
                    t9Mode = state.t9Mode,
                    onToggleT9 = { onAction(RedesignAction.SetT9(!state.t9Mode)) },
                    onAction = onAction,
                    onScreenshot = {
                        onAction(RedesignAction.Screenshot)
                        screenshotToast = true
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.kbKeyBorder),
                )

                if (state.t9Mode) {
                    T9KeyboardDeck(state = state, onAction = onAction)
                } else {
                    QwertyKeyboardDeck(state = state, onAction = onAction)
                }
            }
        }

        // 截屏回传浮层 Toast（不影响键盘布局）
        AnimatedVisibility(
            visible = screenshotToast,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.bgBase)
                    .border(1.5.dp, colors.accent, RoundedCornerShape(16.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        // 原来是手写的 Color(0xFF1E293B) / Color.White：深浅不随主题走，
                        // 换成和键盘键帽同一套 token（浅色主题浅灰底 + 深色字，深色主题反过来）
                        .background(colors.kbKeyFn),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("PC.PNG", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textPrimary)
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("已发送 PrintScreen 按键", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    Text("当前协议无截屏回传帧，回传预览需扩展协议", fontSize = 10.sp, color = colors.textSecondary)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("OK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accentText)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工具条：Esc | 切换9键 | 十字摇杆 | Del | 截图
// ---------------------------------------------------------------------------

@Composable
private fun KeyboardToolbar(
    t9Mode: Boolean,
    onToggleT9: () -> Unit,
    onAction: (RedesignAction) -> Unit,
    onScreenshot: () -> Unit,
) {
    // Esc / 切换9键 这一行压到原来的 0.6 倍高；5 个工具键 weight(1f) 等宽
    val toolH = AirTouchDimens.portraitKeyHeight * 0.6f * KeyboardScale
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(toolH),
        horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatKeycap(
            label = "Esc",
            onClick = { onAction(RedesignAction.PressKey("Esc")) },
            modifier = Modifier
                .weight(1f)
                .height(toolH),
            variant = FlatKeyVariant.FUNCTION,
            fontSize = 10.sp,
        )
        FlatKeycap(
            label = if (t9Mode) "切全键盘" else "切换9键",
            onClick = onToggleT9,
            modifier = Modifier
                .weight(1f)
                .height(toolH),
            variant = FlatKeyVariant.FUNCTION,
            stickyLocked = t9Mode,
            fontSize = 10.sp,
        )
        // 一字形滑动回弹摇杆（中间的小圆球滑块）
        LinearSwipeJoystick(
            onDirection = { onAction(RedesignAction.PressKey(it)) },
            modifier = Modifier
                .weight(1f)
                .height(toolH),
        )
        FlatKeycap(
            label = "Del",
            onClick = { onAction(RedesignAction.PressKey("Delete")) },
            modifier = Modifier
                .weight(1f)
                .height(toolH),
            variant = FlatKeyVariant.FUNCTION,
            fontSize = 10.sp,
        )
        FlatKeycap(
            label = "截图",
            onClick = onScreenshot,
            modifier = Modifier
                .weight(1f)
                .height(toolH),
            variant = FlatKeyVariant.FUNCTION,
            fontSize = 10.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// QWERTY 全键盘
// ---------------------------------------------------------------------------

private fun key(
    label: String,
    key: String = label,
    weight: Float = 1f,
    variant: FlatKeyVariant = FlatKeyVariant.NORMAL,
    mod: String? = null,
    sub: String? = null,
    fontSize: TextUnit = 12.sp,
    t9: Int? = null,
) = FlatKeySpec(label, key, weight, variant, mod, sub, fontSize, t9)

@Composable
private fun QwertyKeyboardDeck(state: RedesignUiState, onAction: (RedesignAction) -> Unit) {
    val locked: (String) -> Boolean = { it in state.stickyModifiers }
    Column(verticalArrangement = Arrangement.spacedBy(AirTouchDimens.keyGap)) {
        FlatKeyRow(
            specs = listOf(
                key("~"), key("1"), key("2"), key("3"), key("4"), key("5"),
                key("6"), key("7"), key("8"), key("9"), key("0"),
                key("⌫", variant = FlatKeyVariant.FUNCTION),
            ),
            keyHeight = AirTouchDimens.portraitKeyHeight * KeyboardScale,
            onAction = onAction,
            stickyLocked = locked,
        )
        FlatKeyRow(
            specs = listOf(
                key("Tab", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                key("Q"), key("W"), key("E"), key("R"), key("T"), key("Y"), key("U"),
                key("I"), key("O"), key("P"),
            ),
            keyHeight = AirTouchDimens.portraitKeyHeight * KeyboardScale,
            onAction = onAction,
            stickyLocked = locked,
        )
        FlatKeyRow(
            specs = listOf(
                key("Caps", mod = "Caps", variant = FlatKeyVariant.FUNCTION, fontSize = 9.sp),
                key("A"), key("S"), key("D"), key("F"), key("G"), key("H"),
                key("J"), key("K"), key("L"),
                key("↵", key = "Enter", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
            ),
            keyHeight = AirTouchDimens.portraitKeyHeight * KeyboardScale,
            onAction = onAction,
            stickyLocked = locked,
        )
        FlatKeyRow(
            specs = listOf(
                key("⇧", mod = "Shift", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                key("Z"), key("X"), key("C"), key("V"), key("B"), key("N"), key("M"), key("."),
                key("⇧", mod = "Shift", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
            ),
            keyHeight = AirTouchDimens.portraitKeyHeight * KeyboardScale,
            onAction = onAction,
            stickyLocked = locked,
        )
        FlatKeyRow(
            specs = listOf(
                key("Ctrl", mod = "Ctrl", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                key("Win", mod = "Win", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                key("Alt", mod = "Alt", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                key("Space", weight = 5f, fontSize = 11.sp),
                key("Alt", mod = "Alt", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
                key("Ctrl", mod = "Ctrl", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp),
            ),
            keyHeight = AirTouchDimens.portraitKeyHeight * KeyboardScale,
            onAction = onAction,
            stickyLocked = locked,
        )
    }
}

// ---------------------------------------------------------------------------
// 9 键（5 列 × 4 行）
// ---------------------------------------------------------------------------

@Composable
private fun T9KeyboardDeck(state: RedesignUiState, onAction: (RedesignAction) -> Unit) {
    val locked: (String) -> Boolean = { it in state.stickyModifiers }
    // 九键每行 54dp，按键间距统一 5dp
    val rowH = AirTouchDimens.t9KeyHeight * KeyboardScale
    val gap = AirTouchDimens.keyGap
    val composing = state.t9Composition.isNotEmpty()

    // 候选条只在「正在组合拼音」时出现，不占常态高度
    if (state.t9Candidates.isNotEmpty()) {
        T9CandidateBar(
            composition = state.t9Composition,
            candidates = state.t9Candidates,
            onPick = { index -> onAction(RedesignAction.T9Commit(index)) },
        )
    }

    FixedGrid(
        columns = 5,
        rows = 4,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowH * 4 + gap * 3),
        hSpacing = gap,
        vSpacing = gap,
    ) {
        val cells = listOf(
            // row 0
            T9Cell(0, 0, key("，", variant = FlatKeyVariant.FUNCTION)),
            T9Cell(1, 0, key("1", key = "1 @", sub = "@/.")),
            T9Cell(2, 0, key("2", key = "2 ABC", sub = "ABC", t9 = 2)),
            T9Cell(3, 0, key("3", key = "3 DEF", sub = "DEF", t9 = 3)),
            T9Cell(4, 0, key("⌫", variant = FlatKeyVariant.FUNCTION, fontSize = 14.sp)),
            // row 1
            T9Cell(0, 1, key("。", variant = FlatKeyVariant.FUNCTION)),
            T9Cell(1, 1, key("4", key = "4 GHI", sub = "GHI", t9 = 4)),
            T9Cell(2, 1, key("5", key = "5 JKL", sub = "JKL", t9 = 5)),
            T9Cell(3, 1, key("6", key = "6 MNO", sub = "MNO", t9 = 6)),
            T9Cell(4, 1, key("复制", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp)),
            // row 2
            T9Cell(0, 2, key("？", variant = FlatKeyVariant.FUNCTION)),
            T9Cell(1, 2, key("7", key = "7 PQRS", sub = "PQRS", t9 = 7)),
            T9Cell(2, 2, key("8", key = "8 TUV", sub = "TUV", t9 = 8)),
            T9Cell(3, 2, key("9", key = "9 WXYZ", sub = "WXYZ", t9 = 9)),
            // 用户要求：九键的 Shift 位改成「粘贴」（这个布局本来就不打大写字母，Shift 用处不大）
            T9Cell(4, 2, key("粘贴", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp)),
            // row 3
            T9Cell(0, 3, key("Ctrl", mod = "Ctrl", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp)),
            T9Cell(1, 3, key("123", variant = FlatKeyVariant.FUNCTION)),
            T9Cell(2, 3, key("空格", key = "Space")),
            T9Cell(3, 3, key("Alt", mod = "Alt", variant = FlatKeyVariant.FUNCTION, fontSize = 10.sp)),
            T9Cell(4, 3, key("回车", key = "Enter", variant = FlatKeyVariant.ACCENT)),
        )
        cells.forEach { cell ->
            val spec = cell.spec
            FlatKeycap(
                label = spec.label,
                subLabel = spec.subLabel,
                variant = spec.variant,
                fontSize = spec.fontSize,
                stickyLocked = spec.modifierName?.let(locked) ?: false,
                onClick = {
                    val digit = spec.t9Digit
                    when {
                        spec.modifierName != null ->
                            onAction(RedesignAction.ToggleSticky(spec.modifierName))
                        // 九键数字键：交给拼音状态机，绝不直接发按键帧
                        digit != null -> onAction(RedesignAction.T9Digit(digit))
                        // 组合中才被九键吃掉：退一位数字 / 上屏第一个候选
                        spec.key == "⌫" && composing -> onAction(RedesignAction.T9Backspace)
                        spec.key == "Space" && composing -> onAction(RedesignAction.T9Commit(0))
                        // 「复制 / 粘贴」直接落到 PressKey → 控制器发一整套 Ctrl+C / Ctrl+V
                        else -> onAction(RedesignAction.PressKey(spec.key))
                    }
                },
                modifier = Modifier.gridCell(cell.col, cell.row),
                // 键帽上滑（r35 任务 3）：九键数字键上滑依次发该键上的字母（b、c、a 循环），
                // 不经过拼音也能直接打字；具体内容由 defaultSwipeUpKeys 推导。
                swipeUpKeys = defaultSwipeUpKeys(spec),
                onSwipeUpKey = { onAction(RedesignAction.PressKey(it)) },
            )
        }
    }
}

/**
 * 九键候选条：左边是正在组合的数字串（按最优切分加了分隔符，`64'426`），
 * 右边是按优先级排好的候选字 / 词 —— 点一个就上屏（Unicode 文本注入），第一个用强调色标出。
 *
 * 纯扁平风格，与键盘其它部分一致（这个文件不引用任何拟物组件）。
 */
@Composable
private fun T9CandidateBar(
    composition: String,
    candidates: List<String>,
    onPick: (Int) -> Unit,
) {
    val colors = AirTouchTokens.colors
    val rowH = AirTouchDimens.t9KeyHeight * KeyboardScale
    val gap = AirTouchDimens.keyGap
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowH)
            .clip(RoundedCornerShape(AirTouchDimens.radiusKey))
            .background(colors.kbKeyFn)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text(
            text = composition,
            modifier = Modifier.padding(start = 2.dp),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = colors.accent,
        )
        candidates.forEachIndexed { index, text ->
            FlatKeycap(
                label = text,
                onClick = { onPick(index) },
                modifier = Modifier.height(rowH - gap * 2),
                variant = if (index == 0) FlatKeyVariant.ACCENT else FlatKeyVariant.NORMAL,
                fontSize = 15.sp,
            )
        }
    }
}

private data class T9Cell(val col: Int, val row: Int, val spec: FlatKeySpec)

/**
 * 系统输入法输入区：**紧贴在「实时键值发送监视器」下方、中间不留空隙**（两块拼成一个圆角块）。
 *
 * 点一下就地调起系统输入法（不开弹窗、不跳页面），中文 / 标点 / 长文本由系统输入法组好字之后，
 * 只把**新提交的字符**按协议 §2.7 的 Unicode 文本帧注入电脑。
 * 关键：`composition` 非空表示输入法正在组词（拼音字母还没选字），这时一个字符都不能发，
 * 否则电脑上会把拼音字母一个个打出来。
 *
 * 退格（r31 任务 6）：这里曾经只转发「末尾追加」，注释还写着「删除一律忽略」—— 结果是手机输入法里
 * 按退格只能删掉本地那行字，**电脑上的字永远删不掉**。现在末尾删除会按删掉的字符数补发 Backspace。
 *
 * 高度（本轮）：输入框锁死一行（`singleLine` + `heightIn(min = 32.dp, max = 40.dp)`），
 * 超出部分由 Compose 自己跟着光标横向滚动 —— 以前没有上限，输入法里敲换行或长文本会把框撑高，
 * 把下面的按钮排和小触控板挤走甚至盖住。
 *
 * 删除：这里**没有**单独的删除按钮（用户明确说不用加那个 `⌫` 圆钮），退格完全交给系统输入法自己 ——
 * `onValueChange` 里「末尾删除」会按删掉的字符数补发 Backspace（见上）。
 *
 * 外观（本轮）：与上面「最近发送按键」那块卡片**同一套视觉** —— 同 16dp 圆角、同 `kbBg` 底色、
 * 同 `kbKeyBorder` 描边、同 14dp 内边距；唯一的差别是**高度**：这里锁死单行 32–40dp，永远长不高。
 */
@Composable
private fun SystemImeInputBar(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val colors = AirTouchTokens.colors
    // 零宽哨兵：框里**始终**留一个看不见的 \u200B。
    // 为什么需要它：`onValueChange` 只在文本真的变化时才触发 —— 框里空着时按退格不产生任何变化，
    // 于是一次 Backspace 都发不出去（用户反馈"空框时电脑上的字删不掉"）。留着哨兵，退格至少会让
    // 文本变化（哨兵被删掉），就能识别成"用户按了退格"并补发一次。
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL)) }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val old = value.text.replace(SENTINEL, "")     // 真实文本（不含哨兵）
            val rawNew = v.text
            val new = rawNew.replace(SENTINEL, "")
            // 哨兵必须在开头；被删掉了就补回来
            value = if (rawNew.startsWith(SENTINEL)) v else TextFieldValue(SENTINEL + new)
            // 组词中（拼音还没上屏）：一个字都不发，行为维持原样
            if (v.composition == null) {
                when {
                    // ① 变长 = 末尾追加：把多出来的真实字符发走；中间插字仍忽略（没法映射成按键）
                    new.length > old.length && new.startsWith(old) ->
                        onText(new.substring(old.length))
                    // ② 变短 = 末尾删除：每删掉一个真实字符补一次 Backspace（一次全选最多补 64 个）
                    new.length < old.length && old.startsWith(new) ->
                        repeat((old.length - new.length).coerceAtMost(64)) { onBackspace() }
                    // ③ 哨兵被删掉（真实文本长度没变）＝ 这次退格删的是哨兵本身，也算一次退格
                    !rawNew.startsWith(SENTINEL) ->
                        onBackspace()
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            // 外层的圆角矩形 + 描边由调用处统一画（和「最近发送按键」拼成同一个圆角矩形），
            // 这里只管尺寸：内容 24dp + 上下各 6dp = 总高 36dp（用户要求再矮一档），上下限都锁死 —— 永不伸长。
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .heightIn(min = 24.dp, max = 24.dp),
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            Box {
                if (value.text.replace(SENTINEL, "").isEmpty()) {
                    Text(
                        text = "点这里调出系统输入法（中文 / 粘贴文本）→ 直接发送到电脑",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/** [SystemImeInputBar] 的零宽哨兵：用户看不见，只为了让"空框按退格"也能产生一次文本变化。 */
private const val SENTINEL = "\u200B"

/**
 * 「最近发送按键」那一行的显示文本：把 `RedesignContract` 拼进 [RedesignUiState.lastKeyLog] 的
 * 「已发送: 」前缀截掉（用户要求只显示按键本身）。兼容半角/全角冒号。
 * 想从根上去掉的话，改 `RedesignContract.kt:196` 的 `"已发送: $combo"`。
 */
private fun keyLogText(raw: String): String =
    raw.removePrefix("已发送: ").removePrefix("已发送：")

@Preview(name = "05 · 竖屏键盘 (Light)", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun FlatKeyboardScreenPreview() {
    val (state, onAction) = rememberRedesignUiState()
    AirTouchTheme(darkTheme = state.darkTheme, accent = state.accent) {
        FlatKeyboardScreen(state = state, onAction = onAction)
    }
}
