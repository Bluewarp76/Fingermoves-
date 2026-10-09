package dev.tabgestures.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GestureDetectorTest {

    private val results = mutableListOf<Gesture>()
    private val detector = GestureDetector { results += it }.apply {
        naturalWidth = 2960f
        naturalHeight = 1848f
    }

    /** Fingers in a row around (cx, cy). */
    private fun row(n: Int, cx: Float, cy: Float, gap: Float = 150f) =
        List(n) { i -> (cx + (i - (n - 1) / 2f) * gap) to cy }

    /** Fingers on a circle of [radius] around (cx, cy), like a hand ready to pinch. */
    private fun circle(n: Int, cx: Float, cy: Float, radius: Float) =
        List(n) { i ->
            val a = 2 * Math.PI * i / n
            (cx + radius * cos(a)).toFloat() to (cy + radius * sin(a)).toFloat()
        }

    private fun frame(t: Long, pts: List<Pair<Float, Float>>, ids: List<Int> = pts.indices.toList()) {
        detector.onFrame(
            t,
            ids.toIntArray(),
            pts.map { it.first }.toFloatArray(),
            pts.map { it.second }.toFloatArray(),
            pts.size,
        )
    }

    private fun up(t: Long) = detector.onFrame(t, IntArray(0), FloatArray(0), FloatArray(0), 0)

    /** Interpolates between two poses over [steps] frames 8ms apart, then lifts all fingers. */
    private fun move(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>, steps: Int = 20, t0: Long = 0) {
        for (s in 0..steps) {
            val k = s.toFloat() / steps
            frame(t0 + s * 8L, from.zip(to) { a, b -> (a.first + (b.first - a.first) * k) to (a.second + (b.second - a.second) * k) })
        }
        up(t0 + steps * 8L + 8)
    }

    @Test fun fourFingerSwipeLeft() {
        move(row(4, 1800f, 900f), row(4, 1200f, 920f))
        val g = results.single()
        assertEquals(4, g.fingers)
        assertEquals(GestureType.SWIPE, g.type)
        assertTrue(g.dx < -500f)
    }

    @Test fun fourFingerSwipeUp() {
        move(row(4, 1480f, 1500f), row(4, 1480f, 900f))
        val g = results.single()
        assertEquals(GestureType.SWIPE, g.type)
        assertTrue(g.dy < -500f)
    }

    @Test fun fiveFingerPinchIn() {
        move(circle(5, 1480f, 900f, 400f), circle(5, 1500f, 950f, 120f))
        val g = results.single()
        assertEquals(5, g.fingers)
        assertEquals(GestureType.PINCH_IN, g.type)
    }

    @Test fun fourFingerPinchOut() {
        move(circle(4, 1480f, 900f, 100f), circle(4, 1480f, 900f, 350f))
        assertEquals(GestureType.PINCH_OUT, results.single().type)
    }

    @Test fun threeFingerTap() {
        val p = row(3, 1000f, 800f)
        frame(0, p); frame(40, p); frame(80, p)
        up(120)
        val g = results.single()
        assertEquals(3, g.fingers)
        assertEquals(GestureType.TAP, g.type)
    }

    @Test fun twoFingersIgnored() {
        move(row(2, 1800f, 900f), row(2, 1000f, 900f))
        assertTrue(results.isEmpty())
    }

    @Test fun smallMovementIgnored() {
        move(row(4, 1800f, 900f), row(4, 1750f, 900f), steps = 60)
        assertTrue(results.isEmpty())
    }

    @Test fun fingersLandingSlowlyAreNotAGesture() {
        val start = row(4, 1800f, 900f)
        frame(0, start.take(1), listOf(0))
        frame(300, start.take(2), listOf(0, 1))
        frame(600, start, listOf(0, 1, 2, 3))
        move(start, row(4, 1000f, 900f), t0 = 610)
        assertTrue(results.isEmpty())
    }

    @Test fun staggeredLiftUsesLastFullFrame() {
        val from = row(4, 1800f, 900f)
        val to = row(4, 1100f, 900f)
        for (s in 0..20) {
            val k = s / 20f
            frame(s * 8L, from.zip(to) { a, b -> (a.first + (b.first - a.first) * k) to a.second })
        }
        // Fingers lift one at a time; the remaining ones jitter back but that mustn't count.
        frame(200, to.drop(1).map { (it.first + 300f) to it.second }, listOf(1, 2, 3))
        frame(210, to.drop(2).map { (it.first + 300f) to it.second }, listOf(2, 3))
        up(220)
        val g = results.single()
        assertEquals(4, g.fingers)
        assertEquals(GestureType.SWIPE, g.type)
        assertEquals(-700f, g.dx, 1f)
    }

    @Test fun sixFingersReportAsFive() {
        move(row(6, 1480f, 1500f, 120f), row(6, 1480f, 800f, 120f))
        assertEquals(5, results.single().fingers)
    }

    @Test fun nothingAfterReset() {
        frame(0, row(4, 1000f, 900f))
        detector.reset()
        up(10)
        assertNull(results.firstOrNull())
    }
}
