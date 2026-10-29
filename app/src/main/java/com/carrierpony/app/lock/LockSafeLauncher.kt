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
import android.net.Uri
import android.provider.DocumentsContract
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
 *  which only pause the activity. When the lock came on while the picker was
 *  open, the result is not used: onDropped gets it instead (a save dialog uses
 *  that to remove the empty file the system already created), and Root shows
 *  a note after unlocking. */
@Composable
fun <I, O> rememberLockSafeLauncher(
    contract: ActivityResultContract<I, O>,
    onDropped: (O) -> Unit = {},
    onResult: (O) -> Unit
): LockSafeLauncher<I> {
    val lock = LocalAppLock.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnDropped by rememberUpdatedState(onDropped)
    val inner = rememberLauncherForActivityResult(contract) { result ->
        if (lock?.externalActivityFinished() != false) {
            currentOnResult(result)
        } else if (result != null && !(result is Collection<*> && result.isEmpty())) {
            currentOnDropped(result)
            lock?.noteDroppedResult()
        }
    }
    return remember(inner, lock) { LockSafeLauncher(inner, lock) }
}

/** Remove a document that a CreateDocument dialog made but nothing was written
 *  to, so an empty file isn't left looking like a saved backup. Best effort:
 *  some providers don't support deleting. */
fun discardCreatedDocument(context: Context, uri: Uri?) {
    if (uri == null) return
    try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        // Left in place; the note after unlocking still says nothing was saved.
    }
}
