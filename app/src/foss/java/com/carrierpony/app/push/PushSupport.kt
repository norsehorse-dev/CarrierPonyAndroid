// PushSupport.kt
// CarrierPony Android — FOSS FLAVOR
//
// The F-Droid / IzzyOnDroid build carries no Firebase and no Google Play
// Services: there is nothing to receive an FCM wake, so this is a same-signature
// no-op counterpart to the play PushSupport. Message delivery on this flavor is
// covered by foreground polling and the app's other transports (Nostr, LAN/WAN
// direct, SMS), so no push token is ever fetched or registered.
//
// Keeping the notification channel available means a future FOSS-friendly push
// path (e.g. UnifiedPush) could reuse PushNotifier without touching AppModel.

package com.carrierpony.app.push

import android.content.Context

object PushSupport {
    /** No push transport on the FOSS build; delivery falls back to polling. */
    const val available = false

    fun ensureChannel(context: Context) = PushNotifier.ensureChannel(context)

    /** No FCM service to wire on this flavor. */
    fun install(onNewToken: (String) -> Unit, onWake: () -> Boolean) {
        // no-op: nothing receives push wakes on the FOSS build.
    }

    /** No push token exists on this flavor. */
    fun fetchToken(onToken: (String) -> Unit) {
        // no-op: polling covers delivery.
    }
}
