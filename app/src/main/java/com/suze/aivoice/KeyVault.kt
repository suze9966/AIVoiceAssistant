package com.suze.aivoice

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 大模型 API Key 保险箱。
 *
 * 安全设计：
 * 1) 密钥本体由 Android Keystore 生成并保管（默认落在 TEE / StrongBox，无法被导出）。
 * 2) 用该密钥以 AES/GCM/NoPadding 加密用户的 API Key，密文 + IV 一起 Base64 后存入
 *    SharedPreferences。即使有人拿到 /data/data 目录或用备份工具导出，也只能看到密文，
 *    拿不到 Keystore 里的密钥，无法解密。
 * 3) GCM 自带完整性校验，密文被篡改会解密失败（直接当空 Key 处理，不会崩溃）。
 *
 * 兼容性：minSdk 24 起 Keystore AES + GCM 全量可用，无需额外依赖。
 */
object KeyVault {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "aivoice_api_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    private fun getOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            existing.secretKey
        } else {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            gen.generateKey()
        }
    } catch (t: Throwable) {
        // 极端情况（个别 ROM Keystore 异常）退化为 null，交由上层决定
        null
    }

    /** 加密明文，返回 Base64(IV + 密文)；失败返回 null */
    fun encrypt(plain: String): String? = try {
        if (plain.isEmpty()) return ""
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(enc, 0, out, iv.size, enc.size)
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }

    /** 解密 Base64(IV + 密文)；失败返回 null（含被篡改、换机后密钥失效等情况） */
    fun decrypt(stored: String): String? = try {
        if (stored.isEmpty()) return ""
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        if (raw.size <= IV_LEN) return null
        val iv = raw.copyOfRange(0, IV_LEN)
        val enc = raw.copyOfRange(IV_LEN, raw.size)
        val key = getOrCreateKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(enc), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    /**
     * 读取并（必要时）迁移：把旧版本明文存储的 Key 升级为密文。
     * @param sp 与加密后存储同一个 SharedPreferences 对象
     */
    fun readMigrated(sp: SharedPreferences): String {
        val encVal = sp.getString("apiKeyEnc", null)
        if (!encVal.isNullOrEmpty()) {
            val dec = decrypt(encVal)
            if (dec != null) return dec
            // 解不开（换机/被清）：清掉脏数据
            sp.edit().remove("apiKeyEnc").apply()
            return ""
        }
        // 尝试迁移旧的明文 apiKey
        val plain = sp.getString("apiKey", null)
        if (!plain.isNullOrEmpty()) {
            val e = encrypt(plain)
            sp.edit().apply {
                if (e != null) putString("apiKeyEnc", e)
                remove("apiKey") // 无论成功与否，明文必须清除
            }.apply()
            return if (e != null) plain else "" // 加密失败则不返回明文，避免再次落盘
        }
        return ""
    }

    /** 写入：加密后存 apiKeyEnc，并确保明文 apiKey 已被清除 */
    fun writeEncrypted(sp: SharedPreferences, plain: String) {
        val e = encrypt(plain)
        sp.edit().apply {
            if (e != null) putString("apiKeyEnc", e) else remove("apiKeyEnc")
            remove("apiKey")
        }.apply()
    }
}
