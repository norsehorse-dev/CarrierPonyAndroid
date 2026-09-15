// LinkText.kt
// CarrierPony Android
//
// Tap-to-open links in message text, the Android counterpart of the iOS
// NSDataDetector + in-app Safari behavior (Core/UI/ConversationView.swift).
// URLs in a message are underlined and tinted, and a tap opens them in a Chrome
// Custom Tab so the link stays inside the app instead of kicking out to the
// system browser. No link previews in 2.0 — just the tappable link.

package com.carrierpony.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** Open a URL in an in-app browser (Custom Tab), falling back to the system
 *  browser if no Custom Tabs provider handles it. Silently does nothing if the
 *  device has no browser at all. */
fun openInAppBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e2: ActivityNotFoundException) {
            // No handler for the link; nothing to open.
        }
    }
}

/** Build an AnnotatedString from raw message text with every web URL turned
 *  into a tappable, underlined link tinted [linkColor]. Bare-domain matches
 *  with no scheme get https:// prepended so they open. [onOpen] receives the
 *  resolved href. If there are no links the text is returned as a plain span. */
fun linkifiedMessage(text: String, linkColor: Color, onOpen: (String) -> Unit): AnnotatedString {
    val matcher = Patterns.WEB_URL.matcher(text)
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var last = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (start > last) append(text.substring(last, start))
            val raw = text.substring(start, end)
            val href = if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) raw else "https://$raw"
            withLink(LinkAnnotation.Url(href, styles) { onOpen(href) }) {
                append(raw)
            }
            last = end
        }
        if (last < text.length) append(text.substring(last))
    }
}
