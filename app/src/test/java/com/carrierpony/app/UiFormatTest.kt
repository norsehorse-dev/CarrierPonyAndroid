// UiFormatTest.kt
// CarrierPony Android
//
// The JVM-testable slice of the UI: fingerprint shortening, size labels,
// disappearing-timer labels, day bucketing, and relative time labels.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.ui.UiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class UiFormatTest {

    private val fpr = Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8D")!!

    @Test
    fun shortFingerprintGroupsLastEight() {
        assertEquals("2B6C 5A8D", UiFormat.shortFingerprint(fpr))
    }

    @Test
    fun sizeLabels() {
        assertEquals("512 B", UiFormat.sizeLabel(512))
        assertEquals("48 KB", UiFormat.sizeLabel(48 * 1024))
        assertEquals("3.2 MB", UiFormat.sizeLabel((3.2 * 1024 * 1024).toLong()))
    }

    @Test
    fun disappearingLabelOnlyWhenImminent() {
        val now = 1_000_000L
        assertNull(UiFormat.disappearingLabel(now + 2 * 86_400, now))   // 2 days: hidden
        assertNull(UiFormat.disappearingLabel(now - 10, now))           // expired: hidden
        assertEquals("3h", UiFormat.disappearingLabel(now + 3 * 3600 + 100, now))
        assertEquals("12m", UiFormat.disappearingLabel(now + 12 * 60 + 5, now))
        assertEquals("1m", UiFormat.disappearingLabel(now + 30, now))   // floor at one minute
    }

    @Test
    fun listTimeLabelBuckets() {
        // Anchor: now at some midday moment.
        val now = 1_767_000_000L
        val today = UiFormat.startOfDay(now)
        assertEquals("Yesterday", UiFormat.listTimeLabel(today - 3600, now, Locale.US))
        // Within a week: a weekday abbreviation (3 letters in US locale).
        val threeDaysAgo = UiFormat.listTimeLabel(now - 3 * 86_400, now, Locale.US)
        assertEquals(3, threeDaysAgo.length)
        // Older: "MMM d".
        val old = UiFormat.listTimeLabel(now - 30 * 86_400, now, Locale.US)
        assertEquals(true, old.contains(" "))
    }

    @Test
    fun dayLabelsForTodayAndYesterday() {
        val now = 1_767_000_000L
        val today = UiFormat.startOfDay(now)
        assertEquals("Today", UiFormat.dayLabel(today, now, Locale.US))
        assertEquals("Yesterday", UiFormat.dayLabel(today - 86_400, now, Locale.US))
    }
}
