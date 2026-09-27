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

/** Android Keystore + AES-GCM 凭证保险箱；AAD 将密文绑定到具体存储用途，防止跨槽置换。 */
object KeyVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "aivoice_api_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val API_SLOT = "apiKeyEnc"

    private fun getOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
    } catch (_: Throwable) { null }

    private fun encryptFor(slot: String, plain: String): String? {
        return try {
            if (plain.isEmpty()) return ""
            val key = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val output = ByteArray(cipher.iv.size + encrypted.size)
            System.arraycopy(cipher.iv, 0, output, 0, cipher.iv.size)
            System.arraycopy(encrypted, 0, output, cipher.iv.size, encrypted.size)
            Base64.encodeToString(output, Base64.NO_WRAP)
        } catch (_: Throwable) { null }
    }

    private fun decryptFor(slot: String, stored: String): String? {
        return try {
            if (stored.isEmpty()) return ""
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val key = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_LEN)))
            cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(raw.copyOfRange(IV_LEN, raw.size)), Charsets.UTF_8)
        } catch (_: Throwable) { null }
    }

    /** 读取 API Key；可迁移旧版明文，但拒绝用途不明的旧版无 AAD 密文。 */
    fun readMigrated(sp: SharedPreferences): String {
        val encrypted = sp.getString(API_SLOT, null)
        if (!encrypted.isNullOrEmpty()) {
            decryptFor(API_SLOT, encrypted)?.let { return it }
            // 旧版无 AAD 密文无法证明属于哪个凭证槽：安全优先，删除并要求用户重新填写一次。
            sp.edit().remove(API_SLOT).remove("apiKey").apply()
            return ""
        }
        val plain = sp.getString("apiKey", null)
        if (!plain.isNullOrEmpty()) {
            val upgraded = encryptFor(API_SLOT, plain)
            sp.edit().apply {
                if (upgraded != null) putString(API_SLOT, upgraded) else remove(API_SLOT)
                remove("apiKey")
            }.apply()
            return if (upgraded != null) plain else ""
        }
        return ""
    }

    /** 命名凭证必须以自身槽名通过 AAD 验证；旧版无 AAD 密文拒绝使用，避免跨槽置换。 */
    fun readNamed(sp: SharedPreferences, storageKey: String): String {
        val stored = sp.getString(storageKey, null) ?: return ""
        return decryptFor(storageKey, stored) ?: run {
            sp.edit().remove(storageKey).apply()
            ""
        }
    }

    fun writeNamed(sp: SharedPreferences, storageKey: String, plain: String) {
        val clean = plain.trim()
        val encrypted = if (clean.isEmpty()) "" else encryptFor(storageKey, clean)
        sp.edit().apply {
            if (encrypted.isNullOrEmpty()) remove(storageKey) else putString(storageKey, encrypted)
        }.apply()
    }

    fun writeEncrypted(sp: SharedPreferences, plain: String) {
        val encrypted = encryptFor(API_SLOT, plain.trim())
        sp.edit().apply {
            if (encrypted.isNullOrEmpty()) remove(API_SLOT) else putString(API_SLOT, encrypted)
            remove("apiKey")
        }.apply()
    }
}
