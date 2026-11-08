package com.hush.app.focus

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SlotMathTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val sofia: ZoneId = ZoneId.of("Europe/Sofia")

    private fun at(zone: ZoneId, text: String): Long = ZonedDateTime.of(LocalDateTime.parse(text), zone).toInstant().toEpochMilli()

    private fun check(zone: ZoneId, now: String, slot: Int, expected: String) {
        val actual = nextSlot(at(zone, now), slot, zone)
        assertEquals(at(zone, expected), actual, "now=$now slot=$slot → ${java.time.Instant.ofEpochMilli(actual).atZone(zone)}")
    }

    @Test
    fun `table - mid-slot now rounds up to the next boundary`() {
        check(utc, "2026-05-10T10:17:33", 60, "2026-05-10T11:00:00")
        check(utc, "2026-05-10T10:17:33", 30, "2026-05-10T10:30:00")
        check(utc, "2026-05-10T10:44:00", 30, "2026-05-10T11:00:00")
        check(utc, "2026-05-10T10:17:33", 15, "2026-05-10T10:30:00")
        check(utc, "2026-05-10T10:17:33", 120, "2026-05-10T12:00:00")
        check(utc, "2026-05-10T11:17:33", 120, "2026-05-10T12:00:00")
        check(utc, "2026-05-10T23:59:59", 60, "2026-05-11T00:00:00")
    }

    @Test
    fun `exactly on a boundary returns the following boundary`() {
        check(utc, "2026-05-10T10:00:00", 60, "2026-05-10T11:00:00")
        check(utc, "2026-05-10T10:30:00", 30, "2026-05-10T11:00:00")
        check(utc, "2026-05-10T10:45:00", 15, "2026-05-10T11:00:00")
        check(utc, "2026-05-10T12:00:00", 120, "2026-05-10T14:00:00")
        check(utc, "2026-05-10T00:00:00", 60, "2026-05-10T01:00:00")
    }

    @Test
    fun `spring forward in Sofia - 03-00 does not exist, next boundary is 04-00 local`() {
        // 2026-03-29 03:00 EET → 04:00 EEST. 02:30 local is the last half hour before the gap.
        val now = at(sofia, "2026-03-29T02:30:00")
        val next = nextSlot(now, 60, sofia)
        val local = java.time.Instant.ofEpochMilli(next).atZone(sofia)
        assertEquals(4, local.hour)
        assertEquals(0, local.minute)
        assertEquals(30L * 60_000, next - now, "one real half hour elapses across the gap")
    }

    @Test
    fun `fall back in Sofia - result is always strictly after now and on a wall-clock boundary`() {
        // 2026-10-25 04:00 EEST → 03:00 EET: the 03:xx hour happens twice.
        val firstThree = ZonedDateTime.of(LocalDateTime.parse("2026-10-25T03:20:00"), sofia).toInstant().toEpochMilli()
        val secondThree = firstThree + 60 * 60_000 // same wall clock, one real hour later

        for (now in listOf(firstThree, secondThree)) {
            for (slot in listOf(60, 30, 15, 120)) {
                val next = nextSlot(now, slot, sofia)
                assertTrue(next > now, "slot=$slot must be strictly after now")
                val local = java.time.Instant.ofEpochMilli(next).atZone(sofia)
                assertEquals(0, local.second)
                assertEquals(0, (local.hour * 60 + local.minute) % slot, "slot=$slot lands on a wall-clock boundary")
                assertTrue(next - now <= slot * 60_000L + 60 * 60_000L, "slot=$slot is at most one slot plus the repeated hour away")
            }
        }
        // From the second 03:20 the hour slot is 04:00 EET, 40 real minutes later.
        assertEquals(40L * 60_000, nextSlot(secondThree, 60, sofia) - secondThree)
    }

    @Test
    fun `rejects non-positive slot`() {
        assertFailsWith<IllegalArgumentException> { nextSlot(0, 0, utc) }
        assertFailsWith<IllegalArgumentException> { nextSlot(0, -15, utc) }
    }
}
