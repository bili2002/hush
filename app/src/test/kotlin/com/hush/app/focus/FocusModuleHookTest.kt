package com.hush.app.focus

import android.app.NotificationManager
import android.content.Context
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The Sync safety-net hook must take the non-forced path: not-yet-due rows stay held. */
@RunWith(RobolectricTestRunner::class)
class FocusModuleHookTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val originalZone = TimeZone.getDefault()
    private val now = 1_700_000_000_000L // 22:13:20Z; current 60-minute slot opened at 22:00

    @BeforeTest fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @AfterTest fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun hookDeliversOnlyWhatIsDue() = runTest {
        val clock = fixedClock(now)
        val repo = testRepository(clock)
        val settings = FakeSettingsStore(Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha")))
        val hook = FocusModule.deliverDueHook(Deliverer(ctx, repo, settings, clock, DeliveryScheduler(ctx, settings)))

        repo.insert(event("fresh", postTime = now - 60_000L, held = true))
        hook.deliverDue()
        assertEquals(0, shadowOf(nm).allNotifications.size, "not due: a forced path would have posted")
        assertEquals(1, repo.selectHeldUndelivered().size)

        repo.insert(event("old", postTime = now - 2 * 60 * 60_000L, held = true))
        hook.deliverDue()
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals(0, repo.selectHeldUndelivered().size)
    }
}
