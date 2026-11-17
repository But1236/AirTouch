package com.airtouch.app.net

import com.airtouch.app.settings.SettingsStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 证书固定（协议 §4 的强制要求：**禁止**无条件 TrustAll）。
 *
 * v2 的信任模型比 v1 更严格也更好用：
 * 1. 二维码里带着 `fp`（= `base64url(SHA-256(SPKI)[0..16])`）和 `sid`（服务器身份）。只要 `fp`
 *    存在，第一次连接就能**严格校验**，不存在「首次即信任」被中间人抢跑的时间窗；
 * 2. 固定值按 **sid** 保存，而不是按 IP。手机换 Wi-Fi、电脑换网段、IP 变了都还能认出这台电脑 ——
 *    这正是 v1 按 IP 固定时最致命的失效场景；
 * 3. `fp` 缺失（手动输入连接信息）时退回 TOFU：第一次连接记录，之后必须一致；
 * 4. 主机名不再比对：自签名证书的 SAN 只是「生成证书那天的 IP」，拿它当身份只会误伤；真正的身份
 *    是指纹，而指纹来自二维码（带外信道），比 SAN 强得多。
 *
 * 不做有效期校验：自签名证书的有效期由服务端控制，唯一信任依据是固定值（README「已知限制」有记录）。
 */
class PinnedTrust(
    private val store: SettingsStore,
    private val host: String,
    private val sid: String?,
    private val expectedPin: String?,
    private val listener: PinListener? = null
) {

    /** 指纹相关事件。回调发生在 OkHttp 的连接线程上，界面层需自行切回主线程。 */
    interface PinListener {
        /** 首次连接（无 fp 的 TOFU 路径）已记录固定值 */
        fun onPinStored(id: String, pin: String)

        /** 固定值与记录不一致，连接将被中断 */
        fun onPinMismatch(id: String, expectedPin: String, actualPin: String)
    }

    /** 固定值在存储中的键：优先用服务器身份 sid，缺失时退回 host。 */
    private val trustKey: String = if (!sid.isNullOrEmpty()) "sid:$sid" else "host:${host.lowercase()}"

    private val trustManager: X509TrustManager = object : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 本协议不使用双向 TLS
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain == null || chain.isEmpty()) {
                throw CertificateException("服务器未提供证书，无法完成证书固定校验")
            }

            val leaf = chain[0]
            // SPKI 固定值：与二维码里的 fp、以及 PC 端 CertificatePin 用的是同一套算法。
            val actualPin = AuthCrypto.fingerprint(leaf.publicKey.encoded)
            val expected = expectedPin

            if (!expected.isNullOrEmpty()) {
                // 二维码里带了 fp：严格比对，不给中间人任何窗口。
                if (expected == actualPin) {
                    store.setServerPin(trustKey, actualPin, AuthCrypto.sha256Hex(leaf.encoded))
                    return
                }

                listener?.onPinMismatch(trustKey, expected, actualPin)
                throw CertificateException(
                    "服务器证书与二维码里的固定值不一致，连接已中断。\n" +
                        "可能原因：这台电脑重新生成了证书（请重新扫码），或者网络里有人在冒充它。\n" +
                        "（服务器身份：${sid ?: host}）"
                )
            }

            val pinned = store.getServerPin(trustKey)
            if (pinned.isNullOrEmpty()) {
                store.setServerPin(trustKey, actualPin, AuthCrypto.sha256Hex(leaf.encoded))
                listener?.onPinStored(trustKey, actualPin)
                return
            }

            if (pinned == actualPin) {
                // 顺带刷新整证书哈希（换 IP 重新签发的证书只要 SPKI 不变就仍然可信）
                store.setServerPin(trustKey, actualPin, AuthCrypto.sha256Hex(leaf.encoded))
                return
            }

            listener?.onPinMismatch(trustKey, pinned, actualPin)
            throw CertificateException(
                "服务器证书已变化，连接已中断。\n" +
                    "如果你在电脑端重新生成了服务器证书，请在配对页点击「重置证书信任」后重新扫码。\n" +
                    "（服务器身份：${sid ?: host}）"
            )
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val factory: SSLSocketFactory

    init {
        // 只启用 TLS，具体版本交给系统协商（TLS 1.2 / 1.3）
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        factory = ctx.socketFactory
    }

    fun sslSocketFactory(): SSLSocketFactory = factory

    fun x509TrustManager(): X509TrustManager = trustManager

    /**
     * 主机名校验器：接受任意主机名，**身份由固定值保证**。
     *
     * 这不是「一律 return true」的偷懒写法，而是一次刻意的取舍：服务端证书是自签名的，
     * 它的 SAN 只是生成证书那一刻的 IP 列表；如果用 SAN 校验主机名，用户换个网段（或者
     * 同一个 IP 被 DHCP 分给了别的设备）都会导致连不上。真正不可伪造的是二维码带外传过来的
     * SPKI 固定值，它比主机名更强，因此这里把主机名这一层让给固定值。
     */
    fun hostnameVerifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }

    companion object {

        /** 清除某个服务器身份（sid 或 host）的固定值；下次连接重新走 TOFU。 */
        fun reset(store: SettingsStore, sid: String?, host: String) {
            store.resetServerPin(if (!sid.isNullOrEmpty()) "sid:$sid" else "host:${host.lowercase()}")
        }

        /** 清除全部固定值，返回清除条数。 */
        fun resetAll(store: SettingsStore): Int = store.resetAllServerPins()
    }
}
