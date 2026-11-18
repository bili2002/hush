package com.hush.app.focus

import com.hush.app.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusRulesTest {
    private val alpha = "com.example.alpha"
    private val beta = "com.example.beta"
    private val gamma = "com.example.gamma"

    private fun settings(
        hold: Set<String> = setOf(alpha, beta),
        categories: List<FocusCategory> = emptyList(),
        appCategory: Map<String, String> = emptyMap(),
        chatRules: List<ChatRule> = emptyList(),
    ) = Settings(slotMinutes = 60, holdPackages = hold, rules = FocusRules(categories, appCategory, chatRules))

    @Test
    fun `throttle resolves chat rule, then the held app's group, then the default`() {
        val s = settings(
            categories = listOf(FocusCategory("fast", "Fast", 15), FocusCategory("inherit", "Inherit", null), FocusCategory("off", "Off", INSTANT)),
            appCategory = mapOf(alpha to "fast", beta to "inherit", gamma to "fast"),
            chatRules = listOf(ChatRule(alpha, "slow", "Slow chat", 240), ChatRule(alpha, "vip", "VIP", INSTANT), ChatRule(gamma, "noisy", "Noisy", 120)),
        )

        assertEquals(15, s.throttleMinutes(alpha, null), "group interval")
        assertEquals(15, s.throttleMinutes(alpha, "some other chat"))
        assertEquals(240, s.throttleMinutes(alpha, "slow"), "chat rule outranks the group")
        assertEquals(INSTANT, s.throttleMinutes(alpha, "vip"), "a chat can be exempt inside a held app")
        assertEquals(60, s.throttleMinutes(beta, null), "a group without an interval follows the default")
        assertEquals(INSTANT, s.throttleMinutes(gamma, null), "not on the hold list: a group mapping alone holds nothing")
        assertEquals(120, s.throttleMinutes(gamma, "noisy"), "a chat rule holds one chat of an app that is not held")
        assertEquals(INSTANT, s.copy(rules = s.rules.copy(appCategory = mapOf(alpha to "off"))).throttleMinutes(alpha, null))
        assertEquals(60, s.copy(rules = s.rules.copy(appCategory = mapOf(alpha to "deleted-id"))).throttleMinutes(alpha, null), "dangling group id = default")
    }

    @Test
    fun `active throttles are the intervals something can be held for`() {
        assertEquals(setOf(60), settings().activeThrottles())
        assertTrue(settings().holdsAnything)

        val mixed = settings(
            categories = listOf(FocusCategory("fast", "Fast", 15), FocusCategory("off", "Off", INSTANT)),
            appCategory = mapOf(alpha to "fast", beta to "off"),
            chatRules = listOf(ChatRule(gamma, "c", "C", 240), ChatRule(alpha, "vip", "VIP", INSTANT)),
        )
        assertEquals(setOf(15, 240), mixed.activeThrottles())

        assertFalse(settings(hold = emptySet()).holdsAnything)
        assertFalse(
            settings(hold = setOf(alpha), categories = listOf(FocusCategory("off", "Off", INSTANT)), appCategory = mapOf(alpha to "off")).holdsAnything,
            "a held app in an instant group holds nothing",
        )
        assertTrue(settings(hold = emptySet(), chatRules = listOf(ChatRule(alpha, "c", "C", 30))).holdsAnything)
    }

    @Test
    fun `muted is a throttle that needs no alarm but still makes Focus do something`() {
        val muted = settings(hold = setOf(alpha), categories = listOf(FocusCategory("quiet", "Quiet", MUTED)), appCategory = mapOf(alpha to "quiet"))

        assertEquals(MUTED, muted.throttleMinutes(alpha, null))
        assertTrue(muted.activeThrottles().isEmpty(), "nothing to wake up for")
        assertTrue(muted.holdsAnything, "turning Focus on would hide something")
        assertTrue(settings(hold = emptySet(), chatRules = listOf(ChatRule(alpha, "c", "C", MUTED))).holdsAnything)
        assertEquals(60, muted.withChatRule(ChatRule(alpha, "vip", "VIP", 60)).throttleMinutes(alpha, "vip"), "one chat of a muted app can still be delivered")
    }

    @Test
    fun `with the default on Instantly only groups and chats with their own throttle are held`() {
        val s = settings(
            categories = listOf(FocusCategory("slow", "Slow", 120), FocusCategory("inherit", "Inherit", null)),
            appCategory = mapOf(alpha to "slow", beta to "inherit"),
            chatRules = listOf(ChatRule(gamma, "c", "C", MUTED)),
        ).copy(slotMinutes = INSTANT, holdPackages = setOf(alpha, beta, gamma))

        assertEquals(120, s.throttleMinutes(alpha, null))
        assertEquals(INSTANT, s.throttleMinutes(beta, null), "a group that follows the default is not held")
        assertEquals(INSTANT, s.throttleMinutes(gamma, null), "nor is a held app without a group")
        assertEquals(MUTED, s.throttleMinutes(gamma, "c"))
        assertEquals(setOf(120), s.activeThrottles())
        assertTrue(s.holdsAnything)
        assertFalse(s.copy(rules = FocusRules()).holdsAnything, "nothing but the instant default: Focus would do nothing")
    }

    @Test
    fun `a custom interval is hours and minutes within five minutes to a day`() {
        assertEquals(90, customThrottle("1", "30"))
        assertEquals(45, customThrottle("", "45"))
        assertEquals(180, customThrottle(" 3 ", ""))
        assertEquals(100, customThrottle("", "100"), "minutes alone may exceed an hour")
        assertEquals(5, customThrottle("0", "5"))
        assertEquals(24 * 60, customThrottle("24", "0"))

        assertNull(customThrottle("", ""), "nothing typed")
        assertNull(customThrottle("0", "4"), "too short")
        assertNull(customThrottle("24", "1"), "too long")
        assertNull(customThrottle("x", "10"))
        assertNull(customThrottle("1", "-5"))
        assertNull(customThrottle("99999999999", "0"), "not an Int")
        assertTrue(THROTTLE_CHOICES.all { it in THROTTLE_RANGE })
    }

    @Test
    fun `the automatic sort holds the suggested apps in their preset group and nothing else`() {
        val sms = Presets.neverAutomatic.first()
        val before = settings(
            hold = setOf(alpha),
            categories = listOf(FocusCategory(Presets.MESSAGING, "Messaging", 15)),
            appCategory = emptyMap(),
        ).copy(excludedPackages = setOf(gamma))

        val sorted = before.withAutoSorted(
            mapOf(
                beta to Presets.MESSAGING,
                "com.example.delta" to Presets.NEWS,
                alpha to Presets.SOCIAL,
                gamma to Presets.SOCIAL,
                sms to Presets.MESSAGING,
                "com.example.odd" to "no-such-preset",
            ),
        )

        assertEquals(setOf(alpha, beta, "com.example.delta"), sorted.holdPackages, "already held, excluded, SMS and unknown presets are left alone")
        assertEquals(mapOf(beta to Presets.MESSAGING, "com.example.delta" to Presets.NEWS), sorted.rules.appCategory)
        assertEquals(listOf(Presets.MESSAGING, Presets.NEWS), sorted.rules.categories.map { it.id }, "a missing preset group is created, an existing one kept")
        assertEquals(15, sorted.throttleMinutes(beta, null), "the group keeps the interval the owner gave it")
        assertEquals(before, before.withAutoSorted(mapOf(alpha to Presets.SOCIAL)), "nothing to add changes nothing")
    }

    @Test
    fun `group edits never un-hold an app`() {
        val s = settings().withCategoryAdded("g", "  Group  ").withAppInCategory(alpha, "g").withCategoryMinutes("g", 30)
        assertEquals(listOf(FocusCategory("g", "Group", 30)), s.rules.categories)
        assertEquals(30, s.throttleMinutes(alpha, null))

        assertEquals("Renamed", s.withCategoryRenamed("g", "Renamed").rules.categories.single().name)
        assertEquals(60, s.withCategoryMinutes("g", null).throttleMinutes(alpha, null))

        val deleted = s.withoutCategory("g")
        assertTrue(deleted.rules.categories.isEmpty() && deleted.rules.appCategory.isEmpty())
        assertEquals(setOf(alpha, beta), deleted.holdPackages)
        assertEquals(60, deleted.throttleMinutes(alpha, null), "back on the default interval")

        assertEquals(60, s.withAppInCategory(alpha, null).throttleMinutes(alpha, null))
    }

    @Test
    fun `holding and un-holding an app keeps the group mapping in step`() {
        val s = settings(hold = emptySet(), categories = listOf(FocusCategory("g", "Group", 15)))
        val held = s.withHeldApp(alpha, "g").withHeldApp(beta, null)
        assertEquals(setOf(alpha, beta), held.holdPackages)
        assertEquals(mapOf(alpha to "g"), held.rules.appCategory)

        val gone = held.withoutHeldApp(alpha)
        assertEquals(setOf(beta), gone.holdPackages)
        assertTrue(gone.rules.appCategory.isEmpty())
    }

    @Test
    fun `a chat rule is replaced per package and chat, and removed by the same pair`() {
        val s = settings()
            .withChatRule(ChatRule(alpha, "c1", "Old name", 30))
            .withChatRule(ChatRule(beta, "c1", "Other app", 30))
            .withChatRule(ChatRule(alpha, "c1", "New name", 120))
        assertEquals(listOf(ChatRule(beta, "c1", "Other app", 30), ChatRule(alpha, "c1", "New name", 120)), s.rules.chatRules)
        assertEquals(listOf(ChatRule(beta, "c1", "Other app", 30)), s.withoutChatRule(alpha, "c1").rules.chatRules)
    }

    @Test
    fun `suggestion prefers the known-package table, then the OS category`() {
        assertEquals(Presets.MESSAGING, Presets.suggest("com.whatsapp", osCategory = 4), "the OS calls messengers social; the table knows better")
        assertEquals(Presets.SOCIAL, Presets.suggest("com.example.unknown", osCategory = 4))
        assertEquals(Presets.NEWS, Presets.suggest("com.example.unknown", osCategory = 5))
        assertEquals(Presets.WORK, Presets.suggest("com.example.unknown", osCategory = 7))
        assertEquals(Presets.MEDIA, Presets.suggest("com.example.unknown", osCategory = 0), "games")
        assertNull(Presets.suggest("com.example.unknown", osCategory = 6), "maps")
        assertNull(Presets.suggest("com.example.unknown", osCategory = 7, automatic = true), "productivity is only a hint for an app the owner picked")
        assertEquals(Presets.WORK, Presets.suggest("com.Slack", osCategory = 7, automatic = true), "the table still places the known ones")
        assertNull(Presets.suggest("com.example.unknown", osCategory = null))

        val noNews = Settings(rules = FocusRules(categories = Presets.categories.filterNot { it.id == Presets.NEWS }))
        assertNull(noNews.suggestedCategory("com.example.unknown", 5), "a deleted preset is not suggested")
        assertEquals(Presets.SOCIAL, noNews.suggestedCategory("com.example.unknown", 4))
    }

    @Test
    fun `seeding creates the presets once, sorts held apps and changes no interval`() {
        val before = settings(
            hold = setOf("com.whatsapp", alpha, beta),
            categories = listOf(FocusCategory("mine", "Mine", 30)),
            appCategory = mapOf(beta to "mine"),
        )
        val os = mapOf(alpha to 5, beta to 4)

        val seeded = before.seededWithPresets(os::get)

        assertTrue(seeded.rules.seeded)
        assertEquals(listOf("mine") + Presets.categories.map { it.id }, seeded.rules.categories.map { it.id })
        assertEquals(mapOf(beta to "mine", "com.whatsapp" to Presets.MESSAGING, alpha to Presets.NEWS), seeded.rules.appCategory, "an existing mapping is kept")
        before.holdPackages.forEach { pkg -> assertEquals(before.throttleMinutes(pkg, null), seeded.throttleMinutes(pkg, null), pkg) }

        val emptied = seeded.copy(rules = seeded.rules.copy(categories = emptyList(), appCategory = emptyMap()))
        assertEquals(emptied, emptied.seededWithPresets(os::get), "deleted presets do not come back")
    }

    @Test
    fun `category groups list every group in order and the uncategorised apps last`() {
        val s = settings(
            hold = setOf(alpha, beta, gamma),
            categories = listOf(FocusCategory("fast", "Fast", 15), FocusCategory("empty", "Empty", null)),
            appCategory = mapOf(gamma to "fast", alpha to "fast"),
        )
        val label: (String) -> String = { it.substringAfterLast('.') }

        assertEquals(
            listOf(
                CategoryGroup("fast", "Fast", 15, 15, listOf(HeldApp(alpha, "alpha"), HeldApp(gamma, "gamma"))),
                CategoryGroup("empty", "Empty", null, 60, emptyList()),
                CategoryGroup(null, null, null, 60, listOf(HeldApp(beta, "beta"))),
            ),
            s.categoryGroups(label),
        )
        assertEquals(2, s.copy(holdPackages = setOf(alpha, gamma)).categoryGroups(label).size, "no uncategorised apps, no extra group")
    }
}
