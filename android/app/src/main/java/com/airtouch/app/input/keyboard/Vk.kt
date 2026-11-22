package com.airtouch.app.input.keyboard

/**
 * Win32 虚拟键码（Virtual-Key Code）。
 *
 * 这是协议 §2.6 里 `vk` 字段的取值来源，也是键盘布局与 Windows 之间**唯一**的对应关系：
 * 布局表里只写虚拟键码，不写任何显示文本之外的东西，因此新增自定义快捷键只需要往
 * [KeyboardLayouts] 的表格里加一行。
 *
 * ## 为什么左右修饰键要用区分码
 * `VK_SHIFT(0x10)` / `VK_CONTROL(0x11)` / `VK_MENU(0x12)` 是「不区分左右」的通用码，
 * `SendInput` 收到它们时会按下**左**边那个。完整 104 键布局里左右各有一个 Shift/Ctrl/Alt，
 * 用通用码会让四个键变成同一个键；因此这里一律使用 `VK_LSHIFT(0xA0)` 这类明确码。
 *
 * ## 扩展键（`KEYEVENTF_EXTENDEDKEY`）
 * 方向键、`Delete`、`PrintScreen`、右 Ctrl、Win、小键盘 `/` 等在 PS/2 协议里带 `0xE0` 前缀。
 * 手机端**不需要**维护这份名单：不传 `ext` 时服务端会按 vk 自行推断（协议 §2.6），
 * 只有小键盘回车这种「虚拟键码无法区分」的键位才需要在布局表里写 `extended = true`。
 */
object Vk {

    // ---- 编辑与空白 ----
    const val BACKSPACE = 0x08
    const val TAB = 0x09
    const val ENTER = 0x0D
    const val ESCAPE = 0x1B
    const val SPACE = 0x20

    // ---- 导航 ----
    const val PRIOR = 0x21 // Page Up
    const val NEXT = 0x22 // Page Down
    const val END = 0x23
    const val HOME = 0x24
    const val LEFT = 0x25
    const val UP = 0x26
    const val RIGHT = 0x27
    const val DOWN = 0x28

    // ---- 功能 ----
    const val SNAPSHOT = 0x2C // Print Screen
    const val INSERT = 0x2D
    const val DELETE = 0x2E
    const val CAPITAL = 0x14 // Caps Lock
    const val PAUSE = 0x13
    const val SCROLL = 0x91 // Scroll Lock

    // ---- 数字与字母（用辅助函数取，避免写 36 个常量） ----
    const val DIGIT_0 = 0x30
    const val DIGIT_9 = 0x39
    const val LETTER_A = 0x41
    const val LETTER_Z = 0x5A

    // ---- Windows 键 ----
    const val LWIN = 0x5B
    const val RWIN = 0x5C
    const val APPS = 0x5D // 菜单键（右键菜单）

    // ---- 小键盘 ----
    const val NUMPAD0 = 0x60
    const val NUMPAD9 = 0x69
    const val MULTIPLY = 0x6A
    const val ADD = 0x6B
    const val SUBTRACT = 0x6D
    const val DECIMAL = 0x6E
    const val DIVIDE = 0x6F
    const val NUMLOCK = 0x90

    // ---- F1~F12 ----
    const val F1 = 0x70
    const val F12 = 0x7B

    // ---- 左右修饰键 ----
    const val LSHIFT = 0xA0
    const val RSHIFT = 0xA1
    const val LCONTROL = 0xA2
    const val RCONTROL = 0xA3
    const val LMENU = 0xA4 // 左 Alt
    const val RMENU = 0xA5 // 右 Alt

    // ---- 美式布局的 OEM 键（同一个键上的两个字符靠 Shift 区分） ----
    const val OEM_1 = 0xBA // ; :
    const val OEM_PLUS = 0xBB // = +
    const val OEM_COMMA = 0xBC // , <
    const val OEM_MINUS = 0xBD // - _
    const val OEM_PERIOD = 0xBE // . >
    const val OEM_2 = 0xBF // / ?
    const val OEM_3 = 0xC0 // ` ~
    const val OEM_4 = 0xDB // [ {
    const val OEM_5 = 0xDC // \ |
    const val OEM_6 = 0xDD // ] }
    const val OEM_7 = 0xDE // ' "

    /** 数字 `0..9` 的虚拟键码 */
    fun digit(d: Int): Int = DIGIT_0 + d.coerceIn(0, 9)

    /** 字母的虚拟键码（大小写等价，虚拟键码本身不带 Shift 语义） */
    fun letter(c: Char): Int = LETTER_A + (c.uppercaseChar() - 'A').coerceIn(0, 25)

    /** 小键盘数字 `0..9` 的虚拟键码 */
    fun numpadDigit(d: Int): Int = NUMPAD0 + d.coerceIn(0, 9)

    /** `F1..F12` 的虚拟键码 */
    fun function(n: Int): Int = F1 + (n - 1).coerceIn(0, F12 - F1)
}
