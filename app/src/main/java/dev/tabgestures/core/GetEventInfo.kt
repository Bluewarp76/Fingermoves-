package dev.tabgestures.core

/** A multi-touch screen found in `getevent -lp` output. */
data class TouchDevice(
    val path: String,
    val name: String,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
)

object GetEventInfo {
    private val axis = Regex("""(ABS_MT_POSITION_[XY])\s*:\s*value\s*-?\d+,\s*min\s*(-?\d+),\s*max\s*(-?\d+)""")
    private val name = Regex("""name:\s*"([^"]*)"""")

    /** Picks the finger touchscreen from `getevent -lp`, preferring names containing "touchscreen". */
    fun findTouchscreen(output: String): TouchDevice? {
        val candidates = output.split("add device").mapNotNull { block ->
            val path = Regex("""(/dev/input/event\d+)""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!block.contains("ABS_MT_SLOT")) return@mapNotNull null
            val devName = name.find(block)?.groupValues?.get(1) ?: ""
            if (devName.contains("pen", ignoreCase = true)) return@mapNotNull null
            var minX = 0; var maxX = -1; var minY = 0; var maxY = -1
            for (m in axis.findAll(block)) {
                val lo = m.groupValues[2].toInt()
                val hi = m.groupValues[3].toInt()
                if (m.groupValues[1].endsWith("X")) { minX = lo; maxX = hi } else { minY = lo; maxY = hi }
            }
            if (maxX <= minX || maxY <= minY) return@mapNotNull null
            TouchDevice(path, devName, minX, maxX, minY, maxY)
        }
        return candidates.firstOrNull { it.name.contains("touchscreen", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }
}
