package com.hush.app.focus

import com.hush.app.settings.Settings
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusProfilesTest {
    private val alpha = "com.example.alpha"
    private val beta = "com.example.beta"
    private val utc: ZoneId = ZoneId.of("UTC")

    /** One group "g" on 15 min holding alpha; beta ungrouped; default 60; one chat rule. */
    private val base = Settings(
        slotMinutes = 60,
        holdPackages = setOf(alpha, beta),
        rules = FocusRules(
            categories = listOf(FocusCategory("g", "Group", 15), FocusCategory("h", "Other", null)),
            appCategory = mapOf(alpha to "g"),
            chatRules = listOf(ChatRule(alpha, "c1", "Group one", 240)),
            seeded = true,
        ),
    )

    private fun at(text: String, zone: ZoneId = utc): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // ---- profiles ----

    @Test
    fun `settings without profiles are one profile called Default holding the live configuration`() {
        val only = base.profileList.single()

        assertEquals(DEFAULT_PROFILE_ID, only.id)
        assertEquals(ProfileConfig(60, mapOf("g" to 15), base.rules.chatRules), only.config)
        assertEquals(only, base.activeProfile)
        assertEquals(base, base.withLiveConfig(base.liveConfig), "taking the live configuration and putting it back changes nothing")
        assertEquals(base, base.viewOf(DEFAULT_PROFILE_ID))
        assertEquals(base, base.viewOf("no-such-profile"))
    }

    @Test
    fun `a new profile is empty or a copy, and switching swaps the throttles but not the groups or the hold list`() {
        val two = base.withProfileAdded("p2", "  Evening ", copyOf = null, oneTime = false)
        assertEquals(listOf("Default", "Evening"), two.profileList.map { it.name })
        assertEquals(base.liveConfig, two.liveConfig, "adding one does not touch what is in force")

        val evening = two.withActiveProfile("p2")
        assertEquals("p2", evening.activeProfile.id)
        assertEquals(60, evening.throttleMinutes(alpha, null), "an empty profile: every group on the default")
        assertNull(evening.chatRule(alpha, "c1"))
        assertEquals(base.holdPackages, evening.holdPackages)
        assertEquals(base.rules.appCategory, evening.rules.appCategory)
        assertEquals(listOf("g", "h"), evening.rules.categories.map { it.id })

        val tuned = evening.withCategoryMinutes("g", MUTED).copy(slotMinutes = 30)
        val back = tuned.withActiveProfile(DEFAULT_PROFILE_ID)
        assertEquals(base.liveConfig, back.liveConfig, "the first profile has exactly what it had")
        assertEquals(ProfileConfig(30, mapOf("g" to MUTED)), back.profile("p2")?.config, "and the second kept its edits")

        val copy = back.withProfileDnd(DEFAULT_PROFILE_ID, true).withProfileAdded("p3", "Copy", copyOf = DEFAULT_PROFILE_ID, oneTime = false)
        assertEquals(base.liveConfig, copy.profile("p3")?.config)
        assertTrue(copy.profile("p3")!!.dnd, "a copy takes Do Not Disturb along")
        assertEquals("Renamed", copy.withProfileRenamed("p3", " Renamed ").profile("p3")?.name)
    }

    @Test
    fun `a group made under one profile exists in all, on the default for the others`() {
        val evening = base.withProfileAdded("p2", "Evening", null, false).withActiveProfile("p2")
            .withCategoryAdded("new", "New").withCategoryMinutes("new", 120)

        val back = evening.withActiveProfile(DEFAULT_PROFILE_ID)

        assertEquals(listOf("g", "h", "new"), back.rules.categories.map { it.id })
        assertNull(back.rules.categories.single { it.id == "new" }.minutes)
        assertEquals(120, back.viewOf("p2").rules.categories.single { it.id == "new" }.minutes)
    }

    @Test
    fun `switching to the active or an unknown profile changes nothing`() {
        val two = base.withProfileAdded("p2", "Evening", null, false)
        assertEquals(two, two.withActiveProfile(DEFAULT_PROFILE_ID))
        assertEquals(two, two.withActiveProfile("nope"))
    }

    @Test
    fun `a one-time profile is deleted when another takes over, with its schedule entries`() {
        val once = base.withProfileAdded("once", "Tonight", copyOf = DEFAULT_PROFILE_ID, oneTime = true)
            .withScheduleEntry(ScheduleEntry("e", "once", setOf(1), 0, 60))
            .withActiveProfile("once")
        assertTrue(once.activeProfile.oneTime)

        val after = once.withActiveProfile(DEFAULT_PROFILE_ID)

        assertEquals(listOf(DEFAULT_PROFILE_ID), after.profileList.map { it.id })
        assertTrue(after.profiles.schedule.isEmpty())
        assertEquals(base.liveConfig, after.liveConfig)
    }

    @Test
    fun `a one-time profile is also deleted when the schedule switches away from it`() {
        val once = base.withProfileAdded("once", "Tonight", copyOf = null, oneTime = true)
            .withScheduleEntry(ScheduleEntry("e", DEFAULT_PROFILE_ID, setOf(1), 9 * 60, 17 * 60))
            .withActiveProfile("once")

        // 2024-01-08 is a Monday.
        val after = once.reconciled(at("2024-01-08T09:00:00"), utc)

        assertEquals(listOf(DEFAULT_PROFILE_ID), after.profileList.map { it.id })
        assertTrue(after.focusEnabled)
        assertEquals(base.liveConfig, after.liveConfig)
    }

    @Test
    fun `a deleted group's throttle is forgotten in every profile, so a group that comes back starts on the default`() {
        val night = base.withProfileAdded("night", "Night", null, false)
            .withProfileEdited("night") { it.withCategoryMinutes("g", MUTED) }

        val recreated = night.withoutCategory("g").withCategoryAdded("g", "Group again")

        assertEquals(ProfileConfig(), recreated.profile("night")?.config)
        assertEquals(60, recreated.viewOf("night").throttleMinutes(alpha, null))
    }

    @Test
    fun `deleting a profile drops its schedule, hands over if it was active, and never deletes the last`() {
        val two = base.withProfileAdded("p2", "Evening", null, false)
            .withScheduleEntry(ScheduleEntry("e1", "p2", setOf(1), 0, 60))
            .withScheduleEntry(ScheduleEntry("e2", DEFAULT_PROFILE_ID, setOf(1), 60, 120))

        val withoutSecond = two.withoutProfile("p2")
        assertEquals(listOf(DEFAULT_PROFILE_ID), withoutSecond.profileList.map { it.id })
        assertEquals(listOf("e2"), withoutSecond.profiles.schedule.map { it.id })

        val withoutActive = two.withoutProfile(DEFAULT_PROFILE_ID)
        assertEquals("p2", withoutActive.activeProfile.id)
        assertEquals(listOf("p2"), withoutActive.profileList.map { it.id })
        assertEquals(60, withoutActive.throttleMinutes(alpha, null), "the survivor's (empty) configuration is in force")
        assertEquals(listOf("e1"), withoutActive.profiles.schedule.map { it.id })

        assertEquals(base, base.withoutProfile(DEFAULT_PROFILE_ID))
        assertEquals(two, two.withoutProfile("nope"))
    }

    @Test
    fun `editing a profile that is not active leaves the live rules alone, except for what is shared`() {
        val two = base.withProfileAdded("p2", "Evening", null, false)

        val edited = two.withProfileEdited("p2") { it.withCategoryMinutes("g", INSTANT).withChatRule(ChatRule(beta, "c2", "Two", MUTED)).copy(slotMinutes = 30) }
        assertEquals(base.liveConfig, edited.liveConfig)
        assertEquals(ProfileConfig(30, mapOf("g" to INSTANT), listOf(ChatRule(beta, "c2", "Two", MUTED))), edited.profile("p2")?.config)
        assertEquals(INSTANT, edited.viewOf("p2").throttleMinutes(alpha, null))

        val shared = two.withProfileEdited("p2") { it.withoutHeldApp(beta).withCategoryAdded("new", "New") }
        assertEquals(setOf(alpha), shared.holdPackages, "the hold list is everyone's")
        assertEquals(listOf("g", "h", "new"), shared.rules.categories.map { it.id })
        assertEquals(15, shared.throttleMinutes(alpha, null), "the active profile's throttles are untouched")

        val live = two.withProfileEdited(DEFAULT_PROFILE_ID) { it.withCategoryMinutes("g", 30) }
        assertEquals(30, live.throttleMinutes(alpha, null), "the active profile is edited in place")
        assertEquals(two, two.withProfileEdited("nope") { it.copy(slotMinutes = 5) })
    }

    // ---- schedule ----

    // 2024-01-08 is a Monday.
    private val work = ScheduleEntry("work", "p2", setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60)
    private val night = ScheduleEntry("night", DEFAULT_PROFILE_ID, setOf(1, 2, 3, 4, 5, 6, 7), 22 * 60, 7 * 60)
    private val scheduled = base.withProfileAdded("p2", "Work", null, false).withScheduleEntry(work).withScheduleEntry(night)

    @Test
    fun `the entry in force follows weekday and time, half-open`() {
        assertEquals(work, scheduled.scheduledEntry(at("2024-01-08T09:00:00"), utc))
        assertEquals(work, scheduled.scheduledEntry(at("2024-01-08T16:59:59"), utc))
        assertNull(scheduled.scheduledEntry(at("2024-01-08T17:00:00"), utc), "over at its end")
        assertNull(scheduled.scheduledEntry(at("2024-01-08T08:59:59"), utc))
        assertNull(scheduled.withoutScheduleEntry("night").scheduledEntry(at("2024-01-13T12:00:00"), utc), "Saturday is not a work day")
    }

    @Test
    fun `an entry past midnight belongs to the day it starts on`() {
        val fridayOnly = scheduled.withScheduleEntry(night.copy(days = setOf(5)))

        assertEquals("night", fridayOnly.scheduledEntry(at("2024-01-12T23:30:00"), utc)?.id)
        assertEquals("night", fridayOnly.scheduledEntry(at("2024-01-13T06:59:00"), utc)?.id, "Saturday morning still belongs to Friday night")
        assertNull(fridayOnly.scheduledEntry(at("2024-01-13T07:00:00"), utc))
        assertNull(fridayOnly.scheduledEntry(at("2024-01-13T23:30:00"), utc), "Saturday night was not asked for")
    }

    @Test
    fun `where entries overlap the one that started last wins, and dead entries are ignored`() {
        val lunch = ScheduleEntry("lunch", DEFAULT_PROFILE_ID, setOf(1), 12 * 60, 13 * 60)
        val s = scheduled.withScheduleEntry(lunch)

        assertEquals("lunch", s.scheduledEntry(at("2024-01-08T12:30:00"), utc)?.id)
        assertEquals("work", s.scheduledEntry(at("2024-01-08T13:00:00"), utc)?.id, "and the longer one resumes")

        val dead = base.withScheduleEntry(work).withScheduleEntry(night.copy(days = emptySet()))
        assertFalse(dead.hasSchedule, "an entry for a profile that is gone, or for no day, is no schedule")
        assertNull(dead.nextScheduleChange(at("2024-01-08T10:00:00"), utc))
    }

    @Test
    fun `the next change is the nearest start or end, or the end of a manual choice`() {
        assertEquals(at("2024-01-08T09:00:00"), scheduled.nextScheduleChange(at("2024-01-08T07:30:00"), utc))
        assertEquals(at("2024-01-08T17:00:00"), scheduled.nextScheduleChange(at("2024-01-08T09:00:00"), utc))
        assertEquals(at("2024-01-08T07:00:00"), scheduled.nextScheduleChange(at("2024-01-08T01:00:00"), utc), "the end of last night's entry")
        assertEquals(at("2024-01-15T09:00:00"), scheduled.withoutScheduleEntry("night").nextScheduleChange(at("2024-01-12T17:00:00"), utc), "over the weekend")

        val manual = scheduled.withManualHold(at("2024-01-08T10:00:00"), utc)
        assertEquals(at("2024-01-09T00:00:00"), manual.profiles.manualUntil)
        assertEquals(at("2024-01-09T00:00:00"), manual.nextScheduleChange(at("2024-01-08T10:00:00"), utc))
        assertEquals(base, base.withManualHold(at("2024-01-08T10:00:00"), utc), "nothing to hold off without a schedule")
    }

    @Test
    fun `the schedule turns Focus on with its profile inside an entry and off outside`() {
        val inside = scheduled.reconciled(at("2024-01-08T10:00:00"), utc)
        assertTrue(inside.focusEnabled)
        assertEquals("p2", inside.activeProfile.id)

        val after = inside.reconciled(at("2024-01-08T18:00:00"), utc)
        assertFalse(after.focusEnabled)
        assertEquals("p2", after.activeProfile.id, "the profile stays selected")

        val night = after.reconciled(at("2024-01-08T22:00:00"), utc)
        assertTrue(night.focusEnabled)
        assertEquals(DEFAULT_PROFILE_ID, night.activeProfile.id)
        assertEquals(base.liveConfig, night.liveConfig)

        assertEquals(inside, inside.reconciled(at("2024-01-08T11:00:00"), utc), "nothing to do is no change at all")
        val manualOn = base.copy(focusEnabled = true)
        assertEquals(manualOn, manualOn.reconciled(at("2024-01-08T03:00:00"), utc), "no schedule: the switch is the owner's")
    }

    @Test
    fun `a manual choice holds the schedule off until midnight, then it is forgotten`() {
        val off = scheduled.copy(focusEnabled = false).withManualHold(at("2024-01-08T10:00:00"), utc)

        assertEquals(off, off.reconciled(at("2024-01-08T12:00:00"), utc), "still off inside the work entry")
        assertTrue(off.manualHold(at("2024-01-08T23:59:59")))

        val next = off.reconciled(at("2024-01-09T00:00:00"), utc)
        assertNull(next.profiles.manualUntil)
        assertTrue(next.focusEnabled, "Monday night's entry is in force at midnight")
        assertEquals(DEFAULT_PROFILE_ID, next.activeProfile.id)
    }

    @Test
    fun `a clock change that swallows an entry does not break the schedule`() {
        val sofia = ZoneId.of("Europe/Sofia")
        // 2024-03-31: 03:00 jumps to 04:00. An entry 03:00–04:00 has no minute to run in that day.
        val gap = base.withScheduleEntry(ScheduleEntry("gap", DEFAULT_PROFILE_ID, setOf(7), 3 * 60, 4 * 60))

        assertNull(gap.scheduledEntry(at("2024-03-31T04:30:00", sofia), sofia))
        assertEquals(at("2024-04-07T03:00:00", sofia), gap.nextScheduleChange(at("2024-03-31T04:30:00", sofia), sofia))
        assertEquals("gap", gap.scheduledEntry(at("2024-04-07T03:30:00", sofia), sofia)?.id)
    }
}
