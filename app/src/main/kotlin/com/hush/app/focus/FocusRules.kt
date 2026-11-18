package com.hush.app.focus

import com.hush.app.settings.Settings
import kotlinx.serialization.Serializable

/**
 * A named group of held apps with its own digest interval. [minutes]: null = use the default
 * interval ([Settings.slotMinutes]), [INSTANT] = do not hold, [MUTED] = hide and keep out of digests,
 * otherwise the interval in minutes.
 */
@Serializable
data class FocusCategory(val id: String, val name: String, val minutes: Int? = null)

/**
 * A throttle for one chat ([chatKey] = `NotificationEvent.chatKey`) of one app. It wins over the
 * app's category and also applies to an app that is not on the hold list. [title] is only for display.
 */
@Serializable
data class ChatRule(val packageName: String, val chatKey: String, val title: String, val minutes: Int)

/** Everything "who is held for how long" beyond the hold list itself. One JSON preference. */
@Serializable
data class FocusRules(
    val categories: List<FocusCategory> = emptyList(),
    /** Package → [FocusCategory.id]. Only meaningful for packages on the hold list. */
    val appCategory: Map<String, String> = emptyMap(),
    val chatRules: List<ChatRule> = emptyList(),
    /** The preset categories were created once; deleting them all must not bring them back. */
    val seeded: Boolean = false,
)

/** Throttle value meaning "not held: show it right away". */
const val INSTANT = 0

/**
 * Throttle value meaning "hidden while Focus is on and kept out of digests". The notification is
 * still recorded and stays in History; it is delivered after all if its rule later becomes
 * something else while Focus is on. Below [INSTANT] on purpose: every "is there an interval to
 * wait for" check (`> INSTANT`) is false for it without knowing about it.
 */
const val MUTED = -1

/** The intervals offered everywhere a throttle is picked; "Custom…" takes any other within [THROTTLE_RANGE]. */
val THROTTLE_CHOICES = listOf(15, 30, 60, 120, 180, 240, 360, 480, 720)

/**
 * What a custom interval may be, in minutes. Below five minutes a digest is no different from the
 * notifications themselves (and the alarm cannot keep up in Doze); above a day the slots, which
 * restart at local midnight, stop meaning anything.
 */
val THROTTLE_RANGE = 5..24 * 60

/**
 * The interval typed into the custom dialog as [hours] and [minutes] (either may be blank), or
 * null when it is not a number or outside [THROTTLE_RANGE].
 */
fun customThrottle(hours: String, minutes: String): Int? {
    val h = hours.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
    val m = minutes.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
    if (h !in 0..24 || m !in 0..THROTTLE_RANGE.last) return null
    return (h * 60 + m).takeIf { it in THROTTLE_RANGE }
}

fun Settings.categoryOf(packageName: String): FocusCategory? =
    rules.appCategory[packageName]?.let { id -> rules.categories.firstOrNull { it.id == id } }

fun Settings.chatRule(packageName: String, chatKey: String?): ChatRule? =
    chatKey?.let { key -> rules.chatRules.firstOrNull { it.packageName == packageName && it.chatKey == key } }

/** The app-level throttle: [INSTANT] when the app is not on the hold list, else its category's interval or the default. */
fun Settings.appThrottleMinutes(packageName: String): Int =
    if (packageName !in holdPackages) INSTANT else categoryOf(packageName)?.minutes ?: slotMinutes

/**
 * How long a notification of [packageName] in chat [chatKey] is held: chat rule → app category →
 * default. [INSTANT] means it is not held at all, [MUTED] that it is hidden and in no digest. Whether Focus
 * is on is the caller's business.
 */
fun Settings.throttleMinutes(packageName: String, chatKey: String?): Int =
    chatRule(packageName, chatKey)?.minutes ?: appThrottleMinutes(packageName)

/** The distinct intervals the current rules can hold something for; what the alarm has to wake up for. */
fun Settings.activeThrottles(): Set<Int> =
    (holdPackages.map(::appThrottleMinutes) + rules.chatRules.map { it.minutes }).filter { it > INSTANT }.toSet()

/** False when turning Focus on would change nothing: no held app and no chat rule that holds or mutes. */
val Settings.holdsAnything: Boolean
    get() = holdPackages.any { appThrottleMinutes(it) != INSTANT } || rules.chatRules.any { it.minutes != INSTANT }

// ---- rule edits (pure; the UI applies them through FocusActions.updateRules) ----

fun Settings.withCategoryAdded(id: String, name: String): Settings =
    copy(rules = rules.copy(categories = rules.categories + FocusCategory(id, name.trim())))

fun Settings.withCategoryRenamed(id: String, name: String): Settings =
    copy(rules = rules.copy(categories = rules.categories.map { if (it.id == id) it.copy(name = name.trim()) else it }))

fun Settings.withCategoryMinutes(id: String, minutes: Int?): Settings =
    copy(rules = rules.copy(categories = rules.categories.map { if (it.id == id) it.copy(minutes = minutes) else it }))

/**
 * Deleting a category never un-holds its apps: they fall back to the default interval. Its
 * throttle is forgotten in every profile, so a group that later comes back under the same id (the
 * automatic sort re-creates presets) starts on the default everywhere and is not silently muted
 * by what a profile once said about the old one.
 */
fun Settings.withoutCategory(id: String): Settings = copy(
    rules = rules.copy(
        categories = rules.categories.filterNot { it.id == id },
        appCategory = rules.appCategory.filterValues { it != id },
    ),
    profiles = profiles.copy(list = profiles.list.map { it.copy(config = it.config.copy(categoryMinutes = it.config.categoryMinutes - id)) }),
)

/** Puts a held app into [categoryId], or with null back among the uncategorised ones. */
fun Settings.withAppInCategory(packageName: String, categoryId: String?): Settings = copy(
    rules = rules.copy(
        appCategory = if (categoryId == null) rules.appCategory - packageName else rules.appCategory + (packageName to categoryId),
    ),
)

fun Settings.withHeldApp(packageName: String, categoryId: String?): Settings =
    copy(holdPackages = holdPackages + packageName).let { if (categoryId == null) it else it.withAppInCategory(packageName, categoryId) }

fun Settings.withoutHeldApp(packageName: String): Settings =
    copy(holdPackages = holdPackages - packageName, rules = rules.copy(appCategory = rules.appCategory - packageName))

/** Adds or replaces the rule for the rule's (package, chat). */
fun Settings.withChatRule(rule: ChatRule): Settings = copy(
    rules = rules.copy(
        chatRules = rules.chatRules.filterNot { it.packageName == rule.packageName && it.chatKey == rule.chatKey } + rule,
    ),
)

fun Settings.withoutChatRule(packageName: String, chatKey: String): Settings =
    copy(rules = rules.copy(chatRules = rules.chatRules.filterNot { it.packageName == packageName && it.chatKey == chatKey }))

// ---- presets ----

object Presets {
    const val MESSAGING = "messaging"
    const val SOCIAL = "social"
    const val WORK = "work"
    const val NEWS = "news"
    const val SHOPPING = "shopping"
    const val MEDIA = "media"

    /** All on the default interval, so creating them changes nothing about what is held or when. */
    val categories = listOf(
        FocusCategory(MESSAGING, "Messaging"),
        FocusCategory(SOCIAL, "Social"),
        FocusCategory(WORK, "Email & work"),
        FocusCategory(NEWS, "News"),
        FocusCategory(SHOPPING, "Shopping"),
        FocusCategory(MEDIA, "Games & media"),
    )

    /**
     * Never added by the automatic sort, whatever they would be sorted into: SMS apps, because
     * login codes arrive there and an hour's delay locks the owner out. The phone's actual default
     * SMS app is excluded by the caller as well. Adding one by hand still works.
     */
    val neverAutomatic = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")

    private val known: Map<String, String> = buildMap {
        listOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.viber.voip", "com.discord", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.google.android.apps.dynamite",
        ).forEach { put(it, MESSAGING) }
        listOf(
            "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.zhiliaoapp.musically",
            "com.twitter.android", "com.reddit.frontpage", "com.snapchat.android", "com.linkedin.android",
            "com.pinterest", "org.joinmastodon.android", "com.google.android.youtube",
        ).forEach { put(it, SOCIAL) }
        listOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.Slack", "com.microsoft.teams",
        ).forEach { put(it, WORK) }
        listOf(
            "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.alibaba.aliexpresshd", "com.einnovation.temu",
            "com.zzkko", "com.vinted", "com.etsy.android",
        ).forEach { put(it, SHOPPING) }
        listOf("com.spotify.music", "com.netflix.mediaclient", "tv.twitch.android.app").forEach { put(it, MEDIA) }
    }

    // android.content.pm.ApplicationInfo.CATEGORY_* (plain ints so this stays a pure function).
    private const val OS_GAME = 0
    private const val OS_AUDIO = 1
    private const val OS_VIDEO = 2
    private const val OS_SOCIAL = 4
    private const val OS_NEWS = 5
    private const val OS_PRODUCTIVITY = 7

    /**
     * The preset a newly held app most likely belongs in, or null. A short table of well-known
     * packages first (the OS files messengers and social networks under one category), then the
     * app's own declared [osCategory].
     *
     * [automatic]: the app was not picked by the owner. "Productivity" is then no reason to hold
     * it: that category is also what sign-in approval apps, calendars and task reminders declare,
     * and none of those should be delayed without being asked.
     */
    fun suggest(packageName: String, osCategory: Int?, automatic: Boolean = false): String? = known[packageName] ?: when (osCategory) {
        OS_SOCIAL -> SOCIAL
        OS_NEWS -> NEWS
        OS_PRODUCTIVITY -> WORK.takeIf { !automatic }
        OS_GAME, OS_AUDIO, OS_VIDEO -> MEDIA
        else -> null
    }
}

/** [Presets.suggest], narrowed to a category that still exists in these settings. */
fun Settings.suggestedCategory(packageName: String, osCategory: Int?): String? =
    Presets.suggest(packageName, osCategory)?.takeIf { id -> rules.categories.any { it.id == id } }

/**
 * One-time setup of the presets: creates them and sorts the apps already on the hold list into
 * them. Intervals are untouched (every preset uses the default), so nothing changes behaviour.
 */
fun Settings.seededWithPresets(osCategory: (String) -> Int?): Settings {
    if (rules.seeded) return this
    val categories = rules.categories + Presets.categories.filterNot { p -> rules.categories.any { it.id == p.id } }
    val sorted = holdPackages.filterNot { it in rules.appCategory }
        .mapNotNull { pkg -> Presets.suggest(pkg, osCategory(pkg))?.let { pkg to it } }
    return copy(rules = rules.copy(categories = categories, appCategory = rules.appCategory + sorted, seeded = true))
}

/**
 * The automatic sort: holds every app in [suggestions] (package → preset id from [Presets.suggest], `automatic = true`)
 * that is not held yet, in its preset group. A preset group that was deleted is created again when
 * something is sorted into it. Apps already held, excluded from recording, or in
 * [Presets.neverAutomatic] are left alone.
 */
fun Settings.withAutoSorted(suggestions: Map<String, String>): Settings {
    val add = suggestions.filter { (pkg, preset) ->
        pkg !in holdPackages && pkg !in excludedPackages && pkg !in Presets.neverAutomatic && Presets.categories.any { it.id == preset }
    }
    if (add.isEmpty()) return this
    val missing = Presets.categories.filter { p -> p.id in add.values && rules.categories.none { it.id == p.id } }
    return copy(
        holdPackages = holdPackages + add.keys,
        rules = rules.copy(categories = rules.categories + missing, appCategory = rules.appCategory + add),
    )
}

// ---- read model shared by the Focus summary and the rules screen ----

data class HeldApp(val packageName: String, val label: String)

/** [id]/[name] are null for the held apps that are in no category. [effectiveMinutes] resolves "default". */
data class CategoryGroup(val id: String?, val name: String?, val minutes: Int?, val effectiveMinutes: Int, val apps: List<HeldApp>)

/** Every category in its stored order (empty ones included), then the uncategorised held apps if there are any. */
fun Settings.categoryGroups(label: (String) -> String): List<CategoryGroup> {
    val byCategory = holdPackages.groupBy { categoryOf(it)?.id }
    fun apps(id: String?) = byCategory[id].orEmpty().map { HeldApp(it, label(it)) }.sortedBy { it.label.lowercase() }
    val named = rules.categories.map { c -> CategoryGroup(c.id, c.name, c.minutes, c.minutes ?: slotMinutes, apps(c.id)) }
    val loose = apps(null)
    return if (loose.isEmpty()) named else named + CategoryGroup(null, null, null, slotMinutes, loose)
}
