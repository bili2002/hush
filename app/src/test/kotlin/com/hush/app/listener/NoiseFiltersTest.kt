package com.hush.app.listener

import android.app.Notification
import com.hush.app.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoiseFiltersTest {

    private val own = "com.hush.app"

    @Test
    fun `own package is noise`() {
        assertTrue(NoiseFilters.isOwnPackage(sbn(packageName = own), own))
        assertFalse(NoiseFilters.isOwnPackage(sbn(packageName = "com.example.alpha"), own))
        assertEquals(NoiseFilters.Reason.OWN_PACKAGE, NoiseFilters.reason(sbn(packageName = own), Settings(), own))
    }

    @Test
    fun `group summary is noise`() {
        assertTrue(NoiseFilters.isGroupSummary(sbn(notification(flags = Notification.FLAG_GROUP_SUMMARY))))
        assertFalse(NoiseFilters.isGroupSummary(sbn(notification())))
        assertEquals(
            NoiseFilters.Reason.GROUP_SUMMARY,
            NoiseFilters.reason(sbn(notification(flags = Notification.FLAG_GROUP_SUMMARY)), Settings(), own),
        )
    }

    @Test
    fun `ongoing and foreground-service are noise unless logOngoing`() {
        val ongoing = sbn(notification(flags = Notification.FLAG_ONGOING_EVENT))
        val fgs = sbn(notification(flags = Notification.FLAG_FOREGROUND_SERVICE))
        assertTrue(NoiseFilters.isOngoing(ongoing, Settings()))
        assertTrue(NoiseFilters.isOngoing(fgs, Settings()))
        assertFalse(NoiseFilters.isOngoing(sbn(), Settings()))

        val optedIn = Settings(logOngoing = true)
        assertFalse(NoiseFilters.isOngoing(ongoing, optedIn))
        assertFalse(NoiseFilters.isOngoing(fgs, optedIn))
        assertNull(NoiseFilters.reason(ongoing, optedIn, own))
        assertEquals(NoiseFilters.Reason.ONGOING, NoiseFilters.reason(ongoing, Settings(), own))
    }

    @Test
    fun `excluded package is noise`() {
        val settings = Settings(excludedPackages = setOf("com.example.beta"))
        assertTrue(NoiseFilters.isExcluded(sbn(packageName = "com.example.beta"), settings))
        assertFalse(NoiseFilters.isExcluded(sbn(packageName = "com.example.alpha"), settings))
        assertEquals(NoiseFilters.Reason.EXCLUDED, NoiseFilters.reason(sbn(packageName = "com.example.beta"), settings, own))
    }

    @Test
    fun `an ordinary notification passes every filter`() {
        assertNull(NoiseFilters.reason(sbn(), Settings(), own))
    }
}
