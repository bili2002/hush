package com.hush.app.focus

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Epoch millis of the next slot boundary strictly after [nowMillis], in wall-clock time of [zone].
 * Slots start at local midnight and repeat every [slotMinutes]: 60 → top of each hour,
 * 30 → :00/:30, 15 → quarter hours, 120 → even hours. Wall-clock based, so boundaries stay
 * on :00 across a DST change: on a fall-back day a wall-clock boundary that occurs twice
 * yields both instants and the earliest one after now wins; across a spring-forward gap the
 * missing boundary collapses onto the next real instant.
 */
fun nextSlot(nowMillis: Long, slotMinutes: Int, zone: ZoneId): Long {
    require(slotMinutes > 0) { "slotMinutes must be positive, was $slotMinutes" }
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    var candidate: LocalDateTime = today.atStartOfDay()
    // Local midnight is always a boundary. For an interval that divides the day it is one anyway; for one
    // that does not (a custom 100 or 1000 minutes) it is where the grid restarts, so that a boundary computed
    // before midnight and one computed after it are on the same grid.
    var best = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().takeIf { it > nowMillis } ?: Long.MAX_VALUE
    while (true) {
        candidate = candidate.plusMinutes(slotMinutes.toLong())
        val earlier = ZonedDateTime.of(candidate, zone) // earlier offset in an overlap; shifted forward in a gap
        val earlierMillis = earlier.toInstant().toEpochMilli()
        // Earlier-offset instants grow with the candidate, so nothing after this one can beat `best`.
        if (earlierMillis >= best) return best
        val laterMillis = earlier.withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        for (ms in longArrayOf(earlierMillis, laterMillis)) {
            if (ms > nowMillis && ms < best) best = ms
        }
    }
}
