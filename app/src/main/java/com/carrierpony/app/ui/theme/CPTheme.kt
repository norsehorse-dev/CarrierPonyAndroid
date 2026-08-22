// CPTheme.kt
// CarrierPony Android
//
// Brand gradients and shared surface lookups, the Compose counterpart of iOS
// CPTheme. The warm gradient is reserved for brand moments (avatar, send
// button, empty state); flat accent carries the user's own message bubbles so
// a long thread stays calm.

package com.carrierpony.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object CPTheme {

    /** The icon background gradient (gold -> coral -> red), same diagonal axis. */
    val brand: Brush = Brush.linearGradient(colors = listOf(CPGold, CPCoral, CPRed))

    /** A tighter coral-to-red used on the send button, matching the horse fill. */
    val send: Brush = Brush.linearGradient(colors = listOf(CPAccentLite, CPAccentDeep))

    val accent: Color = CPAccent

    val incomingBubble: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    val composerField: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant
}
