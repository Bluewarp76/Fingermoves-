package dev.tabgestures.core

import dev.tabgestures.core.MultiTouchParser.Companion.ABS_MT_POSITION_X
import dev.tabgestures.core.MultiTouchParser.Companion.ABS_MT_POSITION_Y
import dev.tabgestures.core.MultiTouchParser.Companion.ABS_MT_SLOT
import dev.tabgestures.core.MultiTouchParser.Companion.ABS_MT_TOOL_TYPE
import dev.tabgestures.core.MultiTouchParser.Companion.ABS_MT_TRACKING_ID
import dev.tabgestures.core.MultiTouchParser.Companion.EV_ABS
import dev.tabgestures.core.MultiTouchParser.Companion.EV_SYN
import dev.tabgestures.core.MultiTouchParser.Companion.SYN_DROPPED
import dev.tabgestures.core.MultiTouchParser.Companion.SYN_REPORT
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiTouchParserTest {

    private data class F(val ids: List<Int>, val xs: List<Float>, val ys: List<Float>)

    private val frames = mutableListOf<F>()
    private val parser = MultiTouchParser { _, ids, xs, ys, n ->
        frames += F(ids.take(n), xs.take(n), ys.take(n))
    }.apply { scaleX = 0.5f; scaleY = 0.25f }

    private fun abs(code: Int, v: Int) = parser.onEvent(0, EV_ABS, code, v)
    private fun syn() = parser.onEvent(0, EV_SYN, SYN_REPORT, 0)

    @Test fun tracksSlotsAndScales() {
        abs(ABS_MT_SLOT, 0); abs(ABS_MT_TRACKING_ID, 10); abs(ABS_MT_POSITION_X, 100); abs(ABS_MT_POSITION_Y, 400)
        abs(ABS_MT_SLOT, 1); abs(ABS_MT_TRACKING_ID, 11); abs(ABS_MT_POSITION_X, 200); abs(ABS_MT_POSITION_Y, 800)
        syn()
        assertEquals(F(listOf(10, 11), listOf(50f, 100f), listOf(100f, 200f)), frames.last())

        // Only slot 1 moves; slot 0 keeps its last position.
        abs(ABS_MT_POSITION_X, 300); syn()
        assertEquals(F(listOf(10, 11), listOf(50f, 150f), listOf(100f, 200f)), frames.last())

        abs(ABS_MT_SLOT, 0); abs(ABS_MT_TRACKING_ID, -1); syn()
        assertEquals(listOf(11), frames.last().ids)
    }

    @Test fun ignoresPalms() {
        abs(ABS_MT_SLOT, 0); abs(ABS_MT_TRACKING_ID, 1); abs(ABS_MT_TOOL_TYPE, 2)
        abs(ABS_MT_SLOT, 1); abs(ABS_MT_TRACKING_ID, 2)
        syn()
        assertEquals(listOf(2), frames.last().ids)
    }

    @Test fun droppedEventsCancelTouches() {
        abs(ABS_MT_SLOT, 0); abs(ABS_MT_TRACKING_ID, 1); syn()
        parser.onEvent(0, EV_SYN, SYN_DROPPED, 0)
        syn()
        assertEquals(0, frames.last().ids.size)
    }
}
