package com.hush.app.listener

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.text.SpannableString
import android.text.style.StyleSpan
import com.hush.app.db.EventType
import com.hush.app.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationMapperTest {

    private val mapper = NotificationMapper(appContext())
    private val settings = Settings()

    @Test
    fun `plain notification maps title, text, subText, channel, category, flags, importance and key`() {
        val s = sbn(notification(title = "Hello", text = "World", subText = "sub", category = Notification.CATEGORY_MESSAGE), id = 7, tag = "t")
        val e = assertNotNull(mapper.map(s, importance = 3, settings = settings))

        assertEquals("Hello", e.title)
        assertEquals("World", e.text)
        assertEquals("sub", e.subText)
        assertNull(e.bigText)
        assertEquals("synthetic-channel", e.channelId)
        assertEquals(Notification.CATEGORY_MESSAGE, e.category)
        assertEquals(s.notification.flags, e.flags)
        assertEquals(3, e.importance)
        assertEquals(s.key, e.key)
        assertEquals("com.example.alpha", e.packageName)
        assertEquals(s.postTime, e.postTime)
        assertEquals(EventType.POSTED, e.event)
        assertNull(e.removedReason)
        assertEquals(false, e.held)
    }

    @Test
    fun `bigText comes from EXTRA_BIG_TEXT`() {
        val e = assertNotNull(mapper.map(sbn(notification(bigText = "the long form")), null, settings))
        assertEquals("the long form", e.bigText)
    }

    @Test
    fun `textLines are joined with newlines into bigText`() {
        val e = assertNotNull(mapper.map(sbn(notification(textLines = arrayOf("one", "two", "three"))), null, settings))
        assertEquals("one\ntwo\nthree", e.bigText)
    }

    @Test
    fun `MessagingStyle messages and historic messages become sender-prefixed lines`() {
        val n = messagingNotification(
            messages = listOf("Ann" to "hi", null to "anonymous"),
            historic = listOf("Bob" to "earlier"),
        )
        val e = assertNotNull(mapper.map(sbn(n), null, settings))
        assertEquals("Ann: hi\n?: anonymous\nBob: earlier", e.bigText)
    }

    @Test
    fun `Spannable extras are stored as plain strings`() {
        val spanned = SpannableString("bold title").also { it.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 4, 0) }
        val e = assertNotNull(mapper.map(sbn(notification(title = spanned, text = SpannableString("plain"))), null, settings))
        assertEquals("bold title", e.title)
        assertEquals("plain", e.text)
        assertTrue(e.title is String)
    }

    @Test
    fun `bigText is capped at 4 KB`() {
        // Written straight into the extras: the platform Builder already clips BigTextStyle text to
        // 1024 chars on this SDK, but EXTRA_TEXT_LINES / MessagingStyle can still add up past 4 KB.
        val n = notification().also { it.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "x".repeat(10_000)) }
        val e = assertNotNull(mapper.map(sbn(n), null, settings))
        assertEquals(NotificationMapper.MAX_BIG_TEXT_CHARS, e.bigText!!.length)
    }

    @Test
    fun `app label falls back to the package name when the package is not visible`() {
        val e = assertNotNull(mapper.map(sbn(packageName = "com.example.invisible"), null, settings))
        assertEquals("com.example.invisible", e.appLabel)
    }

    @Test
    fun `app label is read from PackageManager when the package is installed`() {
        val pm = appContext().packageManager
        shadowOf(pm).installPackage(
            PackageInfo().apply {
                packageName = "com.example.labelled"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.example.labelled"
                    nonLocalizedLabel = "Labelled App"
                }
            },
        )
        val e = assertNotNull(mapper.map(sbn(packageName = "com.example.labelled"), null, settings))
        assertEquals("Labelled App", e.appLabel)
    }

    @Test
    fun `id is deterministic over key, postTime and event type`() {
        val s = sbn(postTime = 42L)
        val a = assertNotNull(mapper.map(s, 1, settings))
        val b = assertNotNull(mapper.map(sbn(notification(title = "different text"), postTime = 42L), 2, settings))
        assertEquals(a.id, b.id) // same key + postTime: same id regardless of content/importance
        assertNotEquals(a.id, mapper.map(sbn(postTime = 43L), 1, settings)!!.id)
        assertNotEquals(a.id, mapper.mapRemoved(s, 2, settings)!!.id)
        assertEquals(NotificationMapper.eventId(s.key, 42L, EventType.POSTED), a.id)
    }

    @Test
    fun `filtered notifications map to null`() {
        assertNull(mapper.map(sbn(packageName = appContext().packageName), null, settings))
        assertNull(mapper.map(sbn(notification(flags = Notification.FLAG_GROUP_SUMMARY)), null, settings))
        assertNull(mapper.map(sbn(notification(flags = Notification.FLAG_ONGOING_EVENT)), null, settings))
        assertNotNull(mapper.map(sbn(notification(flags = Notification.FLAG_ONGOING_EVENT)), null, Settings(logOngoing = true)))
        assertNull(mapper.map(sbn(packageName = "com.example.beta"), null, Settings(excludedPackages = setOf("com.example.beta"))))
    }

    @Test
    fun `mapRemoved carries reason and no text, and applies the same filters`() {
        val s = sbn(notification(title = "secret", text = "secret", bigText = "secret"))
        val e = assertNotNull(mapper.mapRemoved(s, reason = 2, settings = settings))
        assertEquals(EventType.REMOVED, e.event)
        assertEquals(2, e.removedReason)
        assertNull(e.title)
        assertNull(e.text)
        assertNull(e.bigText)
        assertNull(e.subText)
        assertNull(e.importance)
        assertEquals(false, e.held)
        assertEquals(s.key, e.key)
        assertEquals(s.postTime, e.postTime)

        assertNull(mapper.mapRemoved(sbn(notification(flags = Notification.FLAG_GROUP_SUMMARY)), 2, settings))
        assertNull(mapper.mapRemoved(sbn(packageName = appContext().packageName), 2, settings))
    }
}
