package com.securesdk.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key-value store criptografado (AES-256-GCM) com chave mestra no Android Keystore.
 *
 * Formato persistido (por entrada, em SharedPreferences comum):
 *   prefs[key] = Base64( IV(12 bytes) || CipherText || GCM Tag(16 bytes) )
 *
 * Plaintext interno = TYPE(1 byte) || payload
 *   - O byte de tipo impede ler um Long como String etc. sem perceber.
 * AAD (Additional Authenticated Data) = nome da chave (UTF-8)
 *   - Amarra o ciphertext ao nome: copiar o blob de "auth_token" para "server_url"
 *     num XML adulterado faz o decrypt falhar (AEADBadTagException) em vez de
 *     devolver o valor trocado.
 *
 * API mínima: 23 (AES/GCM no AndroidKeyStore).
 */
class SecureConfig private constructor(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    prefsName: String = DEFAULT_PREFS_NAME,
) {

    companion object {
        private const val TAG = "SecureConfig"
        const val DEFAULT_KEY_ALIAS = "com.securesdk.master"
        const val DEFAULT_PREFS_NAME = "secure_cfg"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE = 12          // 96-bit nonce, recomendado para GCM
        private const val TAG_SIZE_BITS = 128

        private const val T_STRING: Byte = 1
        private const val T_BOOLEAN: Byte = 2
        private const val T_LONG: Byte = 3
        private const val T_BYTES: Byte = 4

        @Volatile private var instance: SecureConfig? = null

        fun getInstance(context: Context): SecureConfig =
            getInstance(context, DEFAULT_KEY_ALIAS, DEFAULT_PREFS_NAME)

        fun getInstance(context: Context, keyAlias: String, prefsName: String): SecureConfig =
            instance ?: synchronized(this) {
                instance ?: SecureConfig(context.applicationContext, keyAlias, prefsName)
                    .also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Lock único: serializa acesso ao Keystore (geração da chave) e read-modify das prefs. */
    private val lock = Any()

    /** Cache da referência da chave (o material nunca sai do Keystore/TEE). */
    @Volatile private var cachedKey: SecretKey? = null

    // ------------------------------------------------------------------ API pública

    fun putString(key: String, value: String) =
        put(key, T_STRING, value.toByteArray(Charsets.UTF_8))

    fun getString(key: String, default: String = ""): String =
        get(key, T_STRING)?.toString(Charsets.UTF_8) ?: default

    fun putBoolean(key: String, value: Boolean) =
        put(key, T_BOOLEAN, byteArrayOf(if (value) 1 else 0))

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        get(key, T_BOOLEAN)?.takeIf { it.size == 1 }?.let { it[0] == 1.toByte() } ?: default

    fun putLong(key: String, value: Long) =
        put(key, T_LONG, ByteBuffer.allocate(8).putLong(value).array())

    fun getLong(key: String, default: Long = 0L): Long =
        get(key, T_LONG)?.takeIf { it.size == 8 }?.let { ByteBuffer.wrap(it).long } ?: default

    fun putBytes(key: String, value: ByteArray) = put(key, T_BYTES, value)

    fun getBytes(key: String): ByteArray? = get(key, T_BYTES)

    fun remove(key: String) = synchronized(lock) {
        prefs.edit().remove(key).apply()
    }

    fun clear() = synchronized(lock) {
        prefs.edit().clear().apply()
    }

    fun contains(key: String): Boolean = synchronized(lock) { prefs.contains(key) }

    // ------------------------------------------------------------------ Encrypt

    private fun put(key: String, type: Byte, payload: ByteArray) {
        val plain = ByteArray(1 + payload.size).also {
            it[0] = type
            System.arraycopy(payload, 0, it, 1, payload.size)
        }
        try {
            val blob = synchronized(lock) {
                val encrypted = encryptWithRetry(key, plain)
                prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
                encrypted
            }
            blob.fill(0)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "encrypt failed for '$key'", e)
        } finally {
            plain.fill(0)
        }
    }

    private fun encryptWithRetry(key: String, plain: ByteArray): ByteArray =
        try {
            encrypt(key, plain)
        } catch (e: KeyPermanentlyInvalidatedException) {
            resetKeyAndData()
            encrypt(key, plain)
        }

    private fun encrypt(key: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "unexpected IV size ${iv.size}" }
        val ct = cipher.doFinal(plain)
        return ByteArray(IV_SIZE + ct.size).also {
            System.arraycopy(iv, 0, it, 0, IV_SIZE)
            System.arraycopy(ct, 0, it, IV_SIZE, ct.size)
        }
    }

    // ------------------------------------------------------------------ Decrypt

    private fun get(key: String, expectedType: Byte): ByteArray? {
        val encoded = synchronized(lock) { prefs.getString(key, null) } ?: return null
        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "corrupt base64 for '$key'")
            return null
        }
        if (blob.size < IV_SIZE + TAG_SIZE_BITS / 8 + 1) return null

        val plain = try {
            decrypt(key, blob)
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "auth tag mismatch for '$key'")
            return null
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "key invalidated; wiping store")
            synchronized(lock) { resetKeyAndData() }
            return null
        } catch (e: KeyStoreException) {
            Log.w(TAG, "keystore unavailable", e); return null
        } catch (e: UnrecoverableKeyException) {
            Log.w(TAG, "key unrecoverable", e); return null
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "decrypt failed for '$key'", e); return null
        } catch (e: RuntimeException) {
            Log.w(TAG, "keystore runtime failure", e); return null
        }

        return try {
            if (plain[0] != expectedType) null else plain.copyOfRange(1, plain.size)
        } finally {
            plain.fill(0)
        }
    }

    private fun decrypt(key: String, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getExistingKey() ?: throw AEADBadTagException("no key in keystore"),
            GCMParameterSpec(TAG_SIZE_BITS, blob, 0, IV_SIZE),
        )
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    // ------------------------------------------------------------------ Keystore

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getExistingKey(): SecretKey? {
        cachedKey?.let { return it }
        synchronized(lock) {
            cachedKey?.let { return it }
            val ks = loadKeyStore()
            return (ks.getKey(keyAlias, null) as? SecretKey)?.also { cachedKey = it }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        synchronized(lock) {
            cachedKey?.let { return it }
            if (prefs.all.isNotEmpty()) {
                Log.w(TAG, "master key missing; discarding stale ciphertexts")
                prefs.edit().clear().commit()
            }
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(spec)
            return kg.generateKey().also { cachedKey = it }
        }
    }

    private fun resetKeyAndData() {
        cachedKey = null
        try {
            loadKeyStore().deleteEntry(keyAlias)
        } catch (e: Exception) {
            Log.w(TAG, "deleteEntry failed", e)
        }
        prefs.edit().clear().commit()
    }
}
