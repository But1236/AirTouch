package com.airtouch.app.net

import java.security.MessageDigest
import java.util.Base64

/**
 * 认证协议里的字节级工具（唯一事实来源：docs/PROTOCOL.md §1.2）。
 *
 * 与 PC 端 `AirTouch.Core/AuthCrypto.cs` 必须**逐字节一致**，任何一处不同都会导致签名永远校验不过：
 * - 签名原文 [canonical]：固定的 LF 分隔 ASCII 串，绝不是 JSON；
 * - 公钥编码：X.509 SubjectPublicKeyInfo DER（`PublicKey.getEncoded()`）；
 * - 签名编码：ASN.1 DER（Java 的 `SHA256withECDSA` 默认输出，.NET 侧用 Rfc3279DerSequence 校验）；
 * - keyId / fp：`base64url(SHA-256(...)[0..16])`，不带 `=` 填充。
 */
object AuthCrypto {

    /** 签名原文首行；改动它等于让所有已保存的设备公钥失效。 */
    const val DOMAIN = "AirTouch-Auth-v1"

    /** `auth_v` 字段的当前值。 */
    const val AUTH_VERSION = 1

    /** keyId / 证书固定值保留的 SHA-256 字节数（128 位）。 */
    const val FINGERPRINT_BYTES = 16

    private val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()

    /** 无填充的 base64url（协议里所有二进制字段都用它）。 */
    fun base64Url(data: ByteArray): String = URL_ENCODER.encodeToString(data)

    /** 解析无填充的 base64url；非法输入返回 null（不抛异常）。 */
    fun fromBase64Url(text: String?): ByteArray? {
        if (text.isNullOrEmpty()) return null
        return try {
            URL_DECODER.decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * 真正被签名的字节序列：`AirTouch-Auth-v1\n{sid}\n{nonce}\n{keyId}\n{ts}`（UTF-8、LF 分隔）。
     * nonce 由服务端下发且一次性使用，所以抓到的 auth_req 无法重放（协议 §1.2 第 3 步）。
     */
    fun canonical(sid: String, nonce: String, keyId: String, unixSeconds: Long): ByteArray =
        "$DOMAIN\n$sid\n$nonce\n$keyId\n$unixSeconds".toByteArray(Charsets.UTF_8)

    /** `base64url(SHA-256(data)[0..16])`，用于 keyId 与证书固定值。 */
    fun fingerprint(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return base64Url(digest.copyOfRange(0, FINGERPRINT_BYTES))
    }

    /** 设备公钥的 keyId：`base64url(SHA-256(SPKI)[0..16])`。 */
    fun keyIdOfPublicKey(subjectPublicKeyInfo: ByteArray): String = fingerprint(subjectPublicKeyInfo)

    /** 整张证书的 SHA-256（小写十六进制），用于在界面上核对与旧版兼容。 */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 把十六进制指纹格式化成 `AA:BB:...`，仅用于显示。 */
    fun formatHex(hex: String): String {
        if (hex.length < 2) return hex
        val sb = StringBuilder(hex.length + hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            if (sb.isNotEmpty()) sb.append(':')
            sb.append(hex.substring(i, i + 2).uppercase())
            i += 2
        }
        return sb.toString()
    }

    /** 当前 Unix 秒。 */
    fun unixNow(): Long = System.currentTimeMillis() / 1000L

    private val HEX = "0123456789abcdef".toCharArray()
}
