package com.airtouch.app.input.keyboard

import android.content.Context
import com.airtouch.app.R

/**
 * 修饰键的中文名与状态提示文案。
 *
 * 竖屏键盘页和横屏全屏键盘都要显示「Ctrl 持续生效中：再点一下取消」这一句，所以文案拼装只写一遍。
 * 粘滞锁定是**看不见的模态状态** —— 不显示出来，用户只会觉得「键盘坏了，按 C 却复制了」。
 */
object ModifierNames {

    /** 修饰键的中文名；不是四个修饰键之一时返回 null */
    fun of(context: Context, vk: Int): String? = when (vk) {
        Vk.LCONTROL, Vk.RCONTROL -> context.getString(R.string.keyboard_modifier_ctrl)
        Vk.LSHIFT, Vk.RSHIFT -> context.getString(R.string.keyboard_modifier_shift)
        Vk.LMENU, Vk.RMENU -> context.getString(R.string.keyboard_modifier_alt)
        Vk.LWIN, Vk.RWIN -> context.getString(R.string.keyboard_modifier_win)
        else -> null
    }

    /**
     * 当前粘滞状态的提示句；没有任何修饰键激活时返回 `null`（调用方据此决定要不要显示这一行）。
     *
     * 持续锁定优先于单次锁定：它更值得被看见，因为接下来的每一次按键都会带上它。
     */
    fun statusText(context: Context, session: KeyboardSession): String? {
        val once = mutableListOf<String>()
        val locked = mutableListOf<String>()

        for (vk in session.activeModifiers()) {
            val name = of(context, vk) ?: continue
            if (session.lockOf(vk) == StickyKeys.Lock.LOCKED) locked.add(name) else once.add(name)
        }

        if (locked.isNotEmpty()) {
            return context.getString(R.string.keyboard_hint_modifier_locked, locked.joinToString(" + "))
        }
        if (once.isNotEmpty()) {
            return context.getString(R.string.keyboard_hint_modifier_once, once.joinToString(" + "))
        }
        return null
    }
}
