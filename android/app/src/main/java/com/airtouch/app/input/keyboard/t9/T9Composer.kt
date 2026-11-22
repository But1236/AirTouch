package com.airtouch.app.input.keyboard.t9

/**
 * 九键拼音的**组合状态机**：只管「用户按了哪几个数字、现在有哪些候选、选中之后要发出什么文本」，
 * 不管界面，也不管发送通道（由 `KeyboardFragment` 接上 `ControlBridge.sendText`）。
 *
 * 一轮组合的生命周期：
 * ```
 * 按 6 → digits="6"   候选「你/…」        显示 6
 * 按 4 → digits="64"  候选「你/尼/泥/…」   显示 64
 * 按 2 → digits="642" …
 * 按 ⌫ → 退掉一位数字（而不是删电脑上的字）
 * 点候选 / 按空格 → commit() 返回文本并清空组合
 * ```
 *
 * 组合清空之后，`⌫` 和`空格` 才回到「发按键帧给电脑」的默认含义 —— 这个切换点由
 * [com.airtouch.app.control.KeyboardFragment] 依据 [composing] 判断。
 */
class T9Composer {

    private var dictionary: PinyinDictionary? = null
    private var digits: String = ""
    private var candidates: List<PinyinEngine.Candidate> = emptyList()

    /** 词库是否已经加载完。还没加载完时组合仍然可以输入，只是没有候选 */
    val isReady: Boolean get() = dictionary != null

    /** 词库加载失败（资源缺失/损坏）：界面要如实告诉用户「中文候选不可用」 */
    val isDictionaryBroken: Boolean get() = dictionary?.isEmpty == true

    /** 是否正在组合拼音 */
    val composing: Boolean get() = digits.isNotEmpty()

    fun candidates(): List<PinyinEngine.Candidate> = candidates

    /** 候选条左边显示的组合串（按最优切分加分隔符，如 `64'426`）；没有组合时为空 */
    fun compositionLabel(): String {
        val dict = dictionary ?: return digits
        return PinyinEngine.compositionLabel(dict, digits)
    }

    /** 词库（后台加载完成后由 Fragment 注入）；重复注入不会打断正在进行的组合 */
    fun attach(dictionary: PinyinDictionary) {
        this.dictionary = dictionary
        refresh()
    }

    /**
     * 按下一个数字键。
     *
     * @return 组合是否发生了变化（界面据此刷新候选条）
     */
    fun onDigit(digit: Int): Boolean {
        if (digit < 0 || digit > 9) return false
        // 超过长度上限就忽略：再长下去既没有候选，也只是在浪费一次解析
        if (digits.length >= PinyinEngine.MAX_DIGITS) return false
        digits += digit.toString()
        refresh()
        return true
    }

    /**
     * 退格。
     *
     * @return true = 这一下被组合吃掉了（退掉一位数字）；false = 组合是空的，
     *   调用方应该把它当成真正的退格键发给电脑
     */
    fun onBackspace(): Boolean {
        if (digits.isEmpty()) return false
        digits = digits.dropLast(1)
        refresh()
        return true
    }

    /** 清空组合（切布局、键盘隐藏、连接断开时调用） */
    fun reset() {
        if (digits.isEmpty() && candidates.isEmpty()) return
        digits = ""
        candidates = emptyList()
    }

    /**
     * 上屏第 [index] 个候选。
     *
     * @return 要发给电脑的文本；没有可选内容时返回 null（调用方什么都不做）
     */
    fun commit(index: Int): String? {
        val text = candidates.getOrNull(index)?.text
        if (text.isNullOrEmpty()) return null
        digits = ""
        candidates = emptyList()
        return text
    }

    private fun refresh() {        val dict = dictionary
        candidates = if (dict == null || digits.isEmpty()) {
            emptyList()
        } else {
            PinyinEngine.candidates(dict, digits)
        }
    }
}
