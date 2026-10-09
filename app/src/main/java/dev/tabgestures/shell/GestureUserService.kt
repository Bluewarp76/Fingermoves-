package dev.tabgestures.shell

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import dev.tabgestures.core.GestureDetector
import dev.tabgestures.core.GetEventInfo
import dev.tabgestures.core.MultiTouchParser
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Runs inside a Shizuku-spawned process with shell (ADB) privileges. It reads raw touches from
 * the touchscreen device, recognises gestures, reports them to the app, and injects input events
 * on the app's behalf.
 */
class GestureUserService() : Binder() {

    @Suppress("unused") // Shizuku uses this constructor when it exists.
    constructor(context: Context) : this()

    @Volatile private var listener: IBinder? = null
    @Volatile private var readerStarted = false

    private val detector = GestureDetector { g ->
        send(Protocol.CB_GESTURE) {
            it.writeInt(g.fingers)
            it.writeInt(g.type.ordinal)
            it.writeFloat(g.dx)
            it.writeFloat(g.dy)
        }
    }

    private val parser = MultiTouchParser { time, ids, xs, ys, count ->
        if (listener != null) detector.onFrame(time, ids, xs, ys, count) else detector.reset()
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            Protocol.START -> {
                val l = data.readStrongBinder()
                val w = data.readInt()
                val h = data.readInt()
                start(l, w, h)
            }
            Protocol.SET_PARAMS -> synchronized(detector) {
                detector.swipeFraction = data.readFloat()
                detector.pinchInRatio = data.readFloat()
                detector.pinchOutRatio = data.readFloat()
            }
            Protocol.INJECT_KEY -> injectKey(data.readInt())
            Protocol.INJECT_SWIPE -> injectSwipe(
                data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat(), data.readInt(),
            )
            Protocol.RUN_COMMAND -> runCommand(data.readString() ?: "")
            Protocol.STOP -> listener = null
            Protocol.DESTROY -> {
                listener = null
                Process.killProcess(Process.myPid())
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    private fun start(l: IBinder, naturalW: Int, naturalH: Int) {
        listener = l
        try {
            l.linkToDeath({ if (listener === l) listener = null }, 0)
        } catch (_: Exception) {
            listener = null
            return
        }
        synchronized(detector) {
            detector.naturalWidth = naturalW.toFloat()
            detector.naturalHeight = naturalH.toFloat()
        }
        if (readerStarted) return
        readerStarted = true
        thread(name = "touch-reader", isDaemon = true) { readLoop(naturalW, naturalH) }
    }

    private fun readLoop(naturalW: Int, naturalH: Int) {
        val device = try {
            GetEventInfo.findTouchscreen(exec("getevent -lp"))
        } catch (e: Exception) {
            null
        }
        if (device == null) {
            status("Couldn't find the touchscreen (getevent -lp gave no multi-touch device)")
            readerStarted = false
            return
        }
        parser.minX = device.minX
        parser.minY = device.minY
        parser.scaleX = naturalW.toFloat() / (device.maxX - device.minX + 1)
        parser.scaleY = naturalH.toFloat() / (device.maxY - device.minY + 1)
        status("Reading ${device.name} (${device.path}, ${device.maxX}×${device.maxY})")

        // struct input_event: timeval (2 longs), u16 type, u16 code, s32 value
        val longSize = if (Process.is64Bit()) 8 else 4
        val eventSize = longSize * 2 + 8
        val buf = ByteArray(eventSize * 64)
        try {
            FileInputStream(device.path).use { input ->
                while (true) {
                    var len = input.read(buf)
                    if (len < 0) break
                    // Reads from evdev always return whole events, but be defensive.
                    len -= len % eventSize
                    val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
                    val now = SystemClock.uptimeMillis()
                    synchronized(detector) {
                        var off = 0
                        while (off < len) {
                            val p = off + longSize * 2
                            val type = bb.getShort(p).toInt() and 0xffff
                            val code = bb.getShort(p + 2).toInt() and 0xffff
                            val value = bb.getInt(p + 4)
                            parser.onEvent(now, type, code, value)
                            off += eventSize
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "touch reader failed", e)
            status("Touch reader stopped: ${e.message}")
        }
        readerStarted = false
    }

    // ---- injection ----

    private val injectMethod: ((InputEvent) -> Boolean)? by lazy {
        for (clsName in listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")) {
            try {
                val cls = Class.forName(clsName)
                val instance = cls.getMethod("getInstance").invoke(null)
                val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                return@lazy { e: InputEvent -> m.invoke(instance, e, 0) as Boolean }
            } catch (_: Throwable) {
            }
        }
        null
    }

    private fun inject(e: InputEvent): Boolean = try {
        injectMethod?.invoke(e) ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "inject failed", t)
        false
    }

    private fun injectKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        fun key(action: Int) = KeyEvent(
            now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
        )
        if (!(inject(key(KeyEvent.ACTION_DOWN)) && inject(key(KeyEvent.ACTION_UP)))) {
            runCommand("input keyevent $keyCode")
        }
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        val down = SystemClock.uptimeMillis()
        fun motion(action: Int, x: Float, y: Float) =
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
        fun send(ev: MotionEvent): Boolean = inject(ev).also { ev.recycle() }

        if (!send(motion(MotionEvent.ACTION_DOWN, x1, y1))) {
            runCommand("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
            return
        }
        val steps = max(durationMs / 10, 5)
        for (i in 1..steps) {
            SystemClock.sleep((durationMs / steps).toLong())
            val t = i.toFloat() / steps
            send(motion(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
        }
        send(motion(MotionEvent.ACTION_UP, x2, y2))
    }

    private fun runCommand(cmd: String) {
        try {
            exec(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "command failed: $cmd", e)
        }
    }

    private fun exec(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    // ---- callbacks ----

    private fun status(msg: String) = send(Protocol.CB_STATUS) { it.writeString(msg) }

    private inline fun send(code: Int, write: (Parcel) -> Unit) {
        val l = listener ?: return
        val data = Parcel.obtain()
        try {
            write(data)
            l.transact(code, data, null, FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed", e)
        } finally {
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "TabGesturesShell"
    }
}
