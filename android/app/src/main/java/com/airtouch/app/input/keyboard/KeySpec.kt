package com.airtouch.app.input.keyboard

/**
 * 按键的功能类别。决定「触摸事件怎么变成协议帧」，也决定 [com.airtouch.app.input.KeyboardView]
 * 怎么画它。
 */
enum class KeyKind {
    /** 普通按键：按下发 `KEY_DOWN`、抬起发 `KEY_UP`；[KeySpec.shift] 为 true 时会临时按下 Shift */
    PLAIN,

    /**
     * 修饰键（Ctrl / Shift / Alt / Win）。
     *
     * 具体是哪种行为由 [KeySpec.sticky] 决定：
     * - `sticky = true`（只有 Ctrl）：**不直接产生协议帧**，而是推进粘滞锁定状态机
     *   （单击=单次锁定、双击=持续锁定），由 [KeyboardSession] 决定什么时候真的按下/抬起它；
     * - `sticky = false`（Shift / Alt / Win）：就是普通的「按下发 down、抬起发 up」，
     *   因此可以真的按住它，再用另一根手指按别的键 —— 用户明确要求它们能单独按。
     */
    MODIFIER,

    /** 只在本机生效、不产生协议帧的动作键（切换 9 键 / QWERTY、全屏展开、标点面板） */
    ACTION,

    /**
     * 九键拼音的数字键：**不产生协议帧**，由 [com.airtouch.app.control.KeyboardFragment] 交给
     * 拼音组合状态机（[com.airtouch.app.input.keyboard.t9.T9Composer]）。
     *
     * 单独一类而不是复用 [PLAIN]：如果它是 PLAIN，[KeyboardSession] 就会把数字键当成真的按键
     * 发给电脑，九键立刻退化成数字小键盘。
     */
    T9_DIGIT,

    /** 空隙：只占宽度，不显示也不响应触摸（用来把 104 键的主键区、方向键区、小键盘区分开） */
    SPACER,
}

/** [KeyKind.ACTION] 类按键具体做什么。 */
enum class KeyAction {
    NONE,

    /** 在当前布局与下一个布局之间切换（竖屏：QWERTY ⇄ 九键） */
    SWITCH_LAYOUT,

    /** 进入 / 退出横屏全屏键盘 */
    FULLSCREEN,

    /** 九键的 `1` 键：在候选条上展开标点面板（标点本身不产生协议帧，选中后发 `text`） */
    PUNCT,
}

/**
 * 一个按键的完整描述 —— **布局数据与 UI 渲染分离**（工程要求 3）。
 *
 * 这里只有「这是什么键、显示什么、占多宽」，没有任何绘制代码；渲染在
 * [com.airtouch.app.input.KeyboardView] 里，发送逻辑在 [KeyboardSession] 里。
 * 想加一组自定义快捷键，只需要在 [KeyboardLayouts] 的表格里多写一个 [KeySpec]。
 *
 * @param vk Win32 虚拟键码（[Vk]）；[KeyKind.SPACER] / [KeyKind.ACTION] 不使用，填 0
 * @param label 键帽正面主标签
 * @param subLabel 键帽上沿的小标签 —— 真实键帽把 Shift 之后的字符印在上面（`` ` `` 上面是 `~`）
 * @param weight 宽度权重，同一行内按键宽按权重分配（行内会归一化到整行宽度）
 * @param kind 功能类别
 * @param shift 这一键在美式布局上需要按住 Shift 才能打出 [label]（如 `@` = Shift+2）
 * @param repeat 按住是否连发（退格 / 删除 / 方向键）
 * @param extended 显式指定 `KEYEVENTF_EXTENDEDKEY`；`null` = 交给服务端按 vk 推断（协议 §2.6）
 * @param action [KeyKind.ACTION] 的动作
 * @param sticky 修饰键是否走粘滞锁定状态机。只有 Ctrl 为 true —— Shift / Alt / Win 必须能像
 *   真键盘那样「按住不放」，粘滞化之后单击它们什么都不发，也就等于永远按不下去
 */
data class KeySpec(
    val vk: Int,
    val label: String,
    val subLabel: String? = null,
    val weight: Float = 1f,
    val kind: KeyKind = KeyKind.PLAIN,
    val shift: Boolean = false,
    val repeat: Boolean = false,
    val extended: Boolean? = null,
    val action: KeyAction = KeyAction.NONE,
    val sticky: Boolean = false,
) {
    /** 是否属于修饰键这一族（决定键帽底色） */
    val isModifier: Boolean get() = kind == KeyKind.MODIFIER

    /** 是否走粘滞锁定状态机：只有它才在抬手时触发 [com.airtouch.app.input.KeyboardView.Listener.onModifierTap] */
    val isStickyModifier: Boolean get() = kind == KeyKind.MODIFIER && sticky

    /** 是否会真的产生协议帧 */
    val emitsKey: Boolean get() = kind == KeyKind.PLAIN || (kind == KeyKind.MODIFIER && !sticky)

    /** 九键拼音的数字键 */
    val isT9Digit: Boolean get() = kind == KeyKind.T9_DIGIT

    /**
     * 是否把「按下 / 抬起 / 连发」三个阶段报给
     * [com.airtouch.app.input.KeyboardView.Listener.onKeyEvent]。
     *
     * 动作键走 `onAction`（抬手才触发）、空隙不参与触摸、粘滞修饰键只走 `onModifierTap`；
     * 其余全部上报 —— 包括不产生协议帧的九键数字键，因为「谁处理它们」是上层的事
     * （Fragment 的拦截器），View 不该替它们做决定。
     */
    val reportsPress: Boolean get() = when (kind) {
        KeyKind.PLAIN, KeyKind.T9_DIGIT -> true
        KeyKind.MODIFIER -> !sticky
        else -> false
    }

    /** 是否参与触摸命中（空隙不参与） */
    val isTouchable: Boolean get() = kind != KeyKind.SPACER
}

/**
 * [KeySpec] 的构造助手。
 *
 * 布局表本身很长，如果每个键都写全参数会淹没在样板里；这些工厂函数让
 * [KeyboardLayouts] 读起来接近一张「键帽图」。
 */
object Keys {

    fun plain(
        vk: Int,
        label: String,
        sub: String? = null,
        weight: Float = 1f,
        repeat: Boolean = false,
        shift: Boolean = false,
        extended: Boolean? = null,
    ) = KeySpec(
        vk = vk,
        label = label,
        subLabel = sub,
        weight = weight,
        kind = KeyKind.PLAIN,
        shift = shift,
        repeat = repeat,
        extended = extended,
    )

    /**
     * 需要 Shift 才能打出 [label] 的键（如 `@` = Shift+2、`~` = Shift+`` ` ``）。
     *
     * 按下时 [KeyboardSession] 会临时把左 Shift 按下去，抬起时再松开——除非用户自己已经把 Shift
     * 锁定了，那就直接借用，不会重复按下也不会误松开。
     */
    fun shifted(vk: Int, label: String, sub: String? = null, weight: Float = 1f) =
        plain(vk, label, sub, weight, shift = true)

    /**
     * 修饰键。
     *
     * @param sticky true = 粘滞锁定（只有 Ctrl）；false = 与普通键一样「按住即生效」，
     *   这样才能用一根手指按住 Shift、另一根手指敲字母打出大写
     */
    fun modifier(vk: Int, label: String, weight: Float = 1f, repeat: Boolean = false, sticky: Boolean = false) =
        KeySpec(
            vk = vk,
            label = label,
            weight = weight,
            kind = KeyKind.MODIFIER,
            repeat = repeat,
            sticky = sticky,
        )

    /** 本机动作键 */
    fun action(action: KeyAction, label: String, sub: String? = null, weight: Float = 1f) =
        KeySpec(
            vk = 0,
            label = label,
            subLabel = sub,
            weight = weight,
            kind = KeyKind.ACTION,
            action = action,
        )

    /** 空隙 */
    fun gap(weight: Float = 1f) = KeySpec(vk = 0, label = "", weight = weight, kind = KeyKind.SPACER)

    /** 字母键 */
    fun letter(c: Char, weight: Float = 1f) =
        plain(Vk.letter(c), c.uppercaseChar().toString(), weight = weight)

    /**
     * 主键盘数字行里的数字键。
     *
     * @param sub 按住 Shift 之后打出的符号（真实键帽就印在数字上方）。**不需要** [KeySpec.shift]：
     *   用户按住手机上的 Shift 再按数字键，电脑自己就会打出符号 —— 借用 Shift 只是为了
     *   「一次点击就出符号」，而数字键单击必须是数字。
     */
    fun digit(d: Int, sub: String? = null, weight: Float = 1f) =
        plain(Vk.digit(d), d.toString(), sub, weight = weight)

    /**
     * 九键拼音的数字键：主标签是数字、副标签是该键上的字母（`5` → `jkl`），
     * 就是手机输入法键帽的样子。它不产生协议帧（见 [KeyKind.T9_DIGIT]）。
     */
    fun t9Digit(digit: Int, letters: String, weight: Float = 1f) = KeySpec(
        vk = 0,
        label = digit.toString(),
        subLabel = letters,
        weight = weight,
        kind = KeyKind.T9_DIGIT,
    )

    /** 小键盘数字键 */
    fun numpad(d: Int, weight: Float = 1f) = plain(Vk.numpadDigit(d), d.toString(), weight = weight)

    /** 小键盘回车：与主回车共用 `VK_RETURN`，只能靠 `ext` 区分（协议 §2.6） */
    fun numpadEnter(weight: Float = 1f) =
        plain(Vk.ENTER, "Enter", weight = weight, extended = true)

    fun function(n: Int, weight: Float = 1f) = plain(Vk.function(n), "F$n", weight = weight)
}
