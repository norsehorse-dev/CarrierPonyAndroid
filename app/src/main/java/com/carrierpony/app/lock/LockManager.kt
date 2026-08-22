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

    /** Engage the lock (called on launch and when entering the background). */
    fun lockIfEnabled() {
        if (!appLockEnabled) return
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
    }
}
