// IdentityStore.kt
// CarrierPony Android
//
// Persistence for the single primary identity, the Android counterpart of the
// iOS Keychain item in Core/App/Identity.swift. The identity record (which
// contains the secret key) is sealed with AES-256-GCM under a key that lives
// in the Android Keystore — generated on-device, non-exportable, and never
// present in app memory as raw bytes. The sealed blob and its IV sit in a
// private SharedPreferences file; without the Keystore key they are noise.
//
// Matching the iOS accessibility choice (afterFirstUnlockThisDeviceOnly): the
// Keystore key is hardware-bound to this device, so the identity does not
// travel with a raw prefs backup. allowBackup semantics for the rest of the
// app's data are unaffected.

package com.carrierpony.app.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

class IdentityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored identity, null when none exists. Throws IdentityStoreException
     *  when a blob exists but cannot be opened or parsed. */
    fun load(): Identity? {
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val blobB64 = prefs.getString(KEY_BLOB, null) ?: return null
        val json = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(TAG_BITS, Base64.Default.decode(ivB64))
            )
            String(cipher.doFinal(Base64.Default.decode(blobB64)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IdentityStoreException("identity unseal failed: ${e.message}")
        }
        return IdentityRecord.decode(json)
            ?: throw IdentityStoreException("identity record malformed")
    }

    fun save(identity: Identity) {
        val plaintext = IdentityRecord.encode(identity).toByteArray(Charsets.UTF_8)
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            cipher.iv to cipher.doFinal(plaintext)
        } catch (e: Exception) {
            throw IdentityStoreException("identity seal failed: ${e.message}")
        }
        prefs.edit()
            .putString(KEY_IV, Base64.Default.encode(sealed.first))
            .putString(KEY_BLOB, Base64.Default.encode(sealed.second))
            .apply()
    }

    fun delete() {
        prefs.edit().remove(KEY_IV).remove(KEY_BLOB).apply()
    }

    // ── Keystore ───────────────────────────────────────────────────────

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "cp.identity"
        const val KEY_IV = "iv"
        const val KEY_BLOB = "blob"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.carrierpony.identity.wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
