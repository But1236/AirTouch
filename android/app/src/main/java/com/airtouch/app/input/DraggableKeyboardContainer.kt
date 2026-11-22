package com.airtouch.app.input

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * 可以整体拖动的键盘容器。
 *
 * 全屏键盘铺满屏幕时，不同手掌大小、不同握持姿势需要的落点并不一样：把键盘往上挪一点，
 * 拇指就不必跨过整个屏幕去够最下面一排。所以容器整体可以用 `translationX/Y` 挪动。
 *
 * ## 两个拖动入口（工程要求的「拖拽边框或手柄」）
 * 1. **手柄**：通过 [dragHandle] 指定的 View（这里是顶部那条 取消全屏 / 手形图标 的标题条）。
 *    标题条上的按钮仍然是按钮 —— 触摸先给子 View，按钮消费掉了就轮不到拖动。
 * 2. **边框**：容器自身有内边距，落在内边距（也就是键盘四周那一圈）上的触摸由本容器的
 *    `onTouchEvent` 接收并变成拖动。落在键盘上的触摸被 [KeyboardView] 消费，因此按键优先。
 *
 * ## 为什么必须夹取（clamp）
 * 「取消全屏」按钮在这个容器里、跟着一起移动。如果能把容器拖出屏幕，这个唯一的出口就没了，
 * 用户只能杀掉 App。因此偏移被严格夹在「容器完整留在父容器内」的范围内。
 */
class DraggableKeyboardContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 拖动完成后回调（用于把位置存进设置） */
    var onDragFinished: ((x: Float, y: Float) -> Unit)? = null

    /** 拖动用的手柄；触摸落在它身上（且没被子 View 消费）时一律当作拖动 */
    var dragHandle: View? = null
        set(value) {
            field?.setOnTouchListener(null)
            field = value
            value?.setOnTouchListener { _, event -> handleDrag(event) }
        }

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 走到这里说明没有子 View 消费这次触摸 —— 也就是落在了容器自己的内边距（键盘四周的边框）上
        if (handleDrag(event)) return true
        return super.onTouchEvent(event)
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                // 拖动时父容器（沉浸式全屏的根布局）不得抢手势
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyTranslation(
                    startTranslationX + (event.rawX - downRawX),
                    startTranslationY + (event.rawY - downRawY),
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onDragFinished?.invoke(translationX, translationY)
                performClick()
                return true
            }
        }
        return dragging
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 直接设定偏移（恢复上次保存的位置），同样会夹取 */
    fun setTranslation(tx: Float, ty: Float) {
        applyTranslation(tx, ty)
    }

    /** 尺寸或父容器变化后重新夹取一次，避免旋转屏幕后键盘跑到屏幕外 */
    fun reclamp() {
        applyTranslation(translationX, translationY)
    }

    private fun applyTranslation(tx: Float, ty: Float) {
        val parentView = parent as? View
        val parentWidth = parentView?.width ?: 0
        val parentHeight = parentView?.height ?: 0
        if (parentWidth <= 0 || parentHeight <= 0 || width <= 0 || height <= 0) {
            // 还没完成布局：先记下来，onLayout 之后会重新夹取
            translationX = tx
            translationY = ty
            return
        }

        val minTx = -left.toFloat()
        val maxTx = (parentWidth - width - left).toFloat()
        translationX = tx.coerceIn(min(minTx, maxTx), max(minTx, maxTx))

        // 上下边界：容器必须完整留在父容器内。微型触控板气泡现在是容器**内部**的一部分
        // （容器会为了它向上长高），所以这里不再需要为气泡额外预留顶部空间。
        val minTy = -top.toFloat()
        val maxTy = (parentHeight - height - top).toFloat()
        translationY = ty.coerceIn(min(minTy, maxTy), max(minTy, maxTy))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) reclamp()
    }
}
