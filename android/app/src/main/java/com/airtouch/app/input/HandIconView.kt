package com.airtouch.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.airtouch.app.R

/**
 * 「手形图标」按钮。
 *
 * 全屏打字时临时想微调一下光标，需要一个小到不会挡住键盘、又一眼能认出来的入口 —— 一只手掌
 * 是这个动作最直白的图标（和 MacBook 触控板、远程桌面类 App 的习惯一致）。
 *
 * ## 为什么是自绘而不是矢量图
 * 用 `VectorDrawable` 需要一段几十个坐标的 `pathData`，一个数字写错就会在 inflate 时抛异常；
 * 而手掌本来就是几个圆角矩形的组合（这也是 Material 的 `pan_tool` 图标的画法）。自绘既能保证
 * 一定能渲染出来，也让「点开气泡后变强调色」这种状态变化就是改一个颜色。
 *
 * 图形按「内容正方形的比例」摆放，因此按钮是方的还是扁的，手掌都不会变形。
 */
class HandIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 气泡是否已展开：展开时整只手变成强调色，用户一眼能看出当前状态 */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val colorNormal = ContextCompat.getColor(context, R.color.at_text)
    private val colorActive = ContextCompat.getColor(context, R.color.at_primary)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = if (wMode == MeasureSpec.UNSPECIFIED) dp(40f).toInt() else MeasureSpec.getSize(widthMeasureSpec)
        val h = if (hMode == MeasureSpec.UNSPECIFIED) dp(40f).toInt() else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val originX = (width - size) / 2f
        val originY = (height - size) / 2f
        fun x(fraction: Float) = originX + size * fraction
        fun y(fraction: Float) = originY + size * fraction
        fun r(fraction: Float) = size * fraction

        fillPaint.color = if (active) colorActive else colorNormal

        // 手掌
        roundRect(canvas, x(0.30f), y(0.46f), x(0.87f), y(0.93f), r(0.11f))

        // 四根手指：长度依次递减，和真实手掌一致
        fingerAt(canvas, x(0.38f), y(0.10f), r(0.115f), y(0.62f))
        fingerAt(canvas, x(0.525f), y(0.05f), r(0.115f), y(0.62f))
        fingerAt(canvas, x(0.67f), y(0.09f), r(0.115f), y(0.62f))
        fingerAt(canvas, x(0.80f), y(0.19f), r(0.10f), y(0.62f))

        // 拇指：绕掌心左下角转一下，才有「手」的斜度
        canvas.save()
        canvas.rotate(-32f, x(0.31f), y(0.62f))
        roundRect(canvas, x(0.19f), y(0.40f), x(0.31f), y(0.74f), r(0.055f))
        canvas.restore()
    }

    /** 一根手指：以 [centerX] 为中心、从 [topY] 伸到掌心内部（[bottomY]） */
    private fun fingerAt(canvas: Canvas, centerX: Float, topY: Float, radius: Float, bottomY: Float) {
        roundRect(canvas, centerX - radius, topY, centerX + radius, bottomY, radius)
    }

    private fun roundRect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        if (right - left <= 0f || bottom - top <= 0f) return
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
