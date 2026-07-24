package org.randomcoder.udroid.x11

internal object X11ServerProtocol {
    const val VERSION = 1

    const val MESSAGE_START = 1
    const val MESSAGE_STOP = 2
    const val MESSAGE_STATUS = 3
    const val MESSAGE_GET_RENDERER = 4
    const val MESSAGE_RENDERER = 5

    const val KEY_RUNTIME_DIRECTORY = "runtime-directory"
    const val KEY_XKB_ROOT = "xkb-root"
    const val KEY_BOOT_ID = "boot-id"
    const val KEY_STATE = "state"
    const val KEY_DETAIL = "detail"
    const val KEY_SOCKET_PATH = "socket-path"
    const val KEY_STARTUP_MILLIS = "startup-millis"
    const val KEY_PID = "pid"
    const val KEY_RENDERER_FD = "renderer-fd"

    const val STATE_STARTING = "starting"
    const val STATE_READY = "ready"
    const val STATE_FAILED = "failed"
    const val STATE_STOPPING = "stopping"
}
