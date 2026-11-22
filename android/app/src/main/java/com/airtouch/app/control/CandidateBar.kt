package com.airtouch.app.control

import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airtouch.app.R

/**
 * 候选条：组合串 + 可点的候选芯片（九键拼音、标点面板共用）。
 *
 * 为什么芯片是普通 [TextView] 而不是自绘 View：候选的数量、文字、宽度每次都不同（「你好」和
 * 「。」宽度差三倍），用 Android 现成的文本布局比再写一套逐字测量 + 换行的绘制代码可靠得多。
 * 自绘留给键帽那种**布局固定、数量固定**的东西（[com.airtouch.app.input.KeyboardView]）。
 *
 * 芯片 View 会**复用**：候选条在每按一个数字键时都会刷新，每次都新建 8 个 TextView 会持续产生
 * 垃圾；这里按需扩容、多余的一律设成 GONE。
 *
 * **显隐必须落在整条 [strip] 上**（而不是里面的两个子 View）：XML 里整条是 `gone`，
 * 只把子 View 设成 VISIBLE 是看不见的 —— 那个父容器仍然是 gone。
 *
 * @param onPick 点中第 [index] 个芯片。候选条不关心里程碑含义：是提交候选还是插入标点，
 *   由调用方（KeyboardFragment）根据自己当前的状态决定。
 */
class CandidateBar(
    private val strip: View,
    private val compositionView: TextView,
    private val scroller: HorizontalScrollView,
    private val chipContainer: LinearLayout,
    private val onPick: (index: Int, text: String) -> Unit,
) {

    /** 一条芯片。[dim] 用于「原样数字」这类次要候选，视觉上退到后面去 */
    data class Chip(val text: String, val dim: Boolean = false)

    private val pool = ArrayList<TextView>()

    /** 当前是否显示，避免每个数字键都白做一次布局 */
    private var visible = false

    private val colorText = ContextCompat.getColor(chipContainer.context, R.color.at_text)
    private val colorDim = ContextCompat.getColor(chipContainer.context, R.color.at_text_dim)
    private val colorAccent = ContextCompat.getColor(chipContainer.context, R.color.at_primary)

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        strip.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 显示一批芯片。
     *
     * @param label 左侧的组合串（如 `64'426`）；null 或空串时隐藏左侧文字
     * @param items 芯片；空列表时整条退化成「只有左侧文字」的状态
     * @param labelDim 左侧文字用次要色（用于「词库载入中」这类状态提示，而不是组合串）
     */
    fun show(label: String?, items: List<Chip>, labelDim: Boolean = false) {
        compositionView.text = label.orEmpty()
        compositionView.visibility = if (label.isNullOrEmpty()) View.GONE else View.VISIBLE
        compositionView.setTextColor(if (labelDim) colorDim else colorAccent)

        // 没有芯片时把滚动区收起来：否则「载入中…」左边会多出一块摸不到的空白
        scroller.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        for (i in items.indices) {
            val chip = items[i]
            val view = chipAt(i)
            view.text = chip.text
            view.setTextColor(if (i == 0 && !chip.dim) colorAccent else if (chip.dim) colorDim else colorText)
            view.visibility = View.VISIBLE
        }
        for (i in items.size until pool.size) {
            pool[i].visibility = View.GONE
        }
        // 每次刷新都回到最左边：第一个候选是最高频的，它必须永远在视野里
        scroller.scrollTo(0, 0)
    }

    private fun chipAt(index: Int): TextView {
        while (pool.size <= index) {
            val context = chipContainer.context
            val view = TextView(context).apply {
                setBackgroundResource(R.drawable.bg_at_button)
                textSize = 15f
                gravity = Gravity.CENTER
                val padH = (resources.displayMetrics.density * 10f).toInt()
                val padV = (resources.displayMetrics.density * 6f).toInt()
                setPadding(padH, padV, padH, padV)
                minWidth = (resources.displayMetrics.density * 40f).toInt()
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = (resources.displayMetrics.density * 6f).toInt()
                    marginEnd = 0
                }
                setOnClickListener { onPick(pool.indexOf(this), text.toString()) }
            }
            chipContainer.addView(view)
            pool.add(view)
        }
        return pool[index]
    }
}
