package com.airtouch.app.input.keyboard

/**
 * 把键盘 UI 的语义事件翻译成协议 §2.6 的 `key` 帧序列。
 *
 * ## 为什么一次组合键是「四次独立的状态变化」
 * 协议里没有「组合键」消息，只有「一次按键状态变化」。`Ctrl+C` 在线上就是
 * `Ctrl↓ C↓ C↑ Ctrl↑` —— 和真实键盘产生的事件序列一模一样。这样做换来三件事：
 *
 * 1. **长按**能表达：`C` 抬起来之后 `Ctrl` 还按着，组合消息说不清这种状态；
 * 2. **服务端保持无状态**：不需要在电脑端复制一份「按顺序按下修饰键、再逆序松开」的状态机；
 * 3. **粘滞键天然属于客户端**：什么时候发修饰键的 down / up 完全由手机端决定。
 *
 * ## 谁负责按住、谁负责松开
 * [heldModifiers] 记录**本会话按下、且还没松开**的修饰键，它是唯一的事实来源：
 * - [StickyKeys.Lock.ONCE]：普通键按下时才真的按下修饰键，**该键抬起后**立刻松开并清空状态；
 * - [StickyKeys.Lock.LOCKED]：点击时立刻按下，一直按到用户再次点击；
 * - 需要 Shift 的符号键（`@` = Shift+2）：借用同一个机制临时按下 Shift，抬起时归还 ——
 *   如果用户自己已经把 Shift 按住了，就直接借用、绝不重复按下，也绝不多余地松开它。
 *
 * 只有**粘滞**修饰键（目前是 Ctrl）走上面这套状态机；Shift / Alt / Win 是 [KeySpec.sticky] 为
 * false 的普通修饰键，[pressKey]/[releaseKey] 对它们就是直接发 `down`/`up` ——
 * 用户要求它们能像真键盘那样被单独按住。
 *
 * [releaseAll] 是安全网：断开连接、切页、退出全屏时把**所有**还按着的东西松开。电脑端也会在
 * 会话结束时做同样的事（协议 §2.6「卡键保护」），两端都做才不会留下一个物理卡住的 Ctrl。
 *
 * @param send 发送一次按键状态变化：`(vk, down, extended)`。通常直接接到
 *   `ControlBridge.sendKey` 上；抽成函数是为了让本类不依赖 Android 与网络，也便于替换成记录器
 *   做验证。
 */
class KeyboardSession(
    private val send: (vk: Int, down: Boolean, extended: Boolean?) -> Unit,
    private val sticky: StickyKeys = StickyKeys(),
) {

    /** 已经按下、还没松开的修饰键（含粘滞锁定的与临时借用的 Shift） */
    private val heldModifiers = LinkedHashSet<Int>()

    /** 还处于按下状态的普通键：vk → 键定义。用于 [releaseAll] 兜底 */
    private val pressedKeys = LinkedHashMap<Int, KeySpec>()

    /** 为打出 `@`/`#` 这类符号而临时借用的 Shift：符号键 vk → 这次是否真的按下了 Shift */
    private val borrowedShift = HashMap<Int, Boolean>()

    /** 修饰键当前的粘滞状态，供 UI 高亮 */
    fun lockOf(vk: Int): StickyKeys.Lock = sticky.lockOf(vk)

    /** 当前处于粘滞激活状态的修饰键（按用户点选顺序），供界面提示「Ctrl 生效中」 */
    fun activeModifiers(): List<Int> = sticky.activeModifiers()

    /**
     * 用户点了一下**粘滞**修饰键（现在只有 Ctrl）。
     *
     * 返回新状态，方便调用方更新提示文案。
     */
    fun tapModifier(vk: Int): StickyKeys.Lock {
        val lock = sticky.tap(vk)
        when (lock) {
            StickyKeys.Lock.LOCKED -> holdModifier(vk)
            StickyKeys.Lock.NONE -> releaseModifier(vk)
            StickyKeys.Lock.ONCE -> {
                // 刻意不立刻按下：用户可能只是想清掉上一个状态，或者改主意了。
                // 真正的按下发生在下一个普通键按下时。
            }
        }
        return lock
    }

    /** 普通键按下 */
    fun pressKey(key: KeySpec) {
        if (!key.emitsKey || key.vk <= 0) return

        // 修饰键必须先在电脑上处于按下状态，随后到达的字母才会被当成组合键
        ensureStickyModifiersHeld()

        if (key.isModifier) {
            // 非粘滞的 Shift / Alt / Win 也记进 heldModifiers：这样「临时借用的 Shift」才能看到
            // 「用户自己已经按住了它」，既不会重复按下一次，也不会在借还时把它松开
            // （把用户按着的 Shift 松开，后面的字母就变成小写了）。
            holdModifier(key.vk)
            return
        }

        if (key.shift) {
            borrowedShift[key.vk] = holdModifier(Vk.LSHIFT)
        }

        pressedKeys[key.vk] = key
        send(key.vk, true, key.extended)
    }

    /** 普通键抬起 */
    fun releaseKey(key: KeySpec) {
        if (!key.emitsKey || key.vk <= 0) return

        if (key.isModifier) {
            // 松开 Shift/Alt/Win 只松开它自己：它是「配合按下」的键，不是一次组合的结尾，
            // 所以不能顺手把还等着用的「单次 Ctrl」清掉。
            releaseModifier(key.vk)
            return
        }

        pressedKeys.remove(key.vk)
        send(key.vk, false, key.extended)

        if (key.shift && borrowedShift.remove(key.vk) == true) {
            releaseModifier(Vk.LSHIFT)
        }

        // 单次锁定到此为止：组合键已经完整发出去了
        releaseConsumedOneShots()
    }

    /**
     * 长按连发的一次中间节拍。
     *
     * 刻意**不**复用 [releaseKey]/[pressKey]：那样会在每一次重复里误消费掉单次锁定的修饰键
     * （`Ctrl` 单次锁定 + 长按退格 会变成只删一个字）。这里只对这个键做一次「抬起 + 按下」。
     *
     * 为什么用「抬起再按下」而不是「一直按着靠 Windows 自己重复」：Windows 的键盘重复有一段
     * 不可控的初始延迟（默认 250~1000ms），而连发的手感需要立刻开始；而发送 key-up 会让 Windows
     * 把它的重复定时器清零，于是重复 rate 完全由手机端决定，不会出现「系统重复 + 手机重复」叠加。
     * 两次节拍之间键仍然处于按下状态，所以游戏里轮询按键状态依然能看到「一直按着」。
     */
    fun repeatTick(key: KeySpec) {
        if (!key.emitsKey || key.vk <= 0) return
        send(key.vk, false, key.extended)
        send(key.vk, true, key.extended)
    }

    /**
     * 松开所有还按着的东西并清空状态。
     *
     * 断开连接、键盘切到后台、退出全屏时都必须调用 —— 否则粘滞锁定的 Ctrl 会一直留在电脑上。
     */
    fun releaseAll() {
        // 先抬普通键（正常路径下 UI 会先发 ACTION_UP，这里只是兜底），再抬修饰键
        for ((vk, key) in pressedKeys.toList()) {
            send(vk, false, key.extended)
        }
        pressedKeys.clear()
        borrowedShift.clear()

        for (vk in heldModifiers.toList()) {
            send(vk, false, null)
        }
        heldModifiers.clear()

        sticky.clearAll()
    }

    // ---------------- 内部 ----------------

    /** @return 这次调用是否真的按下了它（已经按着则返回 false） */
    private fun holdModifier(vk: Int): Boolean {
        if (!heldModifiers.add(vk)) return false
        send(vk, true, null)
        return true
    }

    private fun releaseModifier(vk: Int) {
        if (heldModifiers.remove(vk)) {
            send(vk, false, null)
        }
    }

    private fun ensureStickyModifiersHeld() {
        for (vk in sticky.activeModifiers()) {
            holdModifier(vk)
        }
    }

    private fun releaseConsumedOneShots() {
        for (vk in sticky.activeModifiers()) {
            if (sticky.lockOf(vk) == StickyKeys.Lock.ONCE) {
                releaseModifier(vk)
                sticky.consumeOneShot(vk)
            }
        }
    }
}
