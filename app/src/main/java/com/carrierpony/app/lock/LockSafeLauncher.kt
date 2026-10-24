// LockSafeLauncher.kt
// CarrierPony Android
//
// Activity-result launchers that keep working with App Lock on. Opening a
// system picker or the QR scanner stops MainActivity, and onStop engages the
// lock, which used to tear down the screen waiting for the result. Launching
// through rememberLockSafeLauncher tells LockManager a picker is open so the
// lock holds off until it returns (see LockManager.externalActivityStarting).

package com.carrierpony.app.lock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** The app lock, provided at the root by MainActivity. Null in previews. */
val LocalAppLock = staticCompositionLocalOf<LockManager?> { null }

class LockSafeLauncher<I> internal constructor(
    private val inner: ActivityResultLauncher<I>,
    private val lock: LockManager?
) {
    fun launch(input: I) {
        lock?.externalActivityStarting()
        try {
            inner.launch(input)
        } catch (e: ActivityNotFoundException) {
            lock?.externalActivityFinished()
            throw e
        }
    }
}

/** startActivity for things that leave the app without returning a result
 *  (share sheets, links): holds the lock off like a picker until the app is in
 *  front again. */
fun startActivityHoldingLock(context: Context, intent: Intent) {
    val lock = LockManager.get(context)
    lock.holdForExternal()
    try {
        context.startActivity(intent)
    } catch (e: RuntimeException) {
        lock.externalHoldEnded()
        throw e
    }
}

/** Drop-in for rememberLauncherForActivityResult, for launchers that open a
 *  picker or scanner which covers the app. Not needed for permission dialogs,
 *  which only pause the activity. */
@Composable
fun <I, O> rememberLockSafeLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): LockSafeLauncher<I> {
    val lock = LocalAppLock.current
    val currentOnResult by rememberUpdatedState(onResult)
    val inner = rememberLauncherForActivityResult(contract) { result ->
        if (lock?.externalActivityFinished() != false) currentOnResult(result)
    }
    return remember(inner, lock) { LockSafeLauncher(inner, lock) }
}
