// LockManager.kt
// CarrierPony Android
//
// The app lock, ported from iOS Core/App/LockManager.swift. When enabled, the
// UI is gated behind biometrics (with the device credential as fallback) on
// launch and whenever the app returns from the background. The
// sessionPassphrase seam exists for imported passphrase-protected identities
// (key-import phase): a successful unlock will load the passphrase for the
// session, and locking drops it, so it is never resident while locked.

package com.carrierpony.app.lock

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LockManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cp.prefs", Context.MODE_PRIVATE)

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    var sessionPassphrase: String? = null
        private set

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(ENABLED_KEY, false)
        set(value) { prefs.edit().putBoolean(ENABLED_KEY, value).apply() }

    fun biometricsAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    // A system picker (save/open a document, pick photos) or the QR scanner
    // covers the activity, so onStop fires while the user is still mid-task.
    // Locking there replaced the screen that launched it with LockScreen, its
    // result launcher went away with it, and the result was lost: a backup
    // saved through the system dialog came out as an empty file. So the lock
    // holds off while one of these is open, for EXTERNAL_GRACE_MS at most. A
    // user who leaves the picker open and walks away gets the lock when the
    // picker closes, and the result is dropped so nobody else can use it.
    private var externalSince = 0L
    private var externalExpired = false

    /** A picker or scanner is about to open (see rememberLockSafeLauncher). */
    fun externalActivityStarting() {
        externalSince = SystemClock.elapsedRealtime()
        externalExpired = false
    }

    /** Called from onStart: lock now if a picker stayed open past the grace period. */
    fun enforceExternalGrace() {
        if (externalSince == 0L) return
        if (SystemClock.elapsedRealtime() - externalSince <= EXTERNAL_GRACE_MS) return
        externalSince = 0L
        if (appLockEnabled) {
            externalExpired = true
            lockNow()
        }
    }

    /** The picker returned. True when its result may be used. */
    fun externalActivityFinished(): Boolean {
        enforceExternalGrace()
        val usable = !externalExpired && !_isLocked.value
        externalSince = 0L
        externalExpired = false
        return usable
    }

    /** Engage the lock (called on launch and when entering the background).
     *  Skipped while a picker or scanner launched by the app is open. */
    fun lockIfEnabled() {
        if (!appLockEnabled) return
        if (externalSince != 0L) return
        _isLocked.value = true
        sessionPassphrase = null
    }

    fun lockNow() {
        _isLocked.value = true
        sessionPassphrase = null
    }

    /** Hold a passphrase for the current session (e.g. right after import, so
     *  messaging works before the first lock/unlock cycle). */
    fun setSessionPassphrase(passphrase: String?) {
        sessionPassphrase = passphrase
    }

    fun clearSessionPassphrase() {
        sessionPassphrase = null
    }

    /** Prompt biometrics/credential. On success, unlock. (Loading a protected
     *  identity's passphrase from the vault rides with the key-import phase.) */
    fun unlock(activity: FragmentActivity, onResult: (Boolean) -> Unit = {}) {
        if (!biometricsAvailable(activity)) {
            // No biometrics or credential configured: don't strand the user.
            _isLocked.value = false
            onResult(true)
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _isLocked.value = false
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock CarrierPony")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    private companion object {
        const val ENABLED_KEY = "cp.appLock.enabled"
        const val EXTERNAL_GRACE_MS = 2 * 60 * 1000L
    }
}
