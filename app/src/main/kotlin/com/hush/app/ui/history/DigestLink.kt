package com.hush.app.ui.history

import android.content.Intent
import com.hush.app.ui.MainActivity

/** The digest notification's deep link: "show me the held batch delivered at [deliveredAt]". */
data class DigestLink(val deliveredAt: Long) {
    companion object {
        /**
         * Null unless [intent] carries `EXTRA_FILTER == FILTER_HELD` and a real `EXTRA_DELIVERED_AT`.
         * The activity is exported, so the time is not trusted: zero or less would select the muted
         * rows (stamped 0), which were never in a digest.
         */
        fun parse(intent: Intent?): DigestLink? {
            if (intent == null) return null
            if (intent.getStringExtra(MainActivity.EXTRA_FILTER) != MainActivity.FILTER_HELD) return null
            if (!intent.hasExtra(MainActivity.EXTRA_DELIVERED_AT)) return null
            return intent.getLongExtra(MainActivity.EXTRA_DELIVERED_AT, 0L).takeIf { it > 0L }?.let(::DigestLink)
        }
    }
}
