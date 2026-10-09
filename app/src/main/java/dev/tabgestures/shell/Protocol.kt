package dev.tabgestures.shell

/** Binder transaction codes shared by the app and the Shizuku user service. */
object Protocol {
    // App -> shell service
    const val START = 1          // IBinder listener, int naturalW, int naturalH
    const val SET_PARAMS = 2     // float swipeFraction, float pinchIn, float pinchOut
    const val INJECT_KEY = 3     // int keyCode
    const val INJECT_SWIPE = 4   // float x1, y1, x2, y2, int durationMs
    const val RUN_COMMAND = 5    // String command
    const val STOP = 6
    const val DESTROY = 16777114 // Shizuku's reserved "destroy" transaction

    // Shell service -> app listener
    const val CB_GESTURE = 1     // int fingers, int type ordinal, float dx, float dy
    const val CB_STATUS = 2      // String status
}
