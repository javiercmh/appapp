package com.wynh.appapp.ai.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedCredentialStore(
    context: Context,
    private val keyAlias: String = ALIAS,
    fileName: String = "appapp-chatgpt-credentials.enc"
) : CredentialStore {
    private val file = AtomicFile(java.io.File(context.noBackupFilesDir, fileName))
    private val gson = Gson()

    @Synchronized
    override fun read(): CredentialSnapshot {
        if (!file.baseFile.exists()) {
            return CredentialSnapshot("urn:uuid:${UUID.randomUUID()}").also(::write)
        }
        try {
            val bytes = file.readFully()
            require(bytes.size in 30..1_048_576 && bytes[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
            val result = gson.fromJson(plain, CredentialSnapshot::class.java)
            require(result.hostId.startsWith("urn:uuid:") && result.registrations.map { it.clientId }.distinct().size == result.registrations.size)
            return result
        } catch (_: Exception) {
            // Never discard an unreadable record silently: it may contain a renewable session.
            throw AuthFailure("Saved credentials could not be unlocked. Reset local sign-in data and sign in again. You can disconnect the old registration in ChatGPT settings.")
        }
    }

    @Synchronized
    override fun write(snapshot: CredentialSnapshot) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(gson.toJson(snapshot).toByteArray())
            val stream = file.startWrite()
            try { stream.write(encrypted); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        } catch (_: Exception) { throw AuthFailure("Credentials could not be saved securely. Please try sign-in again.") }
    }

    @Synchronized
    override fun reset() {
        file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(keyAlias)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val ALIAS = "appapp.chatgpt.credentials.v1"
        private val AAD = "AppApp credentials v1".toByteArray()
    }
}
