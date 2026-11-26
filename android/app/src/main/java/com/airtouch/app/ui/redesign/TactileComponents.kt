package com.airtouch.app.ui.redesign

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtouch.app.ui.redesign.theme.AirTouchColors
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * ============================================================================
 * 精密微拟物（Tactile Neumorphism）基础组件库
 * 适用：主页 / 设置 / 鼠标模式 / 触控板 / 开关 / 滑块
 * 严禁在竖屏键盘与横屏全屏键盘里使用本文件的组件（那里只用 FlatComponents.kt）。
 * ============================================================================
 *
 * 绘制策略（质感升级后，纯泥态融合）：
 *  - 面：Brush.linearGradient 145° 微弱色差（bg-surface-top → bg-surface-bottom）
 *         + 一层左上→右下淡出的曲面高光；
 *  - 外凸光影：两块 BlurMaskFilter 模糊圆角矩形（右下 shadow-dark / 左上 shadow-light），
 *    模糊半径与偏移都加大，光晕舒展；
 *  - 内凹光影：裁剪到圆角路径后，用两道 BlurMaskFilter 模糊描边模拟 CSS inset shadow；
 *  - **无显性灰色描边**：只保留顶部一道极淡的 border-subtle 内高光，
 *    按钮与背景完全靠亮部/暗部 + 渐变色差区分，不再有生硬的灰圈；
 *  - 按下：convex → concave 平滑过渡 + HapticFeedback + scale(0.975)。
 *
 * 备注：BlurMaskFilter 在软件渲染（含 Layoutlib 的 @Preview）下表现最完整；
 * 真机若 View 被硬件加速，模糊可能被 HWUI 丢弃，第二步可在宿主 View 上
 * setLayerType(LAYER_TYPE_SOFTWARE, null) 兜底。
 */

// ---------------------------------------------------------------------------
// 低层绘制原语
// ---------------------------------------------------------------------------

/** 用 BlurMaskFilter 在 nativeCanvas 上画一块柔和的实心圆角矩形（外凸光影）。 */
private fun DrawScope.drawBlurredRoundRect(
    rect: RectF,
    corner: Float,
    color: Color,
    blur: Float,
) {
    if (color.alpha <= 0f || blur <= 0f) return
    drawIntoCanvas { canvas ->
        val p = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            style = AndroidPaint.Style.FILL
            this.color = color.toArgb()
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawRoundRect(rect, corner, corner, p)
    }
}

/**
 * CSS `inset 4px 4px 9px shadow-dark, inset -4px -4px 9px shadow-light` 的等价实现：
 * 先裁剪到圆角路径，再画两道偏移的模糊描边 —— 只有内侧半边可见，即内凹阴影。
 */
private fun DrawScope.drawInnerShadows(
    rect: RectF,
    corner: Float,
    dark: Color,
    light: Color,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val strokeW = 12.dp.toPx()
    val blur = 9.dp.toPx()
    val off = 5.dp.toPx()
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val clip = AndroidPath().apply {
            addRoundRect(rect, corner, corner, AndroidPath.Direction.CW)
        }
        nc.save()
        nc.clipPath(clip)

        fun stroke(color: Color, dx: Float, dy: Float) {
            val p = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = strokeW
                this.color = color.copy(alpha = color.alpha * alpha).toArgb()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            nc.drawRoundRect(
                RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy),
                corner, corner, p,
            )
        }
        stroke(dark, off, off)
        stroke(light, -off, -off)
        nc.restore()
    }
}

/** 145° 线性渐变微曲面。 */
private fun DrawScope.drawGradientFace(rect: RectF, corner: Float, top: Color, bottom: Color) {
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(top, bottom),
            start = Offset(rect.left, rect.top),
            end = Offset(rect.right, rect.bottom),
        ),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width(), rect.height()),
        cornerRadius = CornerRadius(corner, corner),
    )
}

/** 计算一块「留了光影余量」的圆角面。internal：取景框的「镜头盖」要按同一个面来画中缝。 */
internal fun DrawScope.tactileFaceRect(corner: Dp): Pair<RectF, Float> {
    val e = AirTouchDimens.tactileEdge.toPx()
    val rect = RectF(e, e, (size.width - e).coerceAtLeast(e), (size.height - e).coerceAtLeast(e))
    val r = min(corner.toPx(), min(rect.width(), rect.height()) / 2f)
    return rect to r
}

/**
 * 统一的新拟物面绘制：press = 0 外凸 / press = 1 内凹。
 * [accentLit] 对应蓝图 `.neu-btn-accent`；[activeRing] 对应 `.neu-active-ring`。
 *
 * internal 是因为取景框的「镜头盖」（HomeScreen.ShutterClosedLens）要复用同一块面，
 * 保证它和按钮/卡片是同一份材质，而不是另画一个长得像的圆。
 */
internal fun DrawScope.drawTactileFace(
    colors: AirTouchColors,
    corner: Dp,
    press: Float,
    accentLit: Boolean,
    activeRing: Boolean,
) {
    val (rect, r) = tactileFaceRect(corner)
    val convex = 1f - press
    val blur = 10.dp.toPx()
    val off = 6.dp.toPx()

    // --- 外凸光影：左上亮部高光 + 右下暗部投影（按下时淡出） ---
    if (convex > 0f) {
        val dark = if (accentLit) colors.accentGlow else colors.shadowDark
        drawBlurredRoundRect(
            RectF(rect.left + off, rect.top + off, rect.right + off, rect.bottom + off),
            r,
            dark.copy(alpha = dark.alpha * convex),
            blur,
        )
        drawBlurredRoundRect(
            RectF(rect.left - off, rect.top - off, rect.right - off, rect.bottom - off),
            r,
            colors.shadowLight.copy(alpha = colors.shadowLight.alpha * convex),
            blur,
        )
        // 激活态用柔和强调色光晕，不再画硬朗的彩色描边
        if (activeRing) {
            drawBlurredRoundRect(
                RectF(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f),
                r + 2f,
                colors.accentGlow,
                12.dp.toPx(),
            )
        }
    }

    // --- 微弱表面渐变（按下时收敛到 bg-well） ---
    val top = lerp(
        if (accentLit) colors.accentLight else colors.bgSurfaceTop,
        if (accentLit) colors.accentDark else colors.bgWell,
        press,
    )
    val bottom = lerp(
        if (accentLit) colors.accentDark else colors.bgSurfaceBottom,
        if (accentLit) colors.accent else colors.bgWell,
        press,
    )
    drawGradientFace(rect, r, top, bottom)

    // --- 曲面高光：左上→右下淡出，营造泥态微凸起 ---
    if (convex > 0f) {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.14f * convex), Color.Transparent),
                start = Offset(rect.left, rect.top),
                end = Offset(rect.right, rect.bottom),
            ),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width(), rect.height()),
            cornerRadius = CornerRadius(r, r),
        )
    }

    // --- 内凹光影（按下时淡入） ---
    if (press > 0f) {
        drawInnerShadows(
            rect, r,
            dark = if (accentLit) Color(0x66000000) else colors.shadowDark,
            light = if (accentLit) Color(0x66FFFFFF) else colors.shadowLight,
            alpha = press,
        )
    }

    // --- 只保留顶部一道极淡内高光；整圈灰色 border 已移除 ---
    if (convex > 0f) {
        drawLine(
            color = colors.borderSubtle.copy(alpha = colors.borderSubtle.alpha * 0.45f * convex),
            start = Offset(rect.left + r * 0.6f, rect.top + 1.2f),
            end = Offset(rect.right - r * 0.6f, rect.top + 1.2f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** 内凹槽背景（供 NeumorphicWell / TactileSlider 轨道复用）。 */
private fun Modifier.neuWellBackground(colors: AirTouchColors, corner: Dp): Modifier = this.drawBehind {
    val w = size.width
    val h = size.height
    val r = min(corner.toPx(), min(w, h) / 2f)
    val rect = RectF(0f, 0f, w, h)
    drawRoundRect(color = colors.bgWell, cornerRadius = CornerRadius(r, r))
    drawInnerShadows(rect, r, colors.shadowDark, colors.shadowLight, 1f)
    // 顶部一道柔和内高光；凹槽外圈不再画任何灰色描边
    drawLine(
        color = colors.borderSubtle.copy(alpha = colors.borderSubtle.alpha * 0.55f),
        start = Offset(r * 0.6f, 1f),
        end = Offset(w - r * 0.6f, 1f),
        strokeWidth = 1.dp.toPx(),
    )
}

// ---------------------------------------------------------------------------
// 1. NeumorphicWell —— 内凹槽容器
// ---------------------------------------------------------------------------

/**
 * 外凹槽 / 内阴影容器，对应蓝图 `.neu-well`。
 * 主页扫码取景框、连接状态卡、分段控件外壳、触控板点阵区、滑块轨道都用它。
 */
@Composable
fun NeumorphicWell(
    modifier: Modifier = Modifier,
    corner: Dp = AirTouchDimens.radiusCard,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = AirTouchTokens.colors
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .neuWellBackground(colors, corner),
        contentAlignment = contentAlignment,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 2. TactileSurfaceButton —— 凸起微曲面按键
// ---------------------------------------------------------------------------

/**
 * 凸起按键：微弱渐变曲面 + 柔和亮/暗光影（无灰色描边）。
 * 按下时平滑切换为内凹、触发 HapticFeedback、轻微缩放。
 * 默认内边距已加大，按键饱满厚实，触控热区更大。
 * [accentLit] = `.neu-btn-accent`；[activeRing] = 柔和强调色光晕。
 */
@Composable
fun TactileSurfaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = AirTouchDimens.radiusBtn,
    accentLit: Boolean = false,
    activeRing: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AirTouchTokens.colors
    val haptics = rememberAirTouchHaptics()
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, label = "tactilePress")
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, label = "tactileScale")

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(onClick, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.tick()
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .drawWithContent {
                drawTactileFace(colors, corner, press, accentLit, activeRing)
                drawContent()
            }
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * **按住型**按键：按下立刻回调 `onHoldChange(true)`，松手（或被手势打断）回调 `false`。
 * 视觉 / 震动 / 按下内凹与 [TactileSurfaceButton] 完全一致，只是把「点击」拆成了「按下 / 抬起」。
 *
 * 用途：鼠标左键 / 右键的**拖拽**。轻点一下再松开 = 一次普通单击（电脑收到 down + up）；
 * 按住不放再用另一根手指滑（触控板页）或倾斜手机（鼠标页）= 拖动 —— 按住期间电脑上的左键
 * 必须一直处于按下态，所以这两步不能合并成一个 `click` 帧。
 *
 * [held] 由外部传入（控制器写回的 `state.heldButton`），手指还按着时页面重组也不会丢高亮；
 * 组件被移出组合（切页）时在 onDispose 里补一次抬起。
 *
 * 注意：`pointerInput` 只以 [enabled] 为 key，回调走 [rememberUpdatedState] —— 若把回调当 key，
 * 拖动过程中 `state.heldButton` 一变就会重组并重启手势检测，按下状态会被自己的重组打断。
 */
@Composable
fun TactileHoldButton(
    onHoldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = AirTouchDimens.radiusBtn,
    accentLit: Boolean = false,
    held: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AirTouchTokens.colors
    val haptics = rememberAirTouchHaptics()
    val currentHoldChange by rememberUpdatedState(onHoldChange)
    var pressed by remember { mutableStateOf(false) }
    val lit = pressed || held
    val press by animateFloatAsState(if (lit) 1f else 0f, label = "holdPress")
    val scale by animateFloatAsState(if (lit) 0.975f else 1f, label = "holdScale")

    // 按住时被移出组合（切页 / 退出）：onPress 之后的代码不会再执行，必须在这里补一次抬起
    DisposableEffect(Unit) {
        onDispose { if (pressed) currentHoldChange(false) }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        // 按住型 = 鼠标物理键：按下就是「拖拽开始」，用稍强的 click 档
                        haptics.click()
                        currentHoldChange(true)
                        tryAwaitRelease()
                        pressed = false
                        currentHoldChange(false)
                    },
                )
            }
            .drawWithContent {
                drawTactileFace(colors, corner, press, accentLit, activeRing = held)
                drawContent()
            }
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * 纯展示用的凸起曲面容器（蓝图 `.neu-btn ... cursor-default`）——不带点击/震动。
 * 设置页的每一行设置项用它做外壳，避免误触。
 */
@Composable
fun TactileSurface(
    modifier: Modifier = Modifier,
    corner: Dp = AirTouchDimens.radiusBtn,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AirTouchTokens.colors
    Box(
        modifier = modifier
            .drawWithContent {
                drawTactileFace(colors, corner, 0f, accentLit = false, activeRing = false)
                drawContent()
            }
            .padding(contentPadding),
        content = content,
    )
}

/** 圆形图标按钮的便捷封装（主页设置齿轮、返回、暂停、传文件等）。 */
@Composable
fun TactileCircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    accentLit: Boolean = false,
    activeRing: Boolean = false,
    content: @Composable () -> Unit,
) {
    TactileSurfaceButton(
        onClick = onClick,
        modifier = modifier.size(size),
        corner = AirTouchDimens.radiusPill,
        accentLit = accentLit,
        activeRing = activeRing,
        contentPadding = PaddingValues(0.dp),
    ) { content() }
}

// ---------------------------------------------------------------------------
// 3. TactileToggleSwitch —— 带防滑纹路的曲面滑块
// ---------------------------------------------------------------------------

/** 开关滑块：外凹槽轨道 + 带两条防滑纹路的曲面圆形滑块。 */
@Composable
fun TactileToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AirTouchTokens.colors
    val haptics = rememberAirTouchHaptics()
    val density = LocalDensity.current
    val travelPx = with(density) { 32.dp.toPx() }
    val offsetPx by animateFloatAsState(
        targetValue = if (checked) travelPx else 0f,
        label = "tactileSwitchThumb",
    )

    Box(
        modifier = modifier
            .size(width = 72.dp, height = 40.dp)
            .clip(RoundedCornerShape(50))
            .neuWellBackground(colors, AirTouchDimens.radiusPill)
            .pointerInput(checked, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = {
                    haptics.tick()
                    onCheckedChange(!checked)
                })
            }
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .size(32.dp)
                .drawWithContent {
                    drawToggleThumb(colors, checked)
                    drawContent()
                },
            contentAlignment = Alignment.Center,
        ) {
            // 防滑纹路：两条 1.5dp × 10dp 竖纹（蓝图 ::before / ::after）
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val ridge = Modifier
                    .size(width = 1.5.dp, height = 12.dp)
                    .background(Color.Black.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
                Box(ridge)
                Box(ridge)
            }
        }
    }
}

private fun DrawScope.drawToggleThumb(colors: AirTouchColors, checked: Boolean) {
    val w = size.width
    val h = size.height
    val r = min(w, h) / 2f
    val rect = RectF(0f, 0f, w, h)
    val blur = 5.dp.toPx()
    drawBlurredRoundRect(
        RectF(2f, 2f, w + 2f, h + 2f), r,
        if (checked) colors.accentGlow else colors.shadowDark, blur,
    )
    drawBlurredRoundRect(
        RectF(-2f, -2f, w - 2f, h - 2f), r,
        colors.shadowLight, blur,
    )
    drawGradientFace(
        rect, r,
        if (checked) colors.accentLight else colors.bgSurfaceTop,
        if (checked) colors.accentDark else colors.bgSurfaceBottom,
    )
    // 顶部极淡高光弧，取代整圈描边
    drawLine(
        color = Color.White.copy(alpha = 0.42f),
        start = Offset(r * 0.7f, 1.4f),
        end = Offset(w - r * 0.7f, 1.4f),
        strokeWidth = 1.dp.toPx(),
    )
}

// ---------------------------------------------------------------------------
// 4. TactileSlider —— 内凹轨道滑块
// ---------------------------------------------------------------------------

/**
 * 灵敏度滑块（第 3 项重构）：**纯新拟物内嵌圆柱进度条**。
 * 外框是圆角内凹长条槽（无任何灰色描边），内部直接用强调色填充的**立体圆角圆柱体**
 * 表示当前比例（上亮下暗的竖直渐变 + 顶部高光 + 底部暗边 + accentGlow 光晕）。
 * 已彻底删除原来突出于轨道的圆球 Thumb；槽内点击 / 左右拖动即可平滑调节。
 */
@Composable
fun TactileSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0.5f..3f,
) {
    val colors = AirTouchTokens.colors
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)

    fun updateFromX(x: Float) {
        val f = (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        onValueChange(valueRange.start + f * (valueRange.endInclusive - valueRange.start))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AirTouchDimens.sliderTrackHeight)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(valueRange) {
                detectTapGestures { pos -> updateFromX(pos.x) }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ ->
                    updateFromX(change.position.x)
                    change.consume()
                }
            }
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = h / 2f
                val track = RectF(0f, 0f, w, h)

                // 1. 内凹槽：底色 + inset 阴影 + 顶部柔和内高光，无描边
                drawRoundRect(color = colors.bgWell, cornerRadius = CornerRadius(r, r))
                drawInnerShadows(track, r, colors.shadowDark, colors.shadowLight, 1f)
                drawLine(
                    color = colors.borderSubtle.copy(alpha = colors.borderSubtle.alpha * 0.5f),
                    start = Offset(r * 0.7f, 1f),
                    end = Offset(w - r * 0.7f, 1f),
                    strokeWidth = 1.dp.toPx(),
                )

                // 2. 强调色立体圆柱填充
                val inset = 4.dp.toPx()
                val maxFill = (w - inset * 2f).coerceAtLeast(0f)
                val fillW = maxFill * fraction
                if (fillW > 2f) {
                    val fill = RectF(inset, inset, inset + fillW, h - inset)
                    val fr = fill.height() / 2f
                    drawBlurredRoundRect(fill, fr, colors.accentGlow, 9.dp.toPx())
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(colors.accentLight, colors.accent, colors.accentDark),
                            startY = fill.top,
                            endY = fill.bottom,
                        ),
                        topLeft = Offset(fill.left, fill.top),
                        size = Size(fill.width(), fill.height()),
                        cornerRadius = CornerRadius(fr, fr),
                    )
                    // 圆柱顶部高光带
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.38f),
                        topLeft = Offset(fill.left + fr * 0.8f, fill.top + 1.5f),
                        size = Size((fill.width() - fr * 1.6f).coerceAtLeast(0f), 1.5.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                    )
                    // 圆柱底部暗边
                    drawLine(
                        color = Color.Black.copy(alpha = 0.16f),
                        start = Offset(fill.left + fr * 0.8f, fill.bottom - 1.5f),
                        end = Offset(fill.right - fr * 0.8f, fill.bottom - 1.5f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            },
    )
}

// ---------------------------------------------------------------------------
// 5. TactileSegmentedControl —— 分段控件（鼠标 / 触控板 / 键盘）
// ---------------------------------------------------------------------------

/**
 * 第 4 项重构：**一体式内凹长胶囊背板 + 高亮立体滑块**。
 * 三个文字直接印在背板上作为底板的一部分 —— 没有独立小按钮、没有任何间隙或分隔缝；
 * 上方只覆盖一个强调色立体圆角滑块，点击或左右滑动时用 animateDpAsState 弹簧动画
 * 在三个文字之间平滑滑动。整体高度加大到 AirTouchDimens.segmentedHeight。
 */
@Composable
fun TactileSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    val count = items.size.coerceAtLeast(1)
    val safeIndex = selectedIndex.coerceIn(0, count - 1)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(AirTouchDimens.segmentedHeight)
            .clip(RoundedCornerShape(AirTouchDimens.segmentedCorner))
            .neuWellBackground(colors, AirTouchDimens.segmentedCorner)
            .pointerInput(count) {
                detectTapGestures(onTap = { pos ->
                    val idx = (pos.x / size.width * count).toInt().coerceIn(0, count - 1)
                    onSelected(idx)
                })
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    val idx = (change.position.x / size.width * count).toInt().coerceIn(0, count - 1)
                    onSelected(idx)
                    change.consume()
                }
            },
    ) {
        val pad = 5.dp
        val segmentW = (maxWidth - pad * 2) / count
        val animatedOffset by animateDpAsState(
            targetValue = pad + segmentW * safeIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "tactileSegmentThumb",
        )

        // 覆盖在底板上的强调色立体滑块
        Box(
            modifier = Modifier
                .offset(x = animatedOffset, y = (AirTouchDimens.segmentedHeight - AirTouchDimens.segmentedThumbHeight) / 2)
                .width(segmentW)
                .height(AirTouchDimens.segmentedThumbHeight)
                .drawWithContent {
                    drawSegmentThumb(colors)
                    drawContent()
                },
        )

        // 文字层：直接印在背板上，无按钮、无间隙
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (index == safeIndex) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (index == safeIndex) colors.accentText else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** 分段控件的强调色立体圆角滑块（竖直渐变 + 顶部高光 + 柔和光晕）。 */
private fun DrawScope.drawSegmentThumb(colors: AirTouchColors) {
    val w = size.width
    val h = size.height
    val r = min(w, h) / 2f
    val rect = RectF(3f, 3f, w - 3f, h - 3f)
    drawBlurredRoundRect(
        RectF(rect.left + 3f, rect.top + 4f, rect.right + 3f, rect.bottom + 4f),
        r, colors.accentGlow, 9.dp.toPx(),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(colors.accentLight, colors.accent, colors.accentDark),
            startY = rect.top,
            endY = rect.bottom,
        ),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width(), rect.height()),
        cornerRadius = CornerRadius(r, r),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.42f),
        start = Offset(rect.left + r * 0.6f, rect.top + 1.5f),
        end = Offset(rect.right - r * 0.6f, rect.top + 1.5f),
        strokeWidth = 1.dp.toPx(),
    )
}

// ---------------------------------------------------------------------------
// 6. 背景修饰：点阵 / 滚轮防滑纹
// ---------------------------------------------------------------------------

/** 蓝图 `.dot-matrix-bg`：16dp 网格、1.45dp 半径、偏移 8dp。 */
@Composable
fun Modifier.dotMatrixBackground(): Modifier {
    val dot = AirTouchTokens.colors.dotColor
    return this.drawBehind {
        val spacing = AirTouchDimens.dotSpacing.toPx()
        val radius = AirTouchDimens.dotRadius.toPx()
        var y = AirTouchDimens.dotOffset.toPx()
        while (y < size.height) {
            var x = AirTouchDimens.dotOffset.toPx()
            while (x < size.width) {
                drawCircle(dot, radius, Offset(x, y))
                x += spacing
            }
            y += spacing
        }
    }
}

/** 蓝图 `.scroll-wheel-ribs` 静态版（触控板滚轮键用）。 */
fun Modifier.ribbedScrollWheel(): Modifier = this.drawBehind { drawRibs(0f) }

/**
 * 第 2 项：可跟随滚轮手势实时滚动的防滑刻度纹路。
 * 传入 State<Float> 而不是裸 Float —— drawBehind 内部读 .value，拖动时只重绘不重组。
 */
@Composable
fun Modifier.ribbedScrollWheel(scrollOffset: State<Float>): Modifier = this.drawBehind {
    drawRibs(scrollOffset.value)
}

private fun DrawScope.drawRibs(offset: Float) {
    val band = 6.dp.toPx()
    val light = 2.dp.toPx()
    // 用取模把偏移折进一个条纹周期内，纹路就会随手势连续滚动
    val start = -(((offset % band) + band) % band)
    var y = start
    while (y < size.height) {
        drawRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, light))
        drawRect(
            Color.Black.copy(alpha = 0.12f),
            Offset(0f, y + light),
            Size(size.width, band - light),
        )
        y += band
    }
}

// ---------------------------------------------------------------------------
// 7. TactileScrollWheel —— 真实鼠标滚轮形状的拟态圆柱
// ---------------------------------------------------------------------------

/**
 * 跟实际滚轮一样的拟态形状：两端半圆的胶囊圆柱，横向明暗渐变模拟圆柱受光（左暗 → 中亮 → 右暗），
 * 表面叠横向防滑肋纹；纹路随 [scrollOffset] 实时滚动，按下时由外凸平滑转为内凹并触发震动。
 */
@Composable
fun TactileScrollWheel(
    scrollOffset: State<Float>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = AirTouchTokens.colors
    val haptics = rememberAirTouchHaptics()
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 1f else 0f, label = "scrollWheelPress")

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AirTouchDimens.radiusPill))
            .graphicsLayer {
                scaleX = 1f - press * 0.02f
                scaleY = 1f - press * 0.02f
            }
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.tick()
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .drawWithContent {
                drawCylinderWheel(colors, press)
                drawRibs(scrollOffset.value)
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) { }
}

/** 圆柱受光面：横向渐变 + 顶部高光 + 外凸/内凹光影，无灰色描边。 */
private fun DrawScope.drawCylinderWheel(colors: AirTouchColors, press: Float) {
    val w = size.width
    val h = size.height
    val edge = 4.dp.toPx()
    val rect = RectF(edge, edge, (w - edge).coerceAtLeast(edge), (h - edge).coerceAtLeast(edge))
    val r = min(rect.width(), rect.height()) / 2f
    val convex = 1f - press

    if (convex > 0f) {
        drawBlurredRoundRect(
            RectF(rect.left + 4f, rect.top + 5f, rect.right + 4f, rect.bottom + 5f), r,
            colors.shadowDark.copy(alpha = colors.shadowDark.alpha * convex), 8.dp.toPx(),
        )
        drawBlurredRoundRect(
            RectF(rect.left - 4f, rect.top - 4f, rect.right - 4f, rect.bottom - 4f), r,
            colors.shadowLight.copy(alpha = colors.shadowLight.alpha * convex), 8.dp.toPx(),
        )
    }

    // 圆柱曲面：左缘暗 → 中间亮 → 右缘暗
    val darkEdge = lerp(colors.bgSurfaceBottom, colors.bgWell, 0.35f)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(darkEdge, colors.bgSurfaceTop, colors.bgSurfaceBottom, darkEdge),
            startX = rect.left,
            endX = rect.right,
        ),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width(), rect.height()),
        cornerRadius = CornerRadius(r, r),
    )

    drawLine(
        color = Color.White.copy(alpha = 0.18f * convex),
        start = Offset(rect.left + r * 0.6f, rect.top + 1.dp.toPx()),
        end = Offset(rect.right - r * 0.6f, rect.top + 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )

    if (press > 0f) {
        drawInnerShadows(rect, r, colors.shadowDark, colors.shadowLight, press)
    }
}
