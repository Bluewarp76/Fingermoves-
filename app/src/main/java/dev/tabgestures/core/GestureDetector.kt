package dev.tabgestures.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

enum class GestureType { SWIPE, PINCH_IN, PINCH_OUT, TAP }

/** A recognised gesture. [dx]/[dy] are the centroid movement in natural-orientation pixels. */
data class Gesture(val fingers: Int, val type: GestureType, val dx: Float, val dy: Float)

/**
 * Recognises multi-finger swipes, pinches and taps from multi-touch frames.
 *
 * A gesture spans from the first finger down to the last finger up. Movement is measured
 * between the first and last frames in which the maximum number of fingers was on screen,
 * so fingers landing or lifting a few milliseconds apart don't skew the result.
 */
class GestureDetector(private val listener: (Gesture) -> Unit) {

    var naturalWidth = 1f
    var naturalHeight = 1f

    /** Minimum centroid travel for a swipe, as a fraction of the screen's short side. */
    var swipeFraction = 0.10f

    /** Finger spread must shrink below this ratio for a pinch-in... */
    var pinchInRatio = 0.65f

    /** ...or grow above this ratio for a pinch-out. */
    var pinchOutRatio = 1.5f

    var minFingers = 3

    private class Frame(val ids: IntArray, val xs: FloatArray, val ys: FloatArray)

    private var active = false
    private var firstDownTime = 0L
    private var maxCount = 0
    private var maxReachedTime = 0L
    private var startFrame: Frame? = null
    private var lastFrame: Frame? = null

    fun reset() {
        active = false
        maxCount = 0
        startFrame = null
        lastFrame = null
    }

    /** Feed one frame of active touches. [count] entries of [ids], [xs], [ys] are valid. */
    fun onFrame(time: Long, ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int) {
        if (count == 0) {
            if (active) finish(time)
            reset()
            return
        }
        if (!active) {
            active = true
            firstDownTime = time
        }
        if (count > maxCount) {
            maxCount = count
            maxReachedTime = time
            startFrame = snapshot(ids, xs, ys, count)
        }
        if (count == maxCount) {
            lastFrame = snapshot(ids, xs, ys, count)
        }
    }

    private fun snapshot(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int) =
        Frame(ids.copyOf(count), xs.copyOf(count), ys.copyOf(count))

    private fun finish(endTime: Long) {
        if (maxCount < minFingers) return
        // Fingers that land one by one are typing or a grip, not a gesture.
        if (maxReachedTime - firstDownTime > LANDING_WINDOW_MS) return
        val start = startFrame ?: return
        val end = lastFrame ?: return

        var n = 0
        var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f
        val pairs = ArrayList<Int>(start.ids.size * 2)
        for (i in start.ids.indices) {
            val j = end.ids.indexOf(start.ids[i])
            if (j < 0) continue
            pairs += i; pairs += j
            sx += start.xs[i]; sy += start.ys[i]
            ex += end.xs[j]; ey += end.ys[j]
            n++
        }
        if (n < minFingers) return
        sx /= n; sy /= n; ex /= n; ey /= n

        var startSpread = 0f
        var endSpread = 0f
        for (k in 0 until n) {
            val i = pairs[2 * k]
            val j = pairs[2 * k + 1]
            startSpread += dist(start.xs[i] - sx, start.ys[i] - sy)
            endSpread += dist(end.xs[j] - ex, end.ys[j] - ey)
        }
        startSpread /= n
        endSpread /= n

        val shortSide = min(naturalWidth, naturalHeight)
        val dx = ex - sx
        val dy = ey - sy
        val travel = hypot(dx, dy)
        val ratio = if (startSpread > 1f) endSpread / startSpread else 1f
        val spreadChange = abs(endSpread - startSpread)
        val pinchMoved = spreadChange > PINCH_MIN_CHANGE * shortSide

        val type = when {
            ratio <= pinchInRatio && pinchMoved -> GestureType.PINCH_IN
            ratio >= pinchOutRatio && pinchMoved -> GestureType.PINCH_OUT
            travel >= swipeFraction * shortSide -> GestureType.SWIPE
            travel < TAP_SLOP * shortSide && spreadChange < TAP_SLOP * shortSide &&
                endTime - firstDownTime <= TAP_MAX_MS -> GestureType.TAP
            else -> return
        }
        listener(Gesture(min(maxCount, 5), type, dx, dy))
    }

    private fun dist(x: Float, y: Float) = sqrt(x * x + y * y)

    companion object {
        const val LANDING_WINDOW_MS = 400L
        const val TAP_MAX_MS = 350L
        const val TAP_SLOP = 0.025f
        const val PINCH_MIN_CHANGE = 0.04f
    }
}
