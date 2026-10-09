package dev.tabgestures.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GetEventInfoTest {
    private val sample = """
add device 1: /dev/input/event5
  bus:      0000
  name:     "sec_e-pen"
  events:
    ABS (0003): ABS_X                 : value 0, min 0, max 29439, fuzz 0, flat 0, resolution 0
                ABS_MT_SLOT           : value 0, min 0, max 0, fuzz 0, flat 0, resolution 0
add device 2: /dev/input/event2
  bus:      0000
  name:     "sec_touchscreen"
  events:
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 19, fuzz 0, flat 0, resolution 0
                ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 29599, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 18479, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
add device 3: /dev/input/event1
  name:     "gpio_keys"
    """.trimIndent()

    @Test fun findsTouchscreenAndSkipsPen() {
        assertEquals(
            TouchDevice("/dev/input/event2", "sec_touchscreen", 0, 29599, 0, 18479),
            GetEventInfo.findTouchscreen(sample),
        )
    }
}
