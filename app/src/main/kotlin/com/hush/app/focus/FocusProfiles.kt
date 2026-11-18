package com.hush.app.focus

import com.hush.app.settings.Settings
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * What differs between Focus profiles: the default interval, each group's throttle and the chat
 * rules. The hold list and the groups themselves (which app is in which group) are shared by all
 * profiles, so apps are sorted once and a profile only says how each group is treated.
 */
@Serializable
data class ProfileConfig(
    val slotMinutes: Int = 60,
    /** Group id → throttle. A group that is absent follows [slotMinutes]. */
    val categoryMinutes: Map<String, Int> = emptyMap(),
    val chatRules: List<ChatRule> = emptyList(),
)

@Serializable
data class FocusProfile(
    val id: String,
    val name: String,
    /** Deleted as soon as another profile takes over. */
    val oneTime: Boolean = false,
    /** Turn the phone's Do Not Disturb on while Focus is on with this profile. */
    val dnd: Boolean = false,
    /**
     * Out of date for the active profile, whose configuration is the live settings
     * ([Settings.slotMinutes], the groups' minutes, the chat rules). Read profiles through
     * [Settings.profileList], which fills the current values in.
     */
    val config: ProfileConfig = ProfileConfig(),
)

/**
 * Focus is on with [profileId] from [start] to [end] (minutes of the day) on [days] (ISO, 1 = Monday).
 * `end <= start` runs past midnight and belongs to the day it starts on.
 */
@Serializable
data class ScheduleEntry(val id: String, val profileId: String, val days: Set<Int>, val start: Int, val end: Int)

/** Profiles, their schedule and the bookkeeping around them. One JSON preference. */
@Serializable
data class FocusProfiles(
    /** Empty until a second profile is made: the live settings alone are then the profile "Default". */
    val list: List<FocusProfile> = emptyList(),
    val activeId: String = DEFAULT_PROFILE_ID,
    val schedule: List<ScheduleEntry> = emptyList(),
    /** A manual choice (the switch, a profile pick) stands until this instant; the schedule waits. */
    val manualUntil: Long? = null,
    /** Hush turned Do Not Disturb on, so Hush is the one to turn it off. */
    val dndByHush: Boolean = false,
    /** Why Do Not Disturb could not be switched, until it works. */
    val dndError: String? = null,
)

const val DEFAULT_PROFILE_ID = "default"
private const val DEFAULT_PROFILE_NAME = "Default"

// ---- live settings <-> profile configuration ----

/** The active profile's configuration as it is right now. */
val Settings.liveConfig: ProfileConfig
    get() = ProfileConfig(
        slotMinutes = slotMinutes,
        categoryMinutes = rules.categories.mapNotNull { c -> c.minutes?.let { c.id to it } }.toMap(),
        chatRules = rules.chatRules,
    )

/** These settings with [config] in force. Groups and the hold list stay; only how they are treated changes. */
fun Settings.withLiveConfig(config: ProfileConfig): Settings = copy(
    slotMinutes = config.slotMinutes,
    rules = rules.copy(
        categories = rules.categories.map { it.copy(minutes = config.categoryMinutes[it.id]) },
        chatRules = config.chatRules,
    ),
)

/** Every profile with its current configuration. Never empty. */
val Settings.profileList: List<FocusProfile>
    get() {
        val stored = profiles.list.ifEmpty { listOf(FocusProfile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_NAME)) }
        val activeId = stored.firstOrNull { it.id == profiles.activeId }?.id ?: stored.first().id
        return stored.map { if (it.id == activeId) it.copy(config = liveConfig) else it }
    }

val Settings.activeProfile: FocusProfile
    get() = profileList.let { list -> list.firstOrNull { it.id == profiles.activeId } ?: list.first() }

fun Settings.profile(id: String): FocusProfile? = profileList.firstOrNull { it.id == id }

/** The stored form every profile edit starts from: the list written out, the active id valid. */
private fun Settings.materialized(): Settings =
    copy(profiles = profiles.copy(list = profileList, activeId = activeProfile.id))

/**
 * These settings as they would be with profile [id] active, for reading its rules with the
 * functions in `FocusRules.kt`. An unknown id reads as the active profile.
 */
fun Settings.viewOf(id: String): Settings {
    val target = profile(id) ?: return this
    return if (target.id == activeProfile.id) this else withLiveConfig(target.config)
}

// ---- profile edits (pure; applied through FocusActions) ----

/** A new profile: a copy of [copyOf] (its throttles, chat rules and Do Not Disturb), or with null an untouched one. */
fun Settings.withProfileAdded(id: String, name: String, copyOf: String?, oneTime: Boolean): Settings {
    val base = materialized()
    val source = copyOf?.let { base.profile(it) }
    val added = FocusProfile(id, name.trim(), oneTime = oneTime, dnd = source?.dnd ?: false, config = source?.config ?: ProfileConfig())
    return base.copy(profiles = base.profiles.copy(list = base.profiles.list + added))
}

private fun Settings.withProfile(id: String, change: (FocusProfile) -> FocusProfile): Settings {
    val base = materialized()
    return base.copy(profiles = base.profiles.copy(list = base.profiles.list.map { if (it.id == id) change(it) else it }))
}

fun Settings.withProfileRenamed(id: String, name: String): Settings = withProfile(id) { it.copy(name = name.trim()) }

fun Settings.withProfileDnd(id: String, dnd: Boolean): Settings = withProfile(id) { it.copy(dnd = dnd) }

/**
 * Makes [id] the active profile: the live settings take its configuration and the one that was
 * active keeps what it had. A one-time profile that is switched away from is deleted. An unknown
 * id, or the one already active, changes nothing.
 */
fun Settings.withActiveProfile(id: String): Settings {
    val base = materialized()
    val current = base.activeProfile
    val target = base.profile(id) ?: return this
    if (target.id == current.id) return this
    val kept = if (current.oneTime) base.profiles.list.filterNot { it.id == current.id } else base.profiles.list
    return base
        .copy(
            profiles = base.profiles.copy(
                list = kept,
                activeId = target.id,
                schedule = if (current.oneTime) base.profiles.schedule.filterNot { it.profileId == current.id } else base.profiles.schedule,
            ),
        )
        .withLiveConfig(target.config)
}

/**
 * Deletes profile [id] and its schedule entries. The last profile cannot be deleted; deleting the
 * active one first hands over to the first that remains.
 */
fun Settings.withoutProfile(id: String): Settings {
    val base = materialized()
    if (base.profiles.list.size <= 1 || base.profiles.list.none { it.id == id }) return this
    val switched = if (base.activeProfile.id == id) base.withActiveProfile(base.profiles.list.first { it.id != id }.id).materialized() else base
    return switched.copy(
        profiles = switched.profiles.copy(
            list = switched.profiles.list.filterNot { it.id == id },
            schedule = switched.profiles.schedule.filterNot { it.profileId == id },
        ),
    )
}

/**
 * Applies a rule edit ([edit] is one of the functions in `FocusRules.kt`) to profile [id], active
 * or not. What the edit does to the shared parts (hold list, groups) applies to everyone; what it
 * does to throttles and chat rules lands in that profile only.
 */
fun Settings.withProfileEdited(id: String, edit: (Settings) -> Settings): Settings {
    val base = materialized()
    val target = base.profile(id) ?: return this
    if (target.id == base.activeProfile.id) return edit(this)
    val mine = base.liveConfig
    val edited = edit(base.withLiveConfig(target.config))
    val theirs = edited.liveConfig
    return edited.withLiveConfig(mine).copy(
        profiles = edited.profiles.copy(list = edited.profiles.list.map { if (it.id == target.id) it.copy(config = theirs) else it }),
    )
}

// ---- schedule ----

fun Settings.withScheduleEntry(entry: ScheduleEntry): Settings = copy(
    profiles = profiles.copy(
        schedule = if (profiles.schedule.any { it.id == entry.id }) profiles.schedule.map { if (it.id == entry.id) entry else it }
        else profiles.schedule + entry,
    ),
)

fun Settings.withoutScheduleEntry(id: String): Settings =
    copy(profiles = profiles.copy(schedule = profiles.schedule.filterNot { it.id == id }))

/** The schedule entries that can take effect: they name a profile that exists and at least one day. */
private fun Settings.liveSchedule(): List<ScheduleEntry> {
    val ids = profileList.map { it.id }.toSet()
    return profiles.schedule.filter { it.profileId in ids && it.days.isNotEmpty() }
}

/** The start and end instants of [entry] on the days around [now]: yesterday's (it may run past midnight) through next week's. */
private fun occurrences(entry: ScheduleEntry, now: Long, zone: ZoneId): List<LongRange> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (-1L..7L).mapNotNull { offset ->
        val day = today.plusDays(offset)
        if (day.dayOfWeek.value !in entry.days) return@mapNotNull null
        val start = day.atStartOfDay().plusMinutes(entry.start.toLong()).atZone(zone).toInstant().toEpochMilli()
        val endDay = if (entry.end > entry.start) day else day.plusDays(1)
        val end = endDay.atStartOfDay().plusMinutes(entry.end.toLong()).atZone(zone).toInstant().toEpochMilli()
        // Half-open: at `end` the entry is over. A spring-forward gap can collapse an entry to nothing.
        if (end > start) start until end else null
    }
}

/** The entry in force at [now], or null. Where entries overlap, the one that started last wins. */
fun Settings.scheduledEntry(now: Long, zone: ZoneId): ScheduleEntry? =
    liveSchedule()
        .flatMap { entry -> occurrences(entry, now, zone).filter { now in it }.map { it.first to entry } }
        .maxByOrNull { it.first }
        ?.second

/** True while a manual choice stands and the schedule has to wait. */
fun Settings.manualHold(now: Long): Boolean = profiles.manualUntil?.let { now < it } == true

val Settings.hasSchedule: Boolean get() = liveSchedule().isNotEmpty()

/**
 * The next instant after [now] at which the schedule may change what Focus does: the end of the
 * manual hold, else the next start or end of any entry. Null without a schedule.
 */
fun Settings.nextScheduleChange(now: Long, zone: ZoneId): Long? {
    val schedule = liveSchedule()
    if (schedule.isEmpty()) return null
    profiles.manualUntil?.takeIf { it > now }?.let { return it }
    return schedule.flatMap { entry -> occurrences(entry, now, zone).flatMap { listOf(it.first, it.last + 1) } }.filter { it > now }.minOrNull()
}

/** A manual choice made at [now] stands until local midnight. Without a schedule there is nothing to hold off. */
fun Settings.withManualHold(now: Long, zone: ZoneId): Settings {
    if (!hasSchedule) return this
    val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return copy(profiles = profiles.copy(manualUntil = midnight))
}

fun Settings.withoutManualHold(): Settings = copy(profiles = profiles.copy(manualUntil = null))

/**
 * What the schedule makes of these settings at [now]. While a manual choice stands, or without a
 * schedule, nothing. Otherwise: inside an entry Focus is on with that entry's profile, outside
 * all entries Focus is off (the profile stays selected). Pure, so the gate can ask it for every
 * notification without waiting for the alarm that writes the result down.
 */
fun Settings.reconciled(now: Long, zone: ZoneId): Settings {
    if (manualHold(now)) return this
    val settled = if (profiles.manualUntil != null) withoutManualHold() else this
    if (!settled.hasSchedule) return settled
    val entry = settled.scheduledEntry(now, zone)
    return if (entry == null) settled.copy(focusEnabled = false) else settled.withActiveProfile(entry.profileId).copy(focusEnabled = true)
}
