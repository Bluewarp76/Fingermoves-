package dev.tabgestures

import android.os.Handler
import android.os.Looper

/** In-process status shared between [GestureService] and [MainActivity]. Main thread only. */
object AppState {
    var serviceRunning = false
        private set
    var connection = "Not started"
        private set
    var touchscreen = "–"
        private set
    var lastGesture = "–"
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun update(
        serviceRunning: Boolean = this.serviceRunning,
        connection: String = this.connection,
        touchscreen: String = this.touchscreen,
        lastGesture: String = this.lastGesture,
    ) = main.post {
        this.serviceRunning = serviceRunning
        this.connection = connection
        this.touchscreen = touchscreen
        this.lastGesture = lastGesture
        listeners.toList().forEach { it() }
    }
}
