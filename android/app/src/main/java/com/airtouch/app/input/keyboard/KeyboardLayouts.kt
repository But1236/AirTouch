package com.airtouch.app.input.keyboard

import com.airtouch.app.input.keyboard.Keys.action
import com.airtouch.app.input.keyboard.Keys.digit
import com.airtouch.app.input.keyboard.Keys.function
import com.airtouch.app.input.keyboard.Keys.gap
import com.airtouch.app.input.keyboard.Keys.letter
import com.airtouch.app.input.keyboard.Keys.modifier
import com.airtouch.app.input.keyboard.Keys.numpad
import com.airtouch.app.input.keyboard.Keys.numpadEnter
import com.airtouch.app.input.keyboard.Keys.plain
import com.airtouch.app.input.keyboard.Keys.t9Digit

/**
 * 全部键盘布局的**数据表**。
 *
 * 这里只有键帽、权重与键码，没有一行绘制或发送代码 —— 要加自定义快捷键，往下面的行里加一个
 * [KeySpec]（或 [Keys] 里的工厂）即可，[com.airtouch.app.input.KeyboardView] 会自动画出来、
 * [KeyboardSession] 会自动按 [KeySpec.kind] 处理。
 *
 * ## 三个布局
 * - [QWERTY]：竖屏常规模式。数字行 + 三行字母区（含 Tab / CapsLock / Enter）+ 底部控制行。
 * - [T9]：竖屏九键模式。手机输入法那样的 3×4 数字/字母盘 + 一行功能键，配合候选条打中文。
 * - [FULL_104]：横屏全屏模式。美式 104 键（F1~F12 功能区 + 主键区 + 方向键区 + 小键盘区）。
 *
 * ## 三个刻意的设计选择
 * 1. **数字行的 `` ` `` 键同时印着 `~`**：这就是实体键盘上那个键（不按 Shift 出 `` ` ``，
 *    按住 Shift 出 `~`）。把键帽做成 `` ` `` /`~` 双标签比只写 `~` 更有用 —— 否则反引号在
 *    手机上将永远打不出来。布局表用 [KeySpec.subLabel] 承载上面那个字符。
 * 2. **只有 Ctrl 是粘滞的**：粘滞化的键单击之后什么都不发，等于「永远按不下去」。Ctrl 主要用在
 *    `Ctrl+C` 这种一气呵成的组合里，粘滞很顺手；而 Shift / Alt / Win 需要能单独按住
 *    （按住 Shift 再按字母打大写、按住 Win 当系统键），所以它们是普通按键。
 * 3. **九键不带修饰键行**：九键是**中文输入**通道，它的数字键根本不发按键帧（见
 *    [KeyKind.T9_DIGIT]）；要 Ctrl/Shift/Alt/Win 就按「英」切回 QWERTY，那里是完整的英文键盘。
 */
object KeyboardLayouts {

    const val DEFAULT_ID = "qwerty"

    // ------------------------------------------------------------------ 竖屏 QWERTY

    /**
     * 竖屏 QWERTY。
     *
     * 每行权重都凑到 12.0，因此各行的键宽完全一致（不像只按行归一化那样出现 5% 的参差）。
     * 三个 Shift（字母区左右各一个 + 控制行一个）用的是**同一个 `VK_LSHIFT`**：它们在电脑上就是
     * 同一个键，所以按住任意一个，另外两个也处于按下状态 —— 这正是实体键盘的行为。
     */
    val QWERTY: KeyboardLayout = KeyboardLayout(
        id = "qwerty",
        // 12 键一排，键宽只有屏宽的 1/12，行必须高一些才按得准（360dp 屏上约 46dp 一行）
        heightRatio = 0.67f,
        rows = listOf(
            // 数字行：~ ` 1..0 Backspace（数字上沿印着 Shift 之后的符号，和真实键帽一样）
            listOf(
                plain(Vk.OEM_3, "`", "~", weight = 0.75f),
                digit(1, "!"), digit(2, "@"), digit(3, "#"), digit(4, "$"), digit(5, "%"),
                digit(6, "^"), digit(7, "&"), digit(8, "*"), digit(9, "("), digit(0, ")"),
                plain(Vk.BACKSPACE, "⌫", weight = 1.25f, repeat = true),
            ),
            // 字母区第一行：Tab + QWERTYUIOP
            listOf(
                plain(Vk.TAB, "Tab", weight = 2f),
                letter('Q'), letter('W'), letter('E'), letter('R'), letter('T'),
                letter('Y'), letter('U'), letter('I'), letter('O'), letter('P'),
            ),
            // 字母区第二行：CapsLock + ASDFGHJKL + Enter
            listOf(
                plain(Vk.CAPITAL, "Caps", weight = 1.75f),
                letter('A'), letter('S'), letter('D'), letter('F'), letter('G'),
                letter('H'), letter('J'), letter('K'), letter('L'),
                plain(Vk.ENTER, "Enter", weight = 1.25f),
            ),
            // 字母区第三行：Shift + ZXCVBNM + Shift
            listOf(
                modifier(Vk.LSHIFT, "Shift", weight = 2.25f),
                letter('Z'), letter('X'), letter('C'), letter('V'), letter('B'),
                letter('N'), letter('M'),
                modifier(Vk.LSHIFT, "Shift", weight = 2.75f),
            ),
            // 底部控制行：只有 Ctrl 是粘滞的（见类注释）
            listOf(
                modifier(Vk.LCONTROL, "Ctrl", weight = 1.25f, sticky = true),
                modifier(Vk.LWIN, "Win", weight = 1.25f),
                modifier(Vk.LMENU, "Alt", weight = 1.25f),
                plain(Vk.SPACE, "Space", weight = 5.5f),
                modifier(Vk.LSHIFT, "Shift", weight = 2.75f),
            ),
        ),
    )

    // ------------------------------------------------------------------ 竖屏九键

    /**
     * 竖屏九键拼音。
     *
     * 前三行是手机输入法的 3×4 数字盘：主标签数字、副标签字母，**这些键不产生按键帧**，
     * 而是把数字交给拼音组合状态机（[KeyKind.T9_DIGIT]）。第四行是功能键。
     *
     * 第四行四个键宽度不等（[KeySpec.weight] = 1 / 1 / 2 / 1.4）：手机输入法的下排本来就是这样，
     * 各行独立归一化，所以前三行的三个大键与它并不对齐 —— 这是刻意的，不是疏漏。
     */
    val T9: KeyboardLayout = KeyboardLayout(
        id = "t9",
        // 一行只有 3~4 个键，键很宽，行高与 QWERTY 同一手感即可
        heightRatio = 0.67f,
        rows = listOf(
            // `1` 不是拼音键：它打开标点面板（标点用 text 帧发出去，见协议 §2.7）
            listOf(action(KeyAction.PUNCT, "1", "标点"), t9Digit(2, "abc"), t9Digit(3, "def")),
            listOf(t9Digit(4, "ghi"), t9Digit(5, "jkl"), t9Digit(6, "mno")),
            listOf(t9Digit(7, "pqrs"), t9Digit(8, "tuv"), t9Digit(9, "wxyz")),
            listOf(
                // 「英」= 切回 QWERTY（英文/数字/符号都在那边），不做老式多击字母
                action(KeyAction.SWITCH_LAYOUT, "英"),
                plain(Vk.ENTER, "↵"),
                plain(Vk.SPACE, "空格", weight = 2f),
                plain(Vk.BACKSPACE, "⌫", weight = 1.4f, repeat = true),
            ),
        ),
    )

    // ------------------------------------------------------------------ 功能栏里的 ↑ ↓

    // ------------------------------------------------------------------ 横屏完整 104 键

    /**
     * 美式 104 键。
     *
     * 每一行的权重都凑到 23.0：主键区 15 + 空隙 0.5 + 方向键区 3 + 空隙 0.5 + 小键盘区 4，
     * 因此三个区在每一行都严格对齐（这正是实体键盘的样子）。
     *
     * F 行高度只有其他行的 0.72：真实键盘上 F 行本来就矮一截，把高度还给定型的主键区更实用。
     *
     * ## 键数核对
     * | 区域 | 键数 |
     * | --- | --- |
     * | Esc + F1~F12 | 13 |
     * | 主键区其余（14+14+13+12+8） | 61 |
     * | PrintScreen/ScrollLock/Pause + 编辑键 6 + 方向键 4 | 13 |
     * | 小键盘（含 NumLock） | 17 |
     * | **合计** | **104** |
     */
    val FULL_104: KeyboardLayout = KeyboardLayout(
        id = "full104",
        // 23 个键宽单位：0.25 让 1 个单位的键帽接近方形（真实 104 键的比例），
        // 同时整块键盘只占横屏高度的 40% 左右，上方留得出提示与拖动空间
        heightRatio = 0.25f,
        rows = listOf(
            // ---- F 行：Esc、F1~F12、PrintScreen/ScrollLock/Pause（小键盘区上方留空）
            listOf(
                plain(Vk.ESCAPE, "Esc", weight = 2f),
                function(1), function(2), function(3), function(4),
                gap(0.5f),
                function(5), function(6), function(7), function(8),
                gap(0.5f),
                function(9), function(10), function(11), function(12),
                gap(0.5f),
                plain(Vk.SNAPSHOT, "prtsc"),
                plain(Vk.SCROLL, "scrlk"),
                plain(Vk.PAUSE, "pause"),
                gap(0.5f),
                gap(4f),
            ),
            // ---- 主键区第 1 行 + 编辑键区第 1 行 + 小键盘第 1 行
            listOf(
                plain(Vk.OEM_3, "`", "~"),
                digit(1, "!"), digit(2, "@"), digit(3, "#"), digit(4, "$"), digit(5, "%"),
                digit(6, "^"), digit(7, "&"), digit(8, "*"), digit(9, "("), digit(0, ")"),
                plain(Vk.OEM_MINUS, "-", "_"),
                plain(Vk.OEM_PLUS, "=", "+"),
                plain(Vk.BACKSPACE, "⌫", weight = 2f, repeat = true),
                gap(0.5f),
                plain(Vk.INSERT, "Ins"),
                plain(Vk.HOME, "Home"),
                plain(Vk.PRIOR, "PgUp"),
                gap(0.5f),
                plain(Vk.NUMLOCK, "Num"),
                plain(Vk.DIVIDE, "/"),
                plain(Vk.MULTIPLY, "*"),
                plain(Vk.SUBTRACT, "-"),
            ),
            // ---- 主键区第 2 行 + 编辑键区第 2 行 + 小键盘第 2 行
            listOf(
                plain(Vk.TAB, "Tab", weight = 1.5f),
                letter('Q'), letter('W'), letter('E'), letter('R'), letter('T'),
                letter('Y'), letter('U'), letter('I'), letter('O'), letter('P'),
                plain(Vk.OEM_4, "[", "{"),
                plain(Vk.OEM_6, "]", "}"),
                plain(Vk.OEM_5, "\\", "|", weight = 1.5f),
                gap(0.5f),
                plain(Vk.DELETE, "Del", repeat = true),
                plain(Vk.END, "End"),
                plain(Vk.NEXT, "PgDn"),
                gap(0.5f),
                numpad(7), numpad(8), numpad(9),
                plain(Vk.ADD, "+"),
            ),
            // ---- 主键区第 3 行 + 编辑键区留空 + 小键盘第 3 行
            listOf(
                plain(Vk.CAPITAL, "Caps", weight = 1.75f),
                letter('A'), letter('S'), letter('D'), letter('F'), letter('G'),
                letter('H'), letter('J'), letter('K'), letter('L'),
                plain(Vk.OEM_1, ";", ":"),
                plain(Vk.OEM_7, "'", "\""),
                plain(Vk.ENTER, "Enter", weight = 2.25f),
                gap(0.5f),
                gap(), gap(), gap(),
                gap(0.5f),
                numpad(4), numpad(5), numpad(6),
                gap(),
            ),
            // ---- 主键区第 4 行 + 方向键区 ↑ + 小键盘第 4 行
            listOf(
                modifier(Vk.LSHIFT, "Shift", weight = 2.25f),
                letter('Z'), letter('X'), letter('C'), letter('V'), letter('B'),
                letter('N'), letter('M'),
                plain(Vk.OEM_COMMA, ",", "<"),
                plain(Vk.OEM_PERIOD, ".", ">"),
                plain(Vk.OEM_2, "/", "?"),
                modifier(Vk.RSHIFT, "Shift", weight = 2.75f),
                gap(0.5f),
                gap(),
                plain(Vk.UP, "↑", repeat = true),
                gap(),
                gap(0.5f),
                numpad(1), numpad(2), numpad(3),
                numpadEnter(),
            ),
            // ---- 主键区第 5 行 + 方向键区 ←↓→ + 小键盘第 5 行
            listOf(
                modifier(Vk.LCONTROL, "Ctrl", weight = 1.25f, sticky = true),
                modifier(Vk.LWIN, "Win", weight = 1.25f),
                modifier(Vk.LMENU, "Alt", weight = 1.25f),
                plain(Vk.SPACE, "Space", weight = 6.25f),
                modifier(Vk.RMENU, "Alt", weight = 1.25f),
                modifier(Vk.RWIN, "Win", weight = 1.25f),
                plain(Vk.APPS, "≡"),
                modifier(Vk.RCONTROL, "Ctrl", weight = 1.25f, sticky = true),
                gap(0.5f),
                plain(Vk.LEFT, "←", repeat = true),
                plain(Vk.DOWN, "↓", repeat = true),
                plain(Vk.RIGHT, "→", repeat = true),
                gap(0.5f),
                numpad(0, weight = 2f),
                plain(Vk.DECIMAL, "."),
                gap(),
            ),
        ),
        rowHeights = listOf(0.72f, 1f, 1f, 1f, 1f, 1f),
    )

    /** 竖屏常规模式可以互相切换的布局，顺序即「切换」按钮的循环顺序 */
    val PORTRAIT: List<KeyboardLayout> = listOf(QWERTY, T9)

    private val BY_ID: Map<String, KeyboardLayout> =
        (PORTRAIT + FULL_104).associateBy { it.id }

    /** 按 id 取布局；id 不认识（旧版本存下的值 / 手工改坏）时退回 [DEFAULT_ID] */
    fun byId(id: String?): KeyboardLayout = BY_ID[id] ?: QWERTY

    /** id 是否指向本布局表里真实存在的布局 */
    fun isKnown(id: String?): Boolean = id != null && BY_ID.containsKey(id)

    /**
     * 竖屏布局的循环切换：QWERTY → 九键 → QWERTY。
     *
     * 全屏布局（[FULL_104]）不参与循环：它是横屏专用的形态，不是「另一种竖屏布局」。
     */
    fun nextPortraitId(current: String?): String {
        val index = PORTRAIT.indexOfFirst { it.id == current }
        return PORTRAIT[(index + 1).mod(PORTRAIT.size)].id
    }

    /** 布局在界面上的名字，用于提示文案 */
    fun displayName(id: String?): String = when (byId(id).id) {
        T9.id -> "九键拼音"
        FULL_104.id -> "全键盘"
        else -> "QWERTY"
    }

    /**
     * 功能栏与一字滑条用的**散键**。
     *
     * 它们不属于任何一张布局表（Esc / Del / prtsc 画在功能栏按钮上、←/→ 由一字滑条按方向发放），
     * 但仍然必须是 [KeySpec]：这样「按下一个键要发什么帧」「要不要临时按 Shift」
     * 依旧只有 [KeyboardSession] 一条代码路径，不会因为「这个键是按钮不是键盘画出来的」
     * 就多出一套发送逻辑。
     *
     * 竖屏没有 ↑/↓：一字滑条只做左右，上下方向键只在横屏 104 键里（主键区右边那一列方向区）。
     */
    object ToolKeys {
        val ESC = plain(Vk.ESCAPE, "Esc")

        /** 功能栏的 Del：长按不连发（那是按钮，不是键帽），所以 repeat 保持默认 false */
        val DELETE = plain(Vk.DELETE, "Del")

        /** 截图键。Windows 上单独按就是全屏截图 */
        val PRTSC = plain(Vk.SNAPSHOT, "prtsc")

        val ARROW_LEFT = plain(Vk.LEFT, "←")
        val ARROW_RIGHT = plain(Vk.RIGHT, "→")
    }
}
