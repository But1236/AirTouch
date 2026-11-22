package com.airtouch.app.net

import android.content.Context

/** App 版本号（`hello` 帧上报给电脑端，仅用于显示与排错）。 */
object AppVersion {

    /**
     * 读不到 `BuildConfig.VERSION_NAME` 时用的兜底值。
     * **必须与 `app/build.gradle.kts` 的 `versionName` 保持一致**（改版本号时两处一起改）。
     */
    private const val FALLBACK = "2.0.2"

    fun of(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: FALLBACK
    } catch (e: Exception) {
        FALLBACK
    }
}
