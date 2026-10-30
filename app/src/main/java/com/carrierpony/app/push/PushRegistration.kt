// PushRegistration.kt
// CarrierPony Android
//
// Flavor-independent push types. The play flavor registers an FCM token; the
// FOSS flavor registers a UnifiedPush endpoint plus the Web Push keys the push
// gateway encrypts each wake to. AppModel hands either one to the gateway the
// same way, so it never needs to know which flavor it is running in.

package com.carrierpony.app.push

/** What gets registered with the push gateway. For UnifiedPush, token is the
 *  endpoint URL and p256dh / auth are its Web Push keys (base64url). */
data class PushRegistration(
    val token: String,
    val platform: String,
    val p256dh: String? = null,
    val auth: String? = null
)

/** How this install hears about new messages while the app is closed. */
enum class PushMode { FCM, UNIFIED_PUSH, BACKGROUND_CHECK }

/** An installed UnifiedPush distributor. */
data class PushDistributor(val packageName: String, val label: String)

/** Whether a CarrierPony screen is in the foreground. Background work reads it to
 *  stay out of the way of the live poll. Set by AppModel with the messaging
 *  lifecycle. */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
