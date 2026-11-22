package com.airtouch.app.input.keyboard

/**
 * 一套键盘布局：若干行，每行若干 [KeySpec]。
 *
 * 行内按 [KeySpec.weight] 归一化分配宽度（每行都会铺满可用宽度），因此布局表不需要手工把每行的
 * 权重凑成同一个总数；视觉上各行的键宽会因此有极小的差异，这正是所有屏幕键盘的做法。
 *
 * @param id 稳定标识，会写进 `SettingsStore`（改名字等于丢掉用户选中的布局）
 * @param rows 从上到下的行
 * @param rowHeights 每行的高度权重；空列表表示所有行等高。104 键布局用它把 F 行压矮
 * @param heightRatio 键盘高度 / 键盘宽度。它是**布局的属性**而不是屏幕的属性：12 键一排的
 *   QWERTY 键窄、需要更高的行才按得准，5 键一排的九键键已经很宽、行要相应压矮，
 *   104 键铺满横屏时则要接近实体键盘的方形键帽。把比例放在布局数据里，同一份渲染代码
 *   才能同时把三种键盘都画得对（工程要求 3：布局数据与 UI 渲染分离）。
 */
data class KeyboardLayout(
    val id: String,
    val rows: List<List<KeySpec>>,
    val rowHeights: List<Float> = emptyList(),
    val heightRatio: Float = 0.66f,
) {
    val rowCount: Int get() = rows.size

    fun rowHeightWeight(rowIndex: Int): Float = rowHeights.getOrNull(rowIndex) ?: 1f

    /** 所有行高权重之和（即这个键盘有多少「行高单位」） */
    val rowWeightSum: Float
        get() {
            var sum = 0f
            for (i in rows.indices) sum += rowHeightWeight(i)
            return if (sum <= 0f) 1f else sum
        }

    /**
     * 这个布局在给定宽度下「应该」有多高。
     *
     * 竖屏键盘用它定高（避免键帽被拉成又窄又高的长条），横屏全屏键盘用它把 104 键画成
     * 接近实体键盘的比例。可用高度不够时由 View 自己再夹一次。
     */
    fun naturalHeightFor(widthPx: Float): Float = widthPx * heightRatio

    /** 该布局是否包含修饰键（决定要不要显示粘滞键说明） */
    val hasModifiers: Boolean
        get() = rows.any { row -> row.any { it.isModifier } }
}
