package dev.tabgestures

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import dev.tabgestures.core.GestureType
import dev.tabgestures.shell.GestureUserService
import dev.tabgestures.shell.Protocol
import rikka.shizuku.Shizuku
import kotlin.math.abs

/**
 * Foreground service that keeps the Shizuku shell process alive, receives recognised gestures
 * from it and performs the mapped actions.
 */
class GestureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var remote: IBinder? = null
    private var bound = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, GestureUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("gestures")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            remote = binder
            AppState.update(connection = "Connected to Shizuku")
            val (w, h) = naturalSize()
            call(Protocol.START) {
                it.writeStrongBinder(listener)
                it.writeInt(w)
                it.writeInt(h)
            }
            pushParams()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            bound = false
            AppState.update(connection = "Shell service disconnected – reconnecting…")
            main.postDelayed({ bindShell() }, 2000)
        }
    }

    /** Receives callbacks from the shell process. */
    private val listener = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                Protocol.CB_GESTURE -> {
                    val fingers = data.readInt()
                    val type = GestureType.entries[data.readInt()]
                    val dx = data.readFloat()
                    val dy = data.readFloat()
                    main.post { onGesture(fingers, type, dx, dy) }
                }
                Protocol.CB_STATUS -> AppState.update(touchscreen = data.readString() ?: "")
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { bindShell() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        bound = false
        AppState.update(connection = "Shizuku stopped – start it again in the Shizuku app")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { bindShell() } }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> pushParams() }

    override fun onCreate() {
        super.onCreate()
        prefs = Config.prefs(this)
        startForeground(
            NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        AppState.update(serviceRunning = true, connection = "Waiting for Shizuku…")
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean(Config.KEY_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (bound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
        }
        remote = null
        AppState.update(serviceRunning = false, connection = "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindShell() {
        if (bound || !Shizuku.pingBinder()) return
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            AppState.update(connection = "Shizuku is too old – please update it")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            AppState.update(connection = "Shizuku permission needed – open the app")
            return
        }
        bound = true
        AppState.update(connection = "Starting shell service…")
        Shizuku.bindUserService(userServiceArgs, connection)
    }

    // ---- gestures -> actions ----

    private fun onGesture(fingers: Int, type: GestureType, dx: Float, dy: Float) {
        val name = when (type) {
            GestureType.SWIPE -> swipeDirection(dx, dy)
            GestureType.PINCH_IN -> GestureName.PINCH_IN
            GestureType.PINCH_OUT -> GestureName.PINCH_OUT
            GestureType.TAP -> GestureName.TAP
        }
        val action = Config.action(prefs, Config.key(fingers, name))
        AppState.update(lastGesture = "$fingers fingers · ${name.label} → ${action.label}")
        perform(action)
    }

    /** Converts movement in the touchscreen's natural orientation into on-screen direction. */
    private fun swipeDirection(rawDx: Float, rawDy: Float): GestureName {
        val turns = (display().rotation + prefs.getInt(Config.KEY_ROTATION_FIX, 0)) % 4
        val (dx, dy) = when (turns) {
            1 -> rawDy to -rawDx
            2 -> -rawDx to -rawDy
            3 -> -rawDy to rawDx
            else -> rawDx to rawDy
        }
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) GestureName.SWIPE_RIGHT else GestureName.SWIPE_LEFT
        } else {
            if (dy > 0) GestureName.SWIPE_DOWN else GestureName.SWIPE_UP
        }
    }

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.HOME -> key(KeyEvent.KEYCODE_HOME)
            Action.RECENTS -> key(KeyEvent.KEYCODE_APP_SWITCH)
            Action.BACK -> key(KeyEvent.KEYCODE_BACK)
            Action.LAST_APP -> {
                key(KeyEvent.KEYCODE_APP_SWITCH)
                main.postDelayed({ key(KeyEvent.KEYCODE_APP_SWITCH) }, 150)
            }
            // The system's own "swipe along the navigation handle" quick-switch gesture.
            Action.PREV_APP -> bottomEdgeSwipe(rightwards = true)
            Action.NEXT_APP -> bottomEdgeSwipe(rightwards = false)
            Action.NOTIFICATIONS -> command("cmd statusbar expand-notifications")
            Action.QUICK_SETTINGS -> command("cmd statusbar expand-settings")
            Action.SCREENSHOT -> key(KeyEvent.KEYCODE_SYSRQ)
            Action.LOCK -> key(KeyEvent.KEYCODE_SLEEP)
            Action.PLAY_PAUSE -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Action.ASSISTANT -> key(KeyEvent.KEYCODE_ASSIST)
        }
    }

    private fun bottomEdgeSwipe(rightwards: Boolean) {
        val size = Point().also { @Suppress("DEPRECATION") display().getRealSize(it) }
        val y = size.y - 6 * resources.displayMetrics.density
        val left = size.x * 0.3f
        val right = size.x * 0.7f
        val (x1, x2) = if (rightwards) left to right else right to left
        call(Protocol.INJECT_SWIPE, oneway = true) {
            it.writeFloat(x1); it.writeFloat(y); it.writeFloat(x2); it.writeFloat(y); it.writeInt(180)
        }
    }

    private fun key(code: Int) = call(Protocol.INJECT_KEY, oneway = true) { it.writeInt(code) }

    private fun command(cmd: String) = call(Protocol.RUN_COMMAND, oneway = true) { it.writeString(cmd) }

    private fun pushParams() {
        val (pinchIn, pinchOut) = Config.pinchRatios(prefs)
        call(Protocol.SET_PARAMS) {
            it.writeFloat(Config.swipeFraction(prefs))
            it.writeFloat(pinchIn)
            it.writeFloat(pinchOut)
        }
    }

    private inline fun call(code: Int, oneway: Boolean = false, write: (Parcel) -> Unit) {
        val r = remote ?: return
        val data = Parcel.obtain()
        val reply = if (oneway) null else Parcel.obtain()
        try {
            write(data)
            r.transact(code, data, reply, if (oneway) IBinder.FLAG_ONEWAY else 0)
        } catch (e: Exception) {
            Log.w(TAG, "call $code failed", e)
        } finally {
            data.recycle()
            reply?.recycle()
        }
    }

    // ---- display helpers ----

    private fun display(): Display =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    /** Screen size in the device's natural orientation (what raw touch coordinates use). */
    private fun naturalSize(): Pair<Int, Int> {
        val mode = display().mode
        return mode.physicalWidth to mode.physicalHeight
    }

    // ---- notification ----

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gesture service", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GestureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gesture)
            .setContentTitle("Multi-finger gestures active")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TabGestures"
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.tabgestures.STOP"

        fun start(context: Context) {
            Config.prefs(context).edit().putBoolean(Config.KEY_ENABLED, true).apply()
            context.startForegroundService(Intent(context, GestureService::class.java))
        }

        fun stop(context: Context) {
            Config.prefs(context).edit().putBoolean(Config.KEY_ENABLED, false).apply()
            context.stopService(Intent(context, GestureService::class.java))
        }
    }
}
