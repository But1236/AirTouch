package com.airtouch.app.file

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.net.Protocol
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机 → 服务端 的文件发送器。
 *
 * 协议 §3 要点：
 * - 先发文本 `file_header{fileName,fileSize}`，随后紧跟二进制块（每块最多 64 KiB）；
 * - 二进制帧只有原始文件字节；
 * - 一个方向同一时刻只允许一个进行中的传输。
 *
 * 线程模型：所有读取与发送都在一个专用单线程 [executor] 上完成，**绝不阻塞主线程**；
 * 回调 [Listener] 也在该线程触发，界面层需自行 post 到主线程。
 *
 * SAF 说明：选文件用 ActivityResultContracts.OpenDocument，MIME 过滤传通配符
 * （任意类型，即「星号 斜杠 星号」）。这里刻意不把通配符原样写出来：它含有块注释
 * 结束符，直接写进 KDoc 会提前终止本段注释，并引发一串 "Expecting a top level declaration"。
 * 返回的是 content:// URI，因此必须通过 ContentResolver 查询名字与大小，
 * 并且只有在该 URI 授权有效的期间内读取（ActivityResultContracts 默认给了持久读权限）。
 */
class FileSender(
    private val context: Context,
    private val client: AirTouchClient,
    private val listener: Listener
) {

    interface Listener {
        /**
         * 开始发送。
         *
         * 协议 §3.3 要求 `file_header.fileSize` 与实际发送的字节数严格相等，所以
         * **total 一定是 >= 0 的确定值**（大小问不出来的文件会在发送前直接失败，
         * 不会出现 total < 0 的情况）。
         */
        fun onSendStarted(fileName: String, total: Long)

        /** 进度回调，可能非常频繁（每块一次，64 KiB 一块） */
        fun onSendProgress(fileName: String, sent: Long, total: Long, percent: Int)

        /** 发送完成（header + 所有字节都已交给 OkHttp） */
        fun onSendCompleted(fileName: String, sent: Long)

        /** 发送失败 */
        fun onSendFailed(fileName: String, reason: String)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "airtouch-file-sender")
        t.isDaemon = true
        t
    }

    private val busy = AtomicBoolean(false)

    val isBusy: Boolean
        get() = busy.get()

    /**
     * 启动一次发送。
     * @return false 表示已有传输在进行中（调用方提示「已有文件正在传输」）
     */
    fun send(uri: Uri): Boolean {
        if (!busy.compareAndSet(false, true)) {
            return false
        }
        executor.execute {
            try {
                doSend(uri)
            } catch (e: Exception) {
                Log.w(TAG, "发送失败", e)
                listener.onSendFailed(displayNameOf(uri), describe(e))
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** Activity 销毁时调用，避免线程泄漏 */
    fun shutdown() {
        try {
            executor.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown 异常", e)
        }
    }

    // ---------------- 实现 ----------------

    private fun doSend(uri: Uri) {
        var name = ""
        var size = -1L
        try {
            queryMeta(uri)?.let { meta ->
                name = meta.first
                size = meta.second
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询文件信息失败", e)
        }
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: "unnamed.bin"
        }

        // 协议 §3.3：接收方以「累计字节数 == fileSize」判定正常结束，fileSize 必须与
        // 实际发送的字节数一致。声称 0 字节再发数据会被服务端判为「超过声明大小」并整单拒绝，
        // 所以大小未知时必须先想办法问出来，实在问不出来就放弃发送而不是发一个错误的 header。
        var stream: java.io.InputStream? = null
        try {
            stream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                Log.w(TAG, "打开输入流失败", e)
                null
            }
            val input = stream
            if (input == null) {
                listener.onSendFailed(name, "无法打开文件输入流（可能没有读取权限）")
                return
            }

            if (size < 0) {
                size = sizeFromFileDescriptor(uri)
            }
            if (size < 0) {
                listener.onSendFailed(name, "无法读取文件大小，无法按协议发送")
                return
            }
            if (size > Protocol.MAX_FILE_SIZE) {
                listener.onSendFailed(name, "文件超过 512 MiB 上限，已中止发送")
                return
            }
            val declaredSize = size

            listener.onSendStarted(name, declaredSize)

            // header 必须在流已经拿到之后再发：否则打开失败时服务端已经进入「等待文件字节」
            // 状态，而我们一个字节都发不出去，白白在服务端留下一个需要超时清理的 .part
            if (!client.sendFileHeader(name, declaredSize)) {
                listener.onSendFailed(name, "连接已断开，无法发送")
                return
            }

            sendChunks(input, name, declaredSize)
        } finally {
            try {
                stream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭输入流失败", e)
            }
        }
    }

    /** 文件正文的分块发送；[size] 一定 >= 0 且已经写进 file_header */
    private fun sendChunks(input: java.io.InputStream, name: String, size: Long) {
        var sent = 0L
        val buffer = ByteArray(Protocol.CHUNK_SIZE)
        var lastPercent = -1
        try {
            while (true) {
                // 只有 -1 才是 EOF；对于长度 > 0 的缓冲区，read 不应该返回 0。
                // 万一真的返回 0（异常的流实现），按结束处理：下面会用
                // sent != size 检出来并如实报错，绝不谎报「发送完成」。
                val n = input.read(buffer)
                if (n <= 0) break
                if (!client.sendBinary(buffer, 0, n)) {
                    listener.onSendFailed(name, "连接已断开，发送中止（已发送 $sent 字节）")
                    return
                }
                sent += n.toLong()
                if (sent > Protocol.MAX_FILE_SIZE) {
                    // 理论上不会发生（header 里已按 size 校验过），这里只是兜底
                    listener.onSendFailed(name, "文件超过 512 MiB 上限，已中止发送")
                    return
                }
                val percent = if (size > 0) ((sent * 100L) / size).toInt().coerceIn(0, 100) else 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    listener.onSendProgress(name, sent, size, percent)
                }
            }
        } catch (e: Exception) {
            listener.onSendFailed(name, "读取文件失败：" + describe(e))
            return
        }

        if (sent != size) {
            // 读到的字节数与 file_header 声明的不一致：服务端会判定字节数不符，
            // 这里如实告知用户，不要谎报「发送完成」
            listener.onSendFailed(name, "文件读取到的字节数（$sent）与声明大小（$size）不一致")
            return
        }

        // 协议 §3.1：file_end 是可选的（正常实现靠字节数判定），这里显式发一帧作为收尾信号
        client.sendFileEnd()
        listener.onSendCompleted(name, sent)
    }

    /**
     * 从 SAF 文件描述符里取文件大小（DISPLAY_NAME/SIZE 查询失败时的兜底）。
     * 内容提供者不一定支持，取不到时返回 -1。
     */
    private fun sizeFromFileDescriptor(uri: Uri): Long {
        var afd: android.content.res.AssetFileDescriptor? = null
        return try {
            afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            val len = afd?.length ?: -1L
            if (len > 0L) len else -1L
        } catch (e: Exception) {
            Log.w(TAG, "读取文件描述符大小失败", e)
            -1L
        } finally {
            try {
                afd?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭文件描述符失败", e)
            }
        }
    }

    /** @return (显示名, 大小字节)；查不到返回 null */
    private fun queryMeta(uri: Uri): Pair<String, Long>? {
        val resolver = context.contentResolver
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?: return null
            if (!cursor.moveToFirst()) return null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
            if (name == null) return null
            return Pair(FileReceiver.sanitizeFileName(name), size)
        } finally {
            try {
                cursor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭 Cursor 失败", e)
            }
        }
    }

    private fun displayNameOf(uri: Uri): String {
        return try {
            queryMeta(uri)?.first ?: (uri.lastPathSegment ?: "文件")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "文件"
        }
    }

    private fun describe(e: Exception): String {
        val m = e.message
        return if (m != null && m.isNotEmpty()) m else e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "FileSender"
    }
}
