package dev.tabgestures.core

/**
 * Decodes Linux multi-touch protocol B input events (as read from /dev/input/eventN) into
 * frames of active contacts, emitted on every SYN_REPORT. Palm contacts are dropped.
 */
class MultiTouchParser(
    private val onFrame: (time: Long, ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int) -> Unit,
) {
    /** Raw axis value -> natural-orientation pixels. */
    var scaleX = 1f
    var scaleY = 1f
    var minX = 0
    var minY = 0

    private val tracking = IntArray(MAX_SLOTS) { -1 }
    private val rawX = IntArray(MAX_SLOTS)
    private val rawY = IntArray(MAX_SLOTS)
    private val tool = IntArray(MAX_SLOTS)
    private var slot = 0
    private var dropped = false

    private val outIds = IntArray(MAX_SLOTS)
    private val outXs = FloatArray(MAX_SLOTS)
    private val outYs = FloatArray(MAX_SLOTS)

    fun onEvent(time: Long, type: Int, code: Int, value: Int) {
        when (type) {
            EV_ABS -> {
                if (code == ABS_MT_SLOT) {
                    slot = value
                    return
                }
                if (slot !in 0 until MAX_SLOTS) return
                when (code) {
                    ABS_MT_TRACKING_ID -> {
                        tracking[slot] = value
                        if (value >= 0) tool[slot] = MT_TOOL_FINGER
                    }
                    ABS_MT_POSITION_X -> rawX[slot] = value
                    ABS_MT_POSITION_Y -> rawY[slot] = value
                    ABS_MT_TOOL_TYPE -> tool[slot] = value
                }
            }
            EV_SYN -> when (code) {
                SYN_REPORT -> {
                    if (dropped) {
                        // Kernel buffer overflowed; our slot state is stale, so cancel everything.
                        dropped = false
                        tracking.fill(-1)
                    }
                    emit(time)
                }
                SYN_DROPPED -> dropped = true
            }
        }
    }

    private fun emit(time: Long) {
        var n = 0
        for (s in 0 until MAX_SLOTS) {
            if (tracking[s] < 0 || tool[s] == MT_TOOL_PALM) continue
            outIds[n] = tracking[s]
            outXs[n] = (rawX[s] - minX) * scaleX
            outYs[n] = (rawY[s] - minY) * scaleY
            n++
        }
        onFrame(time, outIds, outXs, outYs, n)
    }

    companion object {
        const val MAX_SLOTS = 20

        const val EV_SYN = 0x00
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0x00
        const val SYN_DROPPED = 0x03
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_TOOL_TYPE = 0x37
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val MT_TOOL_FINGER = 0
        const val MT_TOOL_PALM = 2
    }
}
