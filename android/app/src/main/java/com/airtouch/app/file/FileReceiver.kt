package com.airtouch.app.file

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * 服务端 → 手机 的文件接收器。
 *
 * 协议 §3 与 §3.5 要点：
 * - 先写临时文件 `<name>.part`，累计字节数 == fileSize 时才落盘为最终文件名；
 * - 收 `file_end` 时字节数不等 → 判定 error 并删除 `.part`；
 * - 连接中断 → 删除 `.part`；超过 fileSize 的多余字节 → error，多余字节丢弃不写盘；
 * - 保存到系统「下载」目录：API ≥ 29 用 MediaStore.Downloads，
 *   API < 29 直接写公共下载目录（需要 WRITE_EXTERNAL_STORAGE，manifest 里限 maxSdkVersion=28）；
 * - fileName 做与 §3.4 同等强度的清洗（去掉路径分隔符、`..`、非法字符）。
 *
 * 线程模型：所有方法都在 OkHttp 读线程上调用，因此内部只有一个写入者，无需额外加锁；
 * 但 [onCompleted]/[onFailed]/[onProgress] 回调也在同一线程触发，界面层需自行切主线程。
 */
class FileReceiver(context: Context) {

    interface Callback {
        fun onProgress(fileName: String, received: Long, total: Long)
        fun onCompleted(fileName: String, savedPath: String, bytes: Long)
        fun onFailed(fileName: String, reason: String)
    }

    /** 只持有 ApplicationContext，避免泄漏 Activity */
    private val context: Context = context.applicationContext

    private var callback: Callback? = null
    private var displayName: String = ""
    private var totalBytes: Long = 0L
    private var receivedBytes: Long = 0L
    private var fileId: String? = null

    private var partFile: File? = null
    private var out: OutputStream? = null
    private var finished = false
    private var lastProgressNotify = 0L

    /** 临时文件目录（App 私有缓存，不需要任何存储权限） */
    private fun tempDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, TEMP_SUBDIR)
        if (!dir.exists()) {
            // mkdirs() 失败时后续 FileOutputStream 会抛异常并走 onFailed，不需要额外分支
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 开始接收。必须在写入任何块之前调用。
     * @throws Exception 创建临时文件失败（调用方转成 onFailed）
     */
    fun begin(name: String, size: Long, id: String?, cb: Callback) {
        val ctx = context
        callback = cb
        fileId = id
        displayName = sanitizeFileName(name)
        totalBytes = size
        receivedBytes = 0L
        finished = false
        lastProgressNotify = 0L

        if (size > MAX_FILE_SIZE) {
            throw IllegalArgumentException("文件超过 512 MiB 上限")
        }

        val part = File(tempDir(ctx), displayName + ".part")
        if (part.exists()) {
            part.delete()
        }
        partFile = part
        out = BufferedOutputStream(FileOutputStream(part), BUFFER_SIZE)
        cb.onProgress(displayName, 0L, totalBytes)
    }

    /** 收到二进制块（只有原始文件字节，见协议 §3.2） */
    fun write(data: ByteArray) {
        if (finished) return
        val o = out ?: return
        if (data.isEmpty()) return

        if (receivedBytes + data.size > totalBytes) {
            // 协议 §3.3：超过 fileSize → error，多余字节丢弃
            fail("接收到的字节数超过 fileSize（$totalBytes），本次传输判定失败")
            return
        }
        try {
            o.write(data)
            receivedBytes += data.size.toLong()
        } catch (e: Exception) {
            fail("写入失败：" + (e.message ?: e.javaClass.simpleName))
            return
        }

        val cb = callback
        if (cb != null) {
            // 每 512 KiB 或收满时上报一次，避免刷爆主线程消息队列
            if (receivedBytes >= totalBytes || receivedBytes - lastProgressNotify >= PROGRESS_STEP) {
                lastProgressNotify = receivedBytes
                cb.onProgress(displayName, receivedBytes, totalBytes)
            }
        }
        if (receivedBytes >= totalBytes) {
            complete()
        }
    }

    /**
     * 收到 file_end（或正常的字节数已达标的收尾）。
     * @param declaredEnd 是否由显式的 file_end 触发
     */
    fun end(declaredEnd: Boolean) {
        if (finished) return
        if (receivedBytes == totalBytes) {
            complete()
            return
        }
        val got = receivedBytes
        fail("传输未完成：收到 $got / $totalBytes 字节" + if (declaredEnd) "（file_end 提前到达）" else "")
    }

    /** 连接关闭/失败/重连时调用：删除 .part，不留半成品（协议 §3.3） */
    fun abort() {
        if (finished) return
        finished = true
        closeQuietly()
        deletePart()
        callback = null
    }

    private fun complete() {
        if (finished) return
        finished = true
        closeQuietly()
        val part = partFile
        val cb = callback
        if (part == null || cb == null) {
            deletePart()
            return
        }
        try {
            val savedPath = publish(part)
            deletePartIfExists(part)
            cb.onCompleted(displayName, savedPath, receivedBytes)
        } catch (e: Exception) {
            deletePartIfExists(part)
            cb.onFailed(displayName, describe(e))
        } finally {
            callback = null
        }
    }

    private fun fail(reason: String) {
        if (finished) return
        finished = true
        closeQuietly()
        deletePart()
        val cb = callback
        callback = null
        if (cb != null) cb.onFailed(displayName, reason)
    }

    private fun closeQuietly() {
        val o = out
        out = null
        if (o != null) {
            try {
                o.flush()
            } catch (e: Exception) {
                Log.w(TAG, "flush failed", e)
            }
            try {
                o.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
        }
    }

    private fun deletePart() {
        val p = partFile
        partFile = null
        deletePartIfExists(p)
    }

    private fun deletePartIfExists(p: File?) {
        if (p == null) return
        try {
            if (p.exists() && !p.delete()) {
                Log.w(TAG, "无法删除临时文件 ${p.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "删除临时文件异常", e)
        }
    }

    // ---------------- 落盘 ----------------

    private fun publish(part: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(context, part)
        } else {
            publishLegacy(part)
        }
    }

    private fun publishViaMediaStore(ctx: Context, part: File): String {
        val resolver = ctx.contentResolver
        // 注意：part.name 是 "<displayName>.part"，落盘时要用清洗后的 displayName
        val requestedName = uniqueDownloadName(displayName)
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(requestedName))
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS
        )
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)

        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore 插入失败（下载目录不可用？）")

        try {
            val os = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开 MediaStore 输出流")
            os.use { target ->
                part.inputStream().use { input -> copy(input, target) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // 失败时把半成品记录从 MediaStore 里删掉
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
                Log.w(TAG, "清理 MediaStore 记录失败", ignored)
            }
            throw e
        }
        // 重名时 MediaStore 会把实际文件名改成 "name (1).ext"，回读真实名字再拼路径，
        // 否则界面提示的路径与实际落盘位置不一致
        return Environment.DIRECTORY_DOWNLOADS + "/" + readBackDisplayName(resolver, uri, requestedName)
    }

    /** 回读 MediaStore 记录里的真实文件名；查询失败时退回我们请求的名字 */
    private fun readBackDisplayName(resolver: android.content.ContentResolver, uri: Uri, fallback: String): String {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            if (cursor == null || !cursor.moveToFirst()) return fallback
            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (idx < 0 || cursor.isNull(idx)) return fallback
            return cursor.getString(idx) ?: fallback
        } catch (e: Exception) {
            Log.w(TAG, "回读 MediaStore 文件名失败", e)
            return fallback
        } finally {
            try {
                cursor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭 Cursor 失败", e)
            }
        }
    }

    private fun publishLegacy(part: File): String {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads == null) throw IllegalStateException("无法访问公共下载目录")
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw IllegalStateException("无法创建下载目录：" + downloads.absolutePath)
        }
        val target = File(downloads, uniqueDownloadName(displayName))
        try {
            part.inputStream().use { input ->
                FileOutputStream(target).use { output -> copy(input, output) }
            }
        } catch (e: Exception) {
            // 半成品不能留在公共下载目录里
            deletePartIfExists(target)
            throw e
        }
        val ctx = context
        try {
            MediaScannerConnection.scanFile(
                ctx,
                arrayOf(target.absolutePath),
                arrayOf(guessMimeType(target.name)),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaScannerConnection.scanFile 失败", e)
        }
        return target.absolutePath
    }

    private fun copy(input: java.io.InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
        }
        output.flush()
    }

    /** 下载目录里重名时追加 (1)、(2)…（与协议 §3.4 的服务端做法一致） */
    private fun uniqueDownloadName(name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore 会自己处理重名（自动加 (1)），这里直接用原名
            return name
        }
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: return name
        if (!File(downloads, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = "$base($i)$ext"
            if (!File(downloads, candidate).exists()) return candidate
            i++
        }
        return name
    }

    private fun describe(e: Exception): String {
        val msg = e.message
        if (msg != null && msg.isNotEmpty()) return msg
        return e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "FileReceiver"

        /** 协议 §3.4：单次文件大小上限 512 MiB */
        const val MAX_FILE_SIZE = 512L * 1024L * 1024L

        private const val TEMP_SUBDIR = "airtouch_tmp"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 512L * 1024L

        /**
         * 文件名清洗（协议 §3.4 的客户端对应实现）：
         * 取 basename → 去掉 `..`、路径分隔符、控制字符、非法字符 → 去掉结尾的 `.`/空格 →
         * 结果为空则回退为 file_<timestamp>.bin。
         */
        fun sanitizeFileName(raw: String?): String {
            var name = raw ?: ""
            // 统一分隔符后只取最后一段（等价于 Path.GetFileName）
            name = name.replace('\\', '/')
            val slash = name.lastIndexOf('/')
            if (slash >= 0) name = name.substring(slash + 1)

            val sb = StringBuilder(name.length)
            for (c in name) {
                when {
                    c == '\u0000' -> {}
                    c.code < 0x20 -> {}                       // 控制字符
                    c == ':' || c == '*' || c == '?' -> {}    // Windows 非法字符
                    c == '"' || c == '<' || c == '>' -> {}
                    c == '|' -> {}
                    c == '/' || c == '\\' -> {}
                    else -> sb.append(c)
                }
            }
            name = sb.toString().replace("..", "")
            // 去掉结尾的 `.` 与空格
            while (name.isNotEmpty() && (name.endsWith(".") || name.endsWith(" "))) {
                name = name.substring(0, name.length - 1)
            }
            name = name.trim()
            if (name.isEmpty() || name == "." || name == "..") {
                name = "file_" + System.currentTimeMillis() + ".bin"
            }
            if (name.length > 200) {
                val dot = name.lastIndexOf('.')
                val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
                name = name.substring(0, 200 - ext.length) + ext
            }
            return name
        }

        private fun guessMimeType(name: String): String {
            val lower = name.lowercase(Locale.US)
            val dot = lower.lastIndexOf('.')
            if (dot < 0) return "application/octet-stream"
            return when (lower.substring(dot + 1)) {
                "txt", "log", "md", "json", "xml", "csv" -> "text/plain"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "mp3" -> "audio/mpeg"
                "mp4" -> "video/mp4"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }
    }
}
