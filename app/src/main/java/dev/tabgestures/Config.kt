package dev.tabgestures

import android.content.Context
import android.content.SharedPreferences

enum class Action(val label: String) {
    NONE("Nothing"),
    HOME("Home"),
    RECENTS("Recent apps"),
    BACK("Back"),
    PREV_APP("Previous app"),
    NEXT_APP("Next app"),
    LAST_APP("Toggle last app"),
    NOTIFICATIONS("Notifications"),
    QUICK_SETTINGS("Quick settings"),
    SCREENSHOT("Screenshot"),
    LOCK("Lock screen"),
    PLAY_PAUSE("Play / pause"),
    ASSISTANT("Assistant"),
}

enum class GestureName(val id: String, val label: String) {
    SWIPE_UP("swipe_up", "Swipe up"),
    SWIPE_DOWN("swipe_down", "Swipe down"),
    SWIPE_LEFT("swipe_left", "Swipe left"),
    SWIPE_RIGHT("swipe_right", "Swipe right"),
    PINCH_IN("pinch_in", "Pinch in"),
    PINCH_OUT("pinch_out", "Pinch out"),
    TAP("tap", "Tap"),
}

object Config {
    const val PREFS = "config"
    const val KEY_ENABLED = "enabled"
    const val KEY_SWIPE = "swipe_percent"        // 5..30, % of short screen side
    const val KEY_PINCH = "pinch_percent"        // 15..60, % spread change for a pinch
    const val KEY_ROTATION_FIX = "rotation_fix"  // 0..3 quarter turns

    val FINGERS = 3..5

    private val DEFAULTS = mapOf(
        "4_pinch_in" to Action.HOME,
        "5_pinch_in" to Action.HOME,
        "4_swipe_up" to Action.RECENTS,
        "5_swipe_up" to Action.RECENTS,
        "4_swipe_left" to Action.NEXT_APP,
        "5_swipe_left" to Action.NEXT_APP,
        "4_swipe_right" to Action.PREV_APP,
        "5_swipe_right" to Action.PREV_APP,
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(fingers: Int, gesture: GestureName) = "${fingers}_${gesture.id}"

    fun action(prefs: SharedPreferences, key: String): Action {
        val stored = prefs.getString("map_$key", null) ?: return DEFAULTS[key] ?: Action.NONE
        return Action.entries.firstOrNull { it.name == stored } ?: Action.NONE
    }

    fun setAction(prefs: SharedPreferences, key: String, action: Action) {
        prefs.edit().putString("map_$key", action.name).apply()
    }

    fun swipeFraction(prefs: SharedPreferences) = prefs.getInt(KEY_SWIPE, 10) / 100f

    /** Pinch sensitivity p: pinch-in below (1 - p), pinch-out above 1 / (1 - p). */
    fun pinchRatios(prefs: SharedPreferences): Pair<Float, Float> {
        val p = prefs.getInt(KEY_PINCH, 35) / 100f
        return (1f - p) to (1f / (1f - p))
    }
}
