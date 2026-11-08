package com.hush.app.ui.history

import android.content.Intent
import com.hush.app.ui.MainActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DigestLinkTest {

    @Test
    fun `held filter with delivered_at parses`() {
        val intent = Intent()
            .putExtra(MainActivity.EXTRA_FILTER, MainActivity.FILTER_HELD)
            .putExtra(MainActivity.EXTRA_DELIVERED_AT, 1_700_000_000_000L)

        assertEquals(DigestLink(1_700_000_000_000L), DigestLink.parse(intent))
    }

    @Test
    fun `launcher intent, other filter, or missing delivered_at is not a digest link`() {
        assertNull(DigestLink.parse(null))
        assertNull(DigestLink.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(DigestLink.parse(Intent().putExtra(MainActivity.EXTRA_FILTER, "other").putExtra(MainActivity.EXTRA_DELIVERED_AT, 1L)))
        assertNull(DigestLink.parse(Intent().putExtra(MainActivity.EXTRA_FILTER, MainActivity.FILTER_HELD)))
    }
}
