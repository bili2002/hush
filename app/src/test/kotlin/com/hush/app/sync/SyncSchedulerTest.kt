package com.hush.app.sync

import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    @BeforeTest
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
    }

    @Test
    fun `initializer enqueues one periodic hush-sync with network constraint`() {
        SyncInitializer().init(ctx as Application)

        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(SyncRequests.PERIODIC_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertTrue(infos.single().tags.contains(SyncWorker::class.java.name))
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)

        // UPDATE: a second init keeps a single request.
        SyncInitializer().init(ctx as Application)
        assertEquals(1, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(SyncRequests.PERIODIC_NAME).get().size)
    }

    @Test
    fun `syncNow enqueues a one-time request with network constraint`() {
        WorkSyncScheduler(ctx).syncNow()

        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(SyncRequests.ONE_TIME_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertTrue(infos.single().tags.contains(SyncWorker::class.java.name))
    }

    @Test
    fun `syncNow replaces a parked request instead of keeping it`() {
        val wm = WorkManager.getInstance(ctx)
        WorkSyncScheduler(ctx).syncNow()
        val first = wm.getWorkInfosForUniqueWork(SyncRequests.ONE_TIME_NAME).get().single().id

        WorkSyncScheduler(ctx).syncNow()

        val infos = wm.getWorkInfosForUniqueWork(SyncRequests.ONE_TIME_NAME).get()
        assertEquals(1, infos.size)
        assertNotEquals(first, infos.single().id)
    }

    @Test
    fun `request builders use a 15 minute period and connected network`() {
        assertEquals(15L * 60_000, SyncRequests.periodic().workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, SyncRequests.oneTime().workSpec.constraints.requiredNetworkType)
    }
}
