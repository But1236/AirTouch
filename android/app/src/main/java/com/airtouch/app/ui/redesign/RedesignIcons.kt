package com.airtouch.app.ui.redesign

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.airtouch.app.ui.redesign.theme.AirTouchTokens

/*
 * 蓝图中的 SVG 线性图标，用 Canvas 手绘转写，避免引入 material-icons-extended
 * （体积大、且与本次「严格按蓝图」无关）。全部为 currentColor 风格，取 [tint]。
 */

@Composable
fun BackArrowIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.62f, h * 0.20f)
            lineTo(w * 0.34f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.80f)
        }
        drawPath(
            p, tint,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
fun GearIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension * 0.44f
        // 8 个齿
        for (i in 0 until 8) {
            rotate(i * 45f, Offset(cx, cy)) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(cx - 1.1f.dp.toPx(), cy - outer - 1.2f.dp.toPx()),
                    size = Size(2.2f.dp.toPx(), 5f.dp.toPx()),
                    cornerRadius = CornerRadius(1f.dp.toPx()),
                )
            }
        }
        drawCircle(
            tint, outer * 0.66f, Offset(cx, cy),
            style = Stroke(width = 1.9f.dp.toPx()),
        )
        drawCircle(
            tint, outer * 0.24f, Offset(cx, cy),
            style = Stroke(width = 1.9f.dp.toPx()),
        )
    }
}

@Composable
fun FolderIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 2f.dp.toPx()
        val p = Path().apply {
            moveTo(w * 0.10f, h * 0.24f)
            lineTo(w * 0.38f, h * 0.24f)
            lineTo(w * 0.46f, h * 0.34f)
            lineTo(w * 0.88f, h * 0.34f)
            lineTo(w * 0.90f, h * 0.80f)
            lineTo(w * 0.10f, h * 0.80f)
            close()
        }
        drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun FullscreenIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        drawCornerBrackets(tint, 2.2f.dp.toPx(), inward = true)
    }
}

@Composable
fun ExitFullscreenIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        drawCornerBrackets(tint, 2.2f.dp.toPx(), inward = false)
    }
}

/** 四角括号：inward=true 向外展开（全屏），false 向内收拢（退出全屏）。 */
private fun DrawScope.drawCornerBrackets(color: Color, sw: Float, inward: Boolean) {
    val w = size.width
    val h = size.height
    val len = minOf(w, h) * 0.26f
    val style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val ox = w * 0.10f
    val oy = h * 0.10f
    val iw = w - ox * 2f
    val ih = h - oy * 2f

    fun corner(x: Float, y: Float, sx: Float, sy: Float) {
        val p = Path().apply {
            if (inward) {
                moveTo(x + sx * len, y); lineTo(x, y); lineTo(x, y + sy * len)
            } else {
                moveTo(x, y + sy * len); lineTo(x, y); lineTo(x + sx * len, y)
            }
        }
        drawPath(p, color, style = style)
    }
    corner(ox, oy, 1f, 1f)
    corner(ox + iw, oy, -1f, 1f)
    corner(ox, oy + ih, 1f, -1f)
    corner(ox + iw, oy + ih, -1f, -1f)
}

@Composable
fun ShieldCheckIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.success) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = 2.2f.dp.toPx()
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.86f, h * 0.22f)
            lineTo(w * 0.86f, h * 0.50f)
            quadraticBezierTo(w * 0.86f, h * 0.78f, w * 0.5f, h * 0.94f)
            quadraticBezierTo(w * 0.14f, h * 0.78f, w * 0.14f, h * 0.50f)
            lineTo(w * 0.14f, h * 0.22f)
            close()
        }
        drawPath(shield, tint, style = Stroke(width = sw, join = StrokeJoin.Round))
        val check = Path().apply {
            moveTo(w * 0.32f, h * 0.50f)
            lineTo(w * 0.45f, h * 0.63f)
            lineTo(w * 0.70f, h * 0.36f)
        }
        drawPath(check, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 扫码取景框里的简化 QR 字形（3 个定位角 + 若干随机点，蓝图里是 25% 不透明度）。 */
@Composable
fun QrGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val cell = w / 7f
        fun finder(cx: Int, cy: Int) {
            drawRect(tint, Offset(cx * cell, cy * cell), Size(cell * 3f, cell * 3f))
            drawRect(Color.Black, Offset((cx + 0.55f) * cell, (cy + 0.55f) * cell), Size(cell * 1.9f, cell * 1.9f))
            drawRect(tint, Offset((cx + 1f) * cell, (cy + 1f) * cell), Size(cell, cell))
        }
        finder(0, 0)
        finder(4, 0)
        finder(0, 4)
        val dots = listOf(
            4 to 4, 5 to 4, 4 to 5, 6 to 4, 4 to 6, 5 to 5, 6 to 6, 3 to 5, 5 to 3, 3 to 3, 6 to 5,
        )
        dots.forEach { (c, r) ->
            drawRect(tint, Offset(c * cell, r * cell), Size(cell * 0.82f, cell * 0.82f))
        }
    }
}

/** 自绘「食指指向」图标：食指竖起 + 中指/无名指/小指**蜷起等高** + 手掌，**不画拇指**，取代 👆 emoji。 */
@Composable
fun HandPointerIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val r = w * 0.075f
        val fingerW = w * 0.135f

        // 食指（竖起，最长的那根：点按/指向）
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.30f, h * 0.10f),
            size = Size(fingerW, h * 0.44f),
            cornerRadius = CornerRadius(r, r),
        )
        // 中指 / 无名指 / 小指：三根蜷起来，**顶端对齐、高度完全一致**（原来是一根比一根短，
        // 看着像伸开的巴掌；现在读作"蜷着的三根"）。小指照旧略细一点，但高度相同。
        val curlTop = h * 0.30f
        val curlH = h * 0.24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.46f, curlTop),
            size = Size(fingerW, curlH),
            cornerRadius = CornerRadius(r, r),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.62f, curlTop),
            size = Size(fingerW, curlH),
            cornerRadius = CornerRadius(r, r),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.78f, curlTop),
            size = Size(fingerW * 0.82f, curlH),
            cornerRadius = CornerRadius(r, r),
        )
        // 手掌（少了拇指那一笔，整体往左挪一点保持重心）
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.26f, h * 0.46f),
            size = Size(w * 0.64f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        )
    }
}

/** 键鼠模式图标：一根 I 形输入光标 + 一个指针箭头光标（自绘，不用 emoji）。 */
@Composable
fun MouseKeyboardModeIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.055f).coerceAtLeast(1.dp.toPx())

        // I 形输入光标（左上）
        val ibeamX = w * 0.24f
        drawLine(
            color = tint,
            start = Offset(ibeamX, h * 0.16f),
            end = Offset(ibeamX, h * 0.62f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(ibeamX - w * 0.075f, h * 0.16f),
            end = Offset(ibeamX + w * 0.075f, h * 0.16f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(ibeamX - w * 0.075f, h * 0.62f),
            end = Offset(ibeamX + w * 0.075f, h * 0.62f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )

        // 指针箭头光标（右下）：实心三角形 + 小尾巴
        val arrow = Path().apply {
            moveTo(w * 0.55f, h * 0.34f)
            lineTo(w * 0.55f, h * 0.86f)
            lineTo(w * 0.68f, h * 0.70f)
            lineTo(w * 0.78f, h * 0.90f)
            lineTo(w * 0.86f, h * 0.86f)
            lineTo(w * 0.76f, h * 0.66f)
            lineTo(w * 0.92f, h * 0.64f)
            close()
        }
        drawPath(arrow, tint)
    }
}

/** 远控模式图标：一台平板（圆角机身 + 屏幕 + 底部小圆点），取代 🖥️ emoji。 */
@Composable
fun RemoteModeIcon(modifier: Modifier = Modifier, tint: Color = AirTouchTokens.colors.textPrimary) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = (w * 0.055f).coerceAtLeast(1.dp.toPx())
        val bodyLeft = w * 0.14f
        val bodyTop = h * 0.10f
        val bodyW = w * 0.72f
        val bodyH = h * 0.80f
        val corner = w * 0.08f

        // 机身
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 屏幕内框
        drawRoundRect(
            color = tint.copy(alpha = 0.55f),
            topLeft = Offset(bodyLeft + w * 0.07f, bodyTop + h * 0.09f),
            size = Size(bodyW - w * 0.14f, bodyH - h * 0.22f),
            cornerRadius = CornerRadius(corner * 0.5f, corner * 0.5f),
            style = Stroke(width = sw * 0.7f, cap = StrokeCap.Round),
        )
        // 底部 Home 小圆点
        drawCircle(
            color = tint,
            radius = sw * 0.9f,
            center = Offset(bodyLeft + bodyW / 2f, bodyTop + bodyH - h * 0.065f),
        )
        // 顶部听筒/摄像头
        drawLine(
            color = tint,
            start = Offset(bodyLeft + bodyW * 0.42f, bodyTop + h * 0.045f),
            end = Offset(bodyLeft + bodyW * 0.58f, bodyTop + h * 0.045f),
            strokeWidth = sw * 0.7f,
            cap = StrokeCap.Round,
        )
    }
}
