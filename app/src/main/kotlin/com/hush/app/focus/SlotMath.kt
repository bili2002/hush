package com.hush.app.focus

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Epoch millis of the next slot boundary strictly after [nowMillis], in wall-clock time of [zone].
 * Slots start at local midnight and repeat every [slotMinutes]: 60 → top of each hour,
 * 30 → :00/:30, 15 → quarter hours, 120 → even hours. Wall-clock based, so boundaries stay
 * on :00 across a DST change.
 */
fun nextSlot(nowMillis: Long, slotMinutes: Int, zone: ZoneId): Long {
    require(slotMinutes > 0) { "slotMinutes must be positive, was $slotMinutes" }
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    var candidate: LocalDateTime = now.toLocalDate().atStartOfDay()
    while (true) {
        candidate = candidate.plusMinutes(slotMinutes.toLong())
        val zoned = ZonedDateTime.of(candidate, zone)
        if (zoned.toInstant().toEpochMilli() > nowMillis) return zoned.toInstant().toEpochMilli()
    }
}
