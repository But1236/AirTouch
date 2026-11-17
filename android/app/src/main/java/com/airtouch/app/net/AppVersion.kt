package com.airtouch.app.net

import android.content.Context

/** App 版本号（`hello` 帧上报给电脑端，仅用于显示与排错）。 */
object AppVersion {

    private const val FALLBACK = "2.0.0"

    fun of(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: FALLBACK
    } catch (e: Exception) {
        FALLBACK
    }
}
