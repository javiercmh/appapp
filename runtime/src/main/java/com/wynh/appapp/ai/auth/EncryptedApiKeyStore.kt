package com.wynh.appapp.ai.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Native-only provider secret (API key, or a custom endpoint's URL and key); never backed up or included in a workspace. */
class EncryptedApiKeyStore(
    context: Context,
    private val keyAlias: String = ALIAS,
    fileName: String = "appapp-openrouter-key.enc",
    private val label: String = "OpenRouter key",
    private val aad: ByteArray = AAD
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    @Synchronized fun read(): String? {
        if (!file.baseFile.exists()) return null
        try {
            val bytes = file.readFully()
            require(bytes.size in 30..16_384 && bytes[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            return String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { throw AuthFailure("Saved $label could not be unlocked. Remove it and add it again.") }
    }
    @Synchronized fun write(value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        } catch (_: Exception) { throw AuthFailure("The $label could not be saved securely.") }
    }
    @Synchronized fun reset() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    companion object {
        const val CUSTOM_ALIAS = "appapp.custom.endpoint.v1"
        val CUSTOM_AAD = "AppApp custom endpoint v1".toByteArray()
        private const val ALIAS = "appapp.openrouter.key.v1"
        private val AAD = "AppApp OpenRouter key v1".toByteArray()
    }
}
