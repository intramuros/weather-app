package io.github.intramuros.weatherbuddy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's OpenAI API key, for drawing styles they make. It stays on this
 * phone ([SecretFile]) and is only ever sent to OpenAI.
 *
 * Reads and writes files; call off the main thread.
 */
class ApiKeyStore(context: Context) {
    private val file = SecretFile(context, "openai-key")

    /** The saved key, or `null` if there's none or it can no longer be read. */
    fun get(): String? = file.read()

    fun set(key: String) = file.write(key)

    fun clear() = file.delete()
}

/**
 * A small secret kept in a file named [name]: encrypted with a key of the same name
 * that never leaves the Android Keystore, in a folder that isn't backed up.
 *
 * Reads and writes files; call off the main thread.
 */
internal class SecretFile(context: Context, private val name: String) {
    private val file = File(context.applicationContext.noBackupFilesDir, name)

    /** The secret, or `null` if there's none or it can no longer be read. */
    fun read(): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivSize = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 1, ivSize))
            String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "$name unreadable", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "$name unreadable", e)
            null
        } catch (_: IndexOutOfBoundsException) {
            null // Cut short.
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun write(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        file.writeAtomically { it.writeBytes(byteArrayOf(iv.size.toByte()) + iv + encrypted) }
    }

    fun delete() {
        file.delete()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(name, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val TAG = "SecretFile"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
