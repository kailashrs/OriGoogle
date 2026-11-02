package io.github.kailashrs.origoogle.core

import android.content.SharedPreferences

/** Feature switches stored in LSPosed remote preferences, readable from every scoped process. */
object Features {
    const val GROUP = "origoogle"

    const val GMS = "gms"
    const val CIRCLE_TO_SEARCH = "circle_to_search"
    const val CORNER_ASSISTANT = "corner_assistant"
    const val POWER_ASSISTANT = "power_assistant"
    const val DISCOVER_FEED = "discover_feed"
    const val QUICK_SHARE = "quick_share"

    /** Settings-screen order; the second value is the default. */
    val ALL = linkedMapOf(
        GMS to true,
        QUICK_SHARE to true,
        CIRCLE_TO_SEARCH to true,
        CORNER_ASSISTANT to true,
        POWER_ASSISTANT to true,
        DISCOVER_FEED to false,
    )

    @Volatile
    private var preferences: SharedPreferences? = null

    fun attach(value: SharedPreferences?) {
        preferences = value
    }

    fun enabled(key: String): Boolean {
        val default = ALL[key] ?: false
        return runCatching { preferences?.getBoolean(key, default) }.getOrNull() ?: default
    }
}
