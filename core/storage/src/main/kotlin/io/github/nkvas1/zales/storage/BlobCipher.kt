// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import io.github.nkvas1.zales.common.ZalesLog
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption of small blobs. */
public interface BlobCipher {
    public fun encrypt(plaintext: ByteArray): ByteArray

    /** Throws [StorageUnavailableException] if the blob cannot be authenticated or the key is gone. */
    public fun decrypt(blob: ByteArray): ByteArray
}

/** The key store cannot be used — for example after the lock screen was removed. Maps to INT-03. */
public class StorageUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-256-GCM with a non-exportable key inside Android Keystore, in StrongBox
 * when the device has one.
 *
 * The key is deliberately **not** bound to user authentication or to an
 * unlocked device: the tunnel must reconnect by itself while the phone sits
 * locked in a pocket, and always-on VPN starts it after a reboot with nobody
 * looking. Viewing a key in the UI is gated by a biometric prompt instead —
 * see docs/SECURITY.md §2.
 *
 * Blob layout: `version(1) | iv(12) | ciphertext+tag`.
 */
public class KeystoreBlobCipher(private val alias: String = DEFAULT_ALIAS) : BlobCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray = guarded("encrypt") {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected IV length ${iv.size}" }
        val sealed = cipher.doFinal(plaintext)
        ByteBuffer.allocate(1 + iv.size + sealed.size)
            .put(FORMAT_VERSION)
            .put(iv)
            .put(sealed)
            .array()
    }

    override fun decrypt(blob: ByteArray): ByteArray = guarded("decrypt") {
        if (blob.size <= 1 + IV_LENGTH || blob[0] != FORMAT_VERSION) {
            throw StorageUnavailableException("stored blob has an unknown format")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 1, IV_LENGTH))
        cipher.doFinal(blob, 1 + IV_LENGTH, blob.size - 1 - IV_LENGTH)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        // A dedicated security chip only exists from Android 9, and only on
        // some devices; everywhere else the key still never leaves the TEE.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) inStrongBoxIfPresent() else generate(false)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun inStrongBoxIfPresent(): SecretKey = try {
        generate(strongBox = true)
    } catch (_: StrongBoxUnavailableException) {
        ZalesLog.info(ZalesLog.TAG_UI, "no StrongBox on this device, using the TEE")
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private inline fun <T> guarded(operation: String, block: () -> T): T = try {
        block()
    } catch (e: StorageUnavailableException) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw StorageUnavailableException("keystore $operation failed: ${e::class.simpleName}", e)
    } catch (e: IllegalStateException) {
        throw StorageUnavailableException("keystore $operation failed: ${e.message}", e)
    }

    public companion object {
        public const val DEFAULT_ALIAS: String = "zales.keys.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_LENGTH = 12
        private const val FORMAT_VERSION: Byte = 1
    }
}
