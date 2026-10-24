// MessageClipboard.kt
// CarrierPony Android
//
// Copy a message's text from the long-press menu, for both 1:1 and group
// bubbles. People paste ciphertext from a keyboard encryption tool into a
// message and the other side copies it back out to decrypt, so both sent and
// received bubbles need it. On Android 13+ the clip is marked sensitive so the
// system clipboard preview doesn't show the text; those versions also show
// their own "copied" confirmation, so the toast is only for older ones.

package com.carrierpony.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

internal fun copyMessageText(context: Context, text: String, copiedLabel: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("CarrierPony", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
    }
}
