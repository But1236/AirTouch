package com.airtouch.app.net

import java.util.Locale

/**
 * 配对地址（二维码内容 / 手动输入）的解析结果。
 *
 * 协议 §1.1：[wss://{IP}:{PORT}/ws?token={TOKEN}]
 * - 扫码后必须「原样」使用 host/port/path/query，客户端不得改写；
 * - PORT 缺省为 8443；
 * - token 必须非空。
 *
 * 只接受 wss（协议 §4：仅 wss://，明文一律拒绝）。
 */
data class PairingUri(
    val host: String,
    val port: Int,
    val path: String,
    val token: String
) {

    /** 重建用于 OkHttp 的 URL 字符串。IPv6 字面量会补回方括号，否则 URL 无法解析。 */
    fun toUrl(): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "wss://$h:$port$path?token=$token"
    }

    /** 用于日志/界面的简短描述，不含 token */
    fun displayName(): String = "$host:$port"

    companion object {

        const val DEFAULT_PORT = 8443
        const val EXPECTED_PATH = "/ws"

        /**
         * 解析二维码文本或手动输入的 URL。
         *
         * 支持的输入形式（宽松解析，方便手动输入）：
         * - `wss://192.168.1.5:8443/ws?token=xxx`
         * - `wss://192.168.1.5/ws?token=xxx`（端口缺省 8443）
         * 非法输入（非 wss、host 为空、path 不是 /ws、token 为空）返回 null。
         */
        fun parse(raw: String?): PairingUri? {
            if (raw == null) return null
            val text = raw.trim()
            if (text.isEmpty()) return null

            val uri = try {
                java.net.URI(text)
            } catch (e: Exception) {
                return null
            }

            val scheme = uri.scheme ?: return null
            if (!scheme.equals("wss", ignoreCase = true)) return null

            val host = uri.host
            if (host.isNullOrEmpty()) return null

            // URI.getPort() 在端口缺省、非法或越界时都返回 -1；
            // 所以「-1 但 authority 里冒号出现在端口位置」说明端口写错了，必须拒绝，
            // 不能静默套用默认端口。IPv6 字面量的方括号内部本来就有冒号，需要先剥掉。
            val rawPort = uri.port
            val authority = uri.rawAuthority ?: ""
            val portTail = if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                authority.substring(close + 1)
            } else {
                authority
            }
            if (rawPort < 0 && portTail.contains(':')) return null
            val port = if (rawPort >= 0) rawPort else DEFAULT_PORT
            if (port !in 1..65535) return null

            val path = uri.path ?: return null
            if (!path.equals(EXPECTED_PATH, ignoreCase = false)) return null

            val token = extractToken(uri.rawQuery)
            if (token.isEmpty()) return null

            return PairingUri(host = host, port = port, path = path, token = token)
        }

        /**
         * 从手动输入的三段信息构造。host 允许带 `wss://` 前缀和 `:port`、`/ws`、`?token=`
         * 之类的粘贴残留——统一交给 [parse] 走一遍，保证与扫码路径完全一致。
         */
        fun fromParts(hostInput: String?, port: Int, token: String?): PairingUri? {
            val rawHost = (hostInput ?: "").trim()
            val rawToken = (token ?: "").trim()
            if (rawHost.isEmpty() || rawToken.isEmpty()) return null

            // 用户可能直接粘贴完整 URL
            if (rawHost.startsWith("wss://", ignoreCase = true) ||
                rawHost.startsWith("https://", ignoreCase = true)
            ) {
                val direct = parse(rawHost)
                if (direct != null && direct.token.isNotEmpty()) return direct
            }

            // 去掉可能残留的 scheme / 路径 / 查询串
            var h = rawHost
            val schemeIdx = h.indexOf("://")
            if (schemeIdx >= 0) h = h.substring(schemeIdx + 3)
            val slashIdx = h.indexOf('/')
            if (slashIdx >= 0) h = h.substring(0, slashIdx)
            val queryIdx = h.indexOf('?')
            if (queryIdx >= 0) h = h.substring(0, queryIdx)

            // host 里若自带端口，优先使用自带的
            var p = port
            if (h.startsWith("[")) {
                // IPv6 字面量 [::1]:8443
                val close = h.indexOf(']')
                if (close < 0) return null
                val afterBracket = h.substring(close + 1)
                if (afterBracket.startsWith(":") && afterBracket.length > 1) {
                    val embedded = afterBracket.substring(1).toIntOrNull()
                    if (embedded != null && embedded in 1..65535) p = embedded
                }
                h = h.substring(1, close)
            } else {
                val colon = h.lastIndexOf(':')
                if (colon > 0) {
                    val embedded = h.substring(colon + 1).toIntOrNull()
                    if (embedded != null && embedded in 1..65535) {
                        p = embedded
                        h = h.substring(0, colon)
                    }
                }
            }

            h = h.trim()
            if (h.isEmpty()) return null
            if (p !in 1..65535) return null

            return PairingUri(host = h, port = p, path = EXPECTED_PATH, token = rawToken)
        }

        /**
         * 从 rawQuery 中取出 token。手工切分而不是用 Uri.parse()，
         * 因为 java.net.URI 的 getQueryParameters() 已废弃（依赖已废弃的 java.net.URLDecoder 行为）。
         */
        private fun extractToken(rawQuery: String?): String {
            if (rawQuery.isNullOrEmpty()) return ""
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                if (!key.equals("token", ignoreCase = true)) continue
                if (eq < 0) return ""
                val value = pair.substring(eq + 1)
                return urlDecode(value).trim()
            }
            return ""
        }

        /**
         * 只把 %XX 解码成对应的字节（按 UTF-8 逐字节拼接，等价于 URLDecoder 对 UTF-8 的处理）；
         * 需要成对出现两次或以上的编码字节才能还原成多字节字符。
         * 注意：不做 '+' → 空格 的转换——token 是 UUID，'+' 不是合法字符，
         * 真出现时应原样保留（协议 §1.1 要求原样使用）。
         */
        private fun urlDecode(s: String): String {
            if (s.indexOf('%') < 0) return s
            val bytes = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 <= s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v == null) {
                        bytes.write(c.code)
                        i++
                    } else {
                        bytes.write(v)
                        i += 3
                    }
                } else {
                    // 非 ASCII 字符先按 UTF-8 展开，避免和 %XX 解码结果混淆
                    val chunk = s.substring(i, i + 1).toByteArray(Charsets.UTF_8)
                    bytes.write(chunk, 0, chunk.size)
                    i++
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        /** 供界面显示的小工具：host 小写化（指纹 key 用） */
        fun normalizeHostKey(host: String): String = host.trim().lowercase(Locale.US)
    }
}
