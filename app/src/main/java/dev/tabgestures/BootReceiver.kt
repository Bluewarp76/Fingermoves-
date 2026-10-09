package dev.tabgestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the service after a reboot if it was enabled. It then waits for Shizuku to be
 * started again and connects automatically.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Config.prefs(context).getBoolean(Config.KEY_ENABLED, false)) {
            GestureService.start(context)
        }
    }
}
