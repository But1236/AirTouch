package com.airtouch.app.net

/**
 * PC 侧对一次「画圆测试」的统计结果（协议 §2.4 的 `diag_report`）。
 *
 * @param received PC 实际收到的帧数
 * @param lost 按 seq 推算出的丢失帧数（seq 区间内没到的数量）
 * @param duplicated 重复帧数
 * @param outOfOrder 乱序帧数
 * @param maxGapMs PC 侧相邻两帧到达时间的最大间隔
 * @param meanGapMs PC 侧相邻两帧到达时间的平均间隔
 * @param durationMs PC 侧从 diag_begin 到 diag_report 的时长
 * @param clientSent 手机自报发送的帧数
 * @param clientElapsedMs 手机自报的耗时
 * @param clientMaxSendGapMs 手机自报的「两次发送之间最大间隔」——手机自己卡没卡，看它
 * @param sendInputFailures PC 侧 SendInput/SetCursorPos 被系统拒绝的次数（非 0 说明 PC 端丢了输入）
 * @param cursorUpdates PC 在测试期间**光标位置真正变化**的次数（PC 自己用 GetCursorPos 采样的）
 * @param cursorPollHz PC 采样线程的轮询频率（太低说明这个测量本身不可信）
 * @param cursorMaxGapMs PC 侧光标两次变化之间的最大间隔
 * @param displayHz PC 主显示器的刷新率 —— 「看起来」的物理上限
 */
data class ServerDiagReport(
    val testKind: String,
    val mode: String,
    val planned: Int,
    val received: Int,
    val firstSeq: Int,
    val lastSeq: Int,
    val lost: Int,
    val duplicated: Int,
    val outOfOrder: Int,
    val maxGapMs: Int,
    val meanGapMs: Int,
    val durationMs: Long,
    val clientSent: Int,
    val clientElapsedMs: Long,
    val clientMaxSendGapMs: Int,
    val targetHz: Int,
    val sendInputFailures: Long,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val cursorUpdates: Int,
    val cursorPollHz: Int,
    val cursorMaxGapMs: Int,
    val displayHz: Int
) {

    /** 手机说发了、PC 没收到 —— 链路（Wi-Fi/AP）丢帧的量化指标。 */
    val missing: Int get() = if (clientSent > received) clientSent - received else 0

    /** 实际到达率（PC 收到 / 手机自报发送）。 */
    val arrivalPercent: Double get() = if (clientSent <= 0) 0.0 else received * 100.0 / clientSent

    /** 实际帧率（按 PC 侧时长折算）。 */
    val actualHz: Double get() = if (durationMs <= 0) 0.0 else received * 1000.0 / durationMs

    /**
     * PC 侧光标每秒真正变化的次数 —— 这是**眼睛看到的**帧率，与「发送帧率」「到达帧率」都不一样。
     * 光标只能整物理像素跳，所以慢速移动时它必然低于发送频率。
     */
    val cursorHz: Int get() = if (durationMs <= 0) 0 else (cursorUpdates * 1000.0 / durationMs).toInt()

    /** 采样频率够高时 [cursorHz] 才有意义（PC 侧采样线程至少要 200 Hz）。 */
    val cursorSampleValid: Boolean get() = cursorPollHz >= 200

    /**
     * 一句话结论：卡顿到底出在手机、网络还是 PC 的输入注入。
     * 与 PC 端界面的判断逻辑保持一致（AirTouch.Server/MainWindow.xaml.cs 的 Verdict）。
     */
    fun verdict(): String = when {
        sendInputFailures > 0 ->
            "电脑拒绝了 $sendInputFailures 次输入注入（更高权限窗口 / 锁屏 / UAC）——「光标不动」不在手机也不在网络。"

        clientSent == 0 ->
            "手机一帧都没发出来，问题在手机端（界面卡死或线程被抢）。"

        missing > maxOf(2, clientSent / 20) ->
            "手机发出但电脑没收到 $missing 帧（${"%.1f".format(100.0 - arrivalPercent)}%），丢包在链路上（Wi-Fi / AP 隔离）。"

        clientMaxSendGapMs > 40 ->
            "手机侧出现 ${clientMaxSendGapMs}ms 的发送停顿，卡顿来自手机本身，不是网络。"

        maxGapMs > 40 ->
            "手机发得很稳（最大发送间隔 ${clientMaxSendGapMs}ms），但到达间隔出现 ${maxGapMs}ms 的空洞" +
                "（平均才 ${meanGapMs}ms）——帧在链路上被攒起来一起发，不是电脑丢帧。" +
                "已知成因：① Wi-Fi 省电/扫描把帧缓冲到 DTIM 一起发（改用 5GHz、关蓝牙，" +
                "并确认本页没有「Wi-Fi 低延迟锁未获取」提示）；② Nagle + 延迟 ACK（本版已给手机端开启 TCP_NODELAY）。" +
                "若仍复现，用 USB 网络共享跑同一次测试：空洞消失就证明是 Wi-Fi 空口的问题。"

        cursorSampleValid && cursorHz > 0 && cursorHz < cursorCeiling * 0.6 ->
            "帧都到了、间隔也平稳，但电脑侧光标实测只更新了 $cursorHz Hz（发送 $targetHz Hz，屏幕 $displayHz Hz）：" +
                "每次位移不足 1 物理像素，光标只能整像素跳。把目标频率降到 $cursorCeiling Hz 附近或调高灵敏度会更连续。"

        else ->
            "整条链路健康（无丢帧、间隔平稳、光标按帧更新）。"
    }

    /** 感知上的帧率上限 = min(发送频率, 屏幕刷新率) */
    val cursorCeiling: Int
        get() = when {
            displayHz <= 0 -> targetHz
            targetHz <= 0 -> displayHz
            else -> minOf(targetHz, displayHz)
        }
}
