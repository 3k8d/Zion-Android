package com.zion.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zion.app.model.AppConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Settings and servers, encrypted with a key that never leaves the Android Keystore (the Windows version
 * uses DPAPI for the same purpose). Written to a temporary file first and then moved into place.
 * A file that cannot be decrypted is kept aside instead of being overwritten.
 */
object ConfigStore {
    private const val KEY_ALIAS = "zion_config"
    private const val FILE_NAME = "config.dat"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var dir: File
    @Volatile private var current: AppConfig? = null

    /** Unit tests run without Android: nothing is read or written. */
    var isTestMode = false

    fun init(context: Context) {
        dir = context.filesDir
    }

    val config: AppConfig
        get() = current ?: synchronized(this) { current ?: load().also { current = it } }

    private fun load(): AppConfig {
        if (isTestMode) return AppConfig()
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return AppConfig()
        return try {
            val bytes = file.readBytes()
            val plain = decrypt(bytes)
            val cfg = json.decodeFromString(AppConfig.serializer(), String(plain, Charsets.UTF_8))
            cfg.proxies.forEach { it.autoEnrichCountryIfMissing() }
            cfg
        } catch (_: Exception) {
            // Fail closed: never fall back to plaintext, keep the unreadable file for later
            runCatching { file.copyTo(File(dir, "config.dat.corrupted_${System.currentTimeMillis()}.bak"), overwrite = true) }
            AppConfig()
        }
    }

    @Synchronized
    fun save(config: AppConfig = this.config) {
        current = config
        if (isTestMode) return
        try {
            val text = json.encodeToString(AppConfig.serializer(), config)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.outputStream().use { out ->
                out.write(encrypt(text.toByteArray(Charsets.UTF_8)))
                out.fd.sync()
            }
            if (!tmp.renameTo(File(dir, FILE_NAME))) {
                File(dir, FILE_NAME).delete()
                tmp.renameTo(File(dir, FILE_NAME))
            }
        } catch (_: Exception) {
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** [12-byte IV][AES-GCM ciphertext + tag] */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 12 + 16) { "too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }
}
