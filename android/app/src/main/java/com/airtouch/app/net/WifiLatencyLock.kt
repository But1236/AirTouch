package com.airtouch.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * 连接期间抓住一个 Wi-Fi 高性能锁，避免无线电进入省电模式。
 *
 * ## 为什么需要它
 *
 * 手机在 Wi-Fi 省电（PSM/U-APSD）状态下会把下行/上行帧**攒起来一起发**：手机侧的「发送间隔」
 * 看起来很正常（每帧间隔 7ms），但服务端的「到达间隔」会抖到几十毫秒，于是电脑上的光标
 * 走一段停一下 —— 这正是「发送频率不低、看起来却不到 50 帧」最典型的原因。
 *
 * [WifiManager.WIFI_MODE_FULL_LOW_LATENCY]（API 29+）就是为这类延迟敏感场景提供的：
 * 屏幕亮着、前台有界面时，系统会让 Wi-Fi 保持低延迟状态。API < 29 退回
 * [WifiManager.WIFI_MODE_FULL_HIGH_PERF]。
 *
 * ## 安全性
 *
 * - 需要 `CHANGE_WIFI_STATE` / `WAKE_LOCK`（都是 normal 权限，安装即授予），但**任何设备/系统版本
 *   都可能拒绝**（例如没有 Wi-Fi、被省电策略忽略、企业设备策略），所以全程 try/catch：
 *   拿不到锁只是没有优化，绝不能因此崩溃或影响连接。
 * - 只在「已连接 + 界面在前台」时持有，`onPause`/断开时立刻释放，不会额外耗电。
 */
class WifiLatencyLock(context: Context) {

    private val appContext = context.applicationContext
    private var lock: WifiManager.WifiLock? = null

    /** 是否真的拿到了锁（诊断/日志用） */
    @Volatile
    var held: Boolean = false
        private set

    /** 最近一次失败原因（给界面/日志看，null 表示没失败过） */
    @Volatile
    var lastError: String? = null
        private set

    fun acquire() {
        if (held) return

        try {
            val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (manager == null) {
                lastError = "本机没有 Wi-Fi 服务"
                return
            }

            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }

            val created = manager.createWifiLock(mode, TAG)
            created.setReferenceCounted(false)
            created.acquire()
            lock = created
            held = created.isHeld
            lastError = null
            Log.i(TAG, "已获取 Wi-Fi 低延迟锁（mode=$mode，held=$held）")
        } catch (e: Exception) {
            // SecurityException / 厂商定制 ROM 的异常都在这里兜住：没有锁也能用，只是可能更抖
            held = false
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            Log.w(TAG, "获取 Wi-Fi 低延迟锁失败（不影响连接，只是可能更抖）", e)
        }
    }

    fun release() {
        val current = lock ?: return
        lock = null
        held = false
        try {
            if (current.isHeld) {
                current.release()
                Log.i(TAG, "已释放 Wi-Fi 低延迟锁")
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 Wi-Fi 低延迟锁失败", e)
        }
    }

    private companion object {
        const val TAG = "AirTouchWifi"
    }
}
