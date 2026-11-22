package com.airtouch.app.net

import org.json.JSONObject

/**
 * 电脑端**当前实际生效**的缩放后端（协议 §2.2 的 `zoom_status`）。
 *
 * 为什么要把它同步到手机：手机只发「两指间距是几倍」的几何流，真正决定用哪个 Windows API
 * 的是电脑端。用户在手机设置页需要看到「已激活原生触控栈」这种确认，
 * 否则他没法判断电脑端到底走了哪条路 —— 所以电脑端每次状态变化都会主动推一帧过来。
 *
 * @param backend 生效后端，取 [Protocol.ZOOM_BACKEND_NATIVE] / [Protocol.ZOOM_BACKEND_WHEEL]
 * @param available 电脑端触控注入是否可用（`SM_MAXIMUMTOUCHES > 0` 且初始化成功）
 * @param reason 不可用时电脑端给出的中文说明（可用时为空串）
 * @param preference 电脑端当前的偏好（`auto` / `native` / `wheel`），用于回显开关状态
 * @param maxTouches `SM_MAXIMUMTOUCHES` 原值（0 = 没有触摸输入栈）
 * @param digitizer `SM_DIGITIZER` 原值
 */
data class ZoomStatus(
    val backend: String,
    val available: Boolean,
    val reason: String,
    val preference: String,
    val maxTouches: Int,
    val digitizer: Int
) {
    /** 是否正在走原生触控注入（`InjectTouchInput`） */
    val isNative: Boolean
        get() = backend == Protocol.ZOOM_BACKEND_NATIVE

    /**
     * 用户打开「原生丝滑缩放」开关时期望的偏好。开着 = `native`（优先原生，没有则回退），
     * 关掉 = `wheel`（强制滚轮，用来对比手感或躲开不兼容触摸屏的老软件）。
     */
    val wantsNative: Boolean
        get() = preference != Protocol.ZOOM_PREF_WHEEL

    companion object {

        /** 解析 `zoom_status` 帧 */
        fun fromStatus(o: JSONObject) = ZoomStatus(
            backend = o.optString(Protocol.FIELD_MODE, Protocol.ZOOM_BACKEND_WHEEL),
            available = o.optBoolean(Protocol.FIELD_AVAILABLE, false),
            reason = o.optString(Protocol.FIELD_REASON, ""),
            preference = o.optString(Protocol.FIELD_PREFERENCE, Protocol.ZOOM_PREF_AUTO),
            maxTouches = o.optInt(Protocol.FIELD_MAX_TOUCHES, 0),
            digitizer = o.optInt(Protocol.FIELD_DIGITIZER, 0)
        )

        /**
         * 解析 `welcome` 里的初始快照（协议 §2.2）。
         *
         * 握手包里冗余一份状态，是为了断线重连后设置页立刻就是对的，不用等第一帧 `zoom_status`；
         * 返回 null 表示这个服务端版本没带这些字段（老服务端），此时保留上一次的状态即可。
         */
        fun fromWelcome(o: JSONObject): ZoomStatus? {
            if (!o.has(Protocol.FIELD_ZOOM_MODE)) return null
            return ZoomStatus(
                backend = o.optString(Protocol.FIELD_ZOOM_MODE, Protocol.ZOOM_BACKEND_WHEEL),
                available = o.optBoolean(Protocol.FIELD_ZOOM_AVAILABLE, false),
                reason = o.optString(Protocol.FIELD_ZOOM_REASON, ""),
                preference = o.optString(Protocol.FIELD_ZOOM_PREFERENCE, Protocol.ZOOM_PREF_AUTO),
                maxTouches = 0,
                digitizer = 0
            )
        }
    }
}
