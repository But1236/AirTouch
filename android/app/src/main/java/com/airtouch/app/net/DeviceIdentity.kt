package com.airtouch.app.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 本机设备身份：一张 **ECDSA P-256** 密钥对，私钥永不离开手机。
 *
 * 协议 §1.2：配对时把公钥发给电脑，电脑把它当作这台手机的唯一身份；之后每次连接都用私钥
 * 对服务端下发的 nonce 签一次名，签名通过即视为「这台手机」。因此：
 * - 私钥保存在 AndroidKeyStore（硬件支撑的密钥库，不可导出）；
 * - 每台手机只有一把密钥，跨连接、跨重启、跨 IP 变化都保持不变；
 * - 一次会话只签一次名，触摸帧完全不参与签名，不会影响延迟。
 *
 * 为什么只用 P-256：AndroidKeyStore 只支持 NIST 曲线（secp256r1 等），比特币那条 secp256k1
 * 在 KeyStore 里根本生成不了；P-256 同时是 .NET BCL 的原生支持曲线，两端都不需要引入任何
 * 第三方密码学库。
 *
 * [fallback] 只在 KeyStore 不可用时启用（极少数被裁剪的 ROM / 未完成初始化的模拟器）：
 * 此时改为软件密钥，并把 PKCS#8 存进 App 私有目录。这比硬件密钥弱，但比「完全用不了」强，
 * 且 App 私有目录在未 root 的设备上其他应用无法读取。
 */
class DeviceIdentity private constructor(
    private val context: Context,
    private val keyPair: KeyPair,
    val usedKeystore: Boolean
) {

    /** 公钥的 X.509 SubjectPublicKeyInfo DER —— 与 PC 端 `ExportSubjectPublicKeyInfo()` 完全一致的字节。 */
    val publicKeySpki: ByteArray get() = keyPair.public.encoded

    /** 设备身份标识：`base64url(SHA-256(SPKI)[0..16])`，电脑端用它查信任列表。 */
    val keyId: String by lazy { AuthCrypto.keyIdOfPublicKey(publicKeySpki) }

    /** 公钥的 base64url，配对时随 auth_req 一起发送。 */
    val publicKeyBase64: String by lazy { AuthCrypto.base64Url(publicKeySpki) }

    /** 用人机可读的方式说明密钥来源（界面上显示）。 */
    val describe: String get() = if (usedKeystore) "设备密钥（AndroidKeyStore）" else "设备密钥（软件回退）"

    /**
     * 对 `auth_req` 的签名原文签名，返回 **ASN.1 DER** 编码的签名（Java JCA 的默认格式，
     * 也正是 .NET 侧用 Rfc3279DerSequence 校验的格式）。
     */
    fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGN_ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(data)
        return signature.sign()
    }

    /** 为一次挑战生成完整的 `auth_req` 帧。 */
    fun buildAuthRequest(
        sid: String,
        nonce: String,
        token: String?,
        deviceName: String?,
        appVersion: String?,
        serverTimeOffsetSeconds: Long = 0L
    ): String {
        val timestamp = AuthCrypto.unixNow() + serverTimeOffsetSeconds
        val canonical = AuthCrypto.canonical(sid, nonce, keyId, timestamp)
        val signature = AuthCrypto.base64Url(sign(canonical))

        return Protocol.buildAuthRequest(
            authVersion = AuthCrypto.AUTH_VERSION,
            keyId = keyId,
            token = token,
            publicKey = if (token != null) publicKeyBase64 else null,
            signature = signature,
            timestamp = timestamp,
            deviceName = deviceName,
            appVersion = appVersion
        )
    }

    companion object {
        private const val TAG = "DeviceIdentity"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "airtouch_device_identity_v1"
        private const val SIGN_ALGORITHM = "SHA256withECDSA"
        private const val CURVE = "secp256r1"

        private const val PREFS_NAME = "airtouch_device_key"
        private const val PREF_FALLBACK_KEY = "software_pkcs8"
        private const val PREF_FALLBACK_PUBLIC = "software_x509"

        @Volatile
        private var cached: DeviceIdentity? = null

        /** 取得（必要时生成）本机设备身份。可在任意线程调用。 */
        fun get(context: Context): DeviceIdentity {
            cached?.let { return it }
            synchronized(DeviceIdentity::class.java) {
                cached?.let { return it }
                val app = context.applicationContext
                val identity = try {
                    DeviceIdentity(app, loadOrCreateKeystoreKey(), usedKeystore = true)
                } catch (e: Exception) {
                    Log.w(TAG, "AndroidKeyStore 不可用，改用软件密钥", e)
                    DeviceIdentity(app, loadOrCreateSoftwareKey(app), usedKeystore = false)
                }
                cached = identity
                Log.i(TAG, "设备身份就绪：keyId=${identity.keyId}（${identity.describe}）")
                return identity
            }
        }

        private fun loadOrCreateKeystoreKey(): KeyPair {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
                return KeyPair(it.certificate.publicKey, it.privateKey)
            }

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                // Keystore 要求在生成时就固定摘要算法，之后 Signature 必须用同一摘要。
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            Log.i(TAG, "已在 AndroidKeyStore 中生成设备密钥")
            return generator.generateKeyPair()
        }

        private fun loadOrCreateSoftwareKey(context: Context): KeyPair {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedPrivate = prefs.getString(PREF_FALLBACK_KEY, null)
            val storedPublic = prefs.getString(PREF_FALLBACK_PUBLIC, null)

            if (!storedPrivate.isNullOrEmpty() && !storedPublic.isNullOrEmpty()) {
                try {
                    val factory = java.security.KeyFactory.getInstance("EC")
                    val privateKey = factory.generatePrivate(
                        java.security.spec.PKCS8EncodedKeySpec(Base64.decode(storedPrivate, Base64.NO_WRAP))
                    )
                    val publicKey = factory.generatePublic(
                        java.security.spec.X509EncodedKeySpec(Base64.decode(storedPublic, Base64.NO_WRAP))
                    )
                    return KeyPair(publicKey, privateKey)
                } catch (e: Exception) {
                    Log.w(TAG, "软件密钥解析失败，重新生成", e)
                }
            }

            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            val pair = generator.generateKeyPair()
            prefs.edit()
                .putString(PREF_FALLBACK_KEY, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .putString(PREF_FALLBACK_PUBLIC, Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
                .apply()
            Log.w(TAG, "已生成软件回退设备密钥（该设备的密钥不再受硬件保护）")
            return pair
        }
    }
}
