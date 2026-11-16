package com.airtouch.app.net

import com.airtouch.app.settings.SettingsStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TOFU（Trust On First Use）证书指纹固定。
 *
 * 协议 §4 强制要求：Android 端必须做证书指纹固定，**禁止**无条件 TrustAll。
 *
 * 工作方式：
 * 1. 服务端使用自签名证书（协议 §4），系统 CA 链校验必然失败，因此这里不使用默认 TrustManager；
 * 2. 第一次连接某个 host 时，把叶子证书的 SHA-256 记为该 host 的指纹并接受；
 * 3. 之后连接时指纹必须完全一致，否则抛 CertificateException 中断连接；
 * 4. 主机名校验不是「一律 return true」：只接受与本次连接期望 host 相同的主机名，
 *    并且这个 host 还必须与固定指纹时的 host 是同一个。
 *
 * 注意：本类不做证书有效期（notBefore/notAfter）校验——自签名证书的有效期由服务端控制，
 * 且指纹一致才是唯一的信任依据。这一点在 README 的「已知限制」中有说明。
 */
class PinnedTrust(
    private val store: SettingsStore,
    private val expectedHost: String,
    private val listener: PinListener? = null
) {

    /** 指纹相关事件回调。回调可能在 OkHttp 读/连接线程上触发，界面层需自行切回主线程。 */
    interface PinListener {
        /** 首次连接该 host，已记录指纹 */
        fun onPinStored(host: String, fingerprintHex: String)

        /** 指纹与记录不一致，连接将被中断 */
        fun onPinMismatch(host: String, expectedHex: String, actualHex: String)
    }

    private val trustManager: X509TrustManager = object : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 客户端证书校验：本协议不使用双向 TLS，空实现
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain == null || chain.isEmpty()) {
                throw CertificateException("服务器未提供证书，无法完成指纹校验")
            }
            val leaf = chain[0]
            val actual = sha256Hex(leaf.encoded)
            val host = expectedHost

            val pinned = store.getCertificatePin(host)
            if (pinned == null) {
                // 首次使用：记录指纹并接受
                store.setCertificatePin(host, actual)
                listener?.onPinStored(host, actual)
                return
            }
            if (pinned.equals(actual, ignoreCase = true)) {
                return
            }
            listener?.onPinMismatch(host, pinned, actual)
            throw CertificateException(
                "证书指纹已变化，连接已中断。" +
                    "如果你在电脑端重新生成了服务器证书，请在配对界面点击「重置证书信任」后重新扫码配对。" +
                    "（主机：$host）"
            )
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val factory: SSLSocketFactory

    init {
        // 只用 TLS，让系统协商 TLS 1.2 / 1.3（协议 §4）
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        factory = ctx.socketFactory
    }

    /** 供 OkHttpClient.Builder.sslSocketFactory(factory, trustManager) 使用 */
    fun sslSocketFactory(): SSLSocketFactory = factory

    /** 供 OkHttpClient.Builder.sslSocketFactory(factory, trustManager) 使用 */
    fun x509TrustManager(): X509TrustManager = trustManager

    /**
     * 主机名校验器：**不是**无条件 true。
     * 只接受「本次连接期望的 host」——即证书链必须来自我们配对的那台主机。
     * （指纹已经保证了证书身份，这里再钉死主机名，避免把同一个证书用于别的地址。）
     */
    fun hostnameVerifier(): HostnameVerifier = HostnameVerifier { hostname, _ ->
        if (hostname == null) {
            false
        } else {
            stripBrackets(hostname).equals(stripBrackets(expectedHost), ignoreCase = true)
        }
    }

    companion object {

        /** 计算字节数组的 SHA-256，返回小写十六进制字符串 */
        fun sha256Hex(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(data)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4])
                sb.append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()

        private fun stripBrackets(s: String): String {
            var t = s.trim()
            if (t.startsWith("[") && t.endsWith("]") && t.length > 2) {
                t = t.substring(1, t.length - 1)
            }
            return t
        }

        /**
         * 清除某个 host 的指纹；下次连接该 host 会重新走 TOFU（首次即信任）。
         * 用户重新生成了服务端证书时使用。
         */
        fun reset(store: SettingsStore, host: String) {
            store.resetCertificatePin(host)
        }

        /** 清除全部指纹，返回清除条数 */
        fun resetAll(store: SettingsStore): Int = store.resetAllCertificatePins()
    }
}
