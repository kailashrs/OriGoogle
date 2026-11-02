package io.github.kailashrs.origoogle.core

import android.content.Context
import android.provider.Settings

/**
 * Per-corner "Google assistant" choice offered next to vivo's own options in the Interaction Cube
 * (com.vivo.upslide). The stock settings are never given a value vivo does not know: choosing the
 * assistant stores "none" there, so every other reader (Settings, SmartMultiWindow, ...) just sees
 * no action, and flags the choice in a key of its own. The flag only counts while the stock setting
 * still says none, so picking anything else elsewhere supersedes it.
 */
object CornerAssistant {
    enum class Corner(val flag: String) {
        LEFT("origoogle_corner_assistant_left"),
        RIGHT("origoogle_corner_assistant_right"),
    }

    const val LEFT_SETTING = "navigation_gesture_left_bottom_corner_side"
    const val LEFT_NONE = "side_none"
    const val RIGHT_SETTING = "vivo_multi_task_slide_enter_gesture"
    const val RIGHT_DEFAULT = 1

    fun chosen(context: Context, corner: Corner): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.Secure.getInt(resolver, corner.flag, 0) == 1 && when (corner) {
            Corner.LEFT -> Settings.Secure.getString(resolver, LEFT_SETTING) == LEFT_NONE
            Corner.RIGHT -> Settings.Secure.getInt(resolver, RIGHT_SETTING, RIGHT_DEFAULT) == 0
        }
    }.getOrDefault(false)

    fun choose(context: Context, corner: Corner, value: Boolean) {
        runCatching { Settings.Secure.putInt(context.contentResolver, corner.flag, if (value) 1 else 0) }
            .onFailure { Hooks.error("corner assistant: could not store ${corner.flag}", it) }
    }
}
