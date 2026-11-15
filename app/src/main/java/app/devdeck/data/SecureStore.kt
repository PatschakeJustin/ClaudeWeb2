package app.devdeck.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores small text blobs (connections incl. passwords, API key) encrypted with an AES-256-GCM key
 * that lives in the Android Keystore and never leaves the device.
 */
class SecureStore(context: Context) {
    private val dir = File(context.filesDir, "secure").apply { mkdirs() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun write(name: String, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + data)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).delete()
            tmp.renameTo(File(dir, name))
        }
    }

    @Synchronized
    fun read(name: String): String? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    companion object { private const val ALIAS = "devdeck-secrets" }
}
