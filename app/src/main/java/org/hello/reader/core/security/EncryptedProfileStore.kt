package org.hello.reader.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Opens the `EncryptedSharedPreferences` file backing [SecurityVaultImpl].
 *
 * Values are AES-256-GCM encrypted and keys AES-256-SIV encrypted with a Tink
 * keyset that is itself wrapped by a non-exportable Android Keystore master key.
 */
object EncryptedProfileStore {

    const val PREFS_NAME = "hello_secure_vault"
    private const val TAG = "EncryptedProfileStore"

    /** Performs Keystore I/O: call off the main thread. */
    fun open(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return try {
            create(appContext)
        } catch (e: GeneralSecurityException) {
            recover(appContext, e)
        } catch (e: IOException) {
            recover(appContext, e)
        }
    }

    /**
     * The keyset can become undecryptable when the Keystore master key is lost
     * (e.g. a lock-screen reset on some OEM builds, or a restored backup on a new
     * device). The stored tokens are unrecoverable in that case, so the file is
     * wiped and recreated; the user signs in again rather than crash-looping.
     */
    private fun recover(context: Context, cause: Exception): SharedPreferences {
        Log.w(TAG, "Secure vault unreadable; resetting stored server profiles", cause)
        context.deleteSharedPreferences(PREFS_NAME)
        return create(context)
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
