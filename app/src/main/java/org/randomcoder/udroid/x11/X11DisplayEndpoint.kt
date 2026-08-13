package org.randomcoder.udroid.x11

import java.io.File

enum class X11GuestTransport(val journalValue: String) {
    DIRECT_ROOTFS("direct_rootfs"),
    PRIVATE_BIND("private_bind"),
}

data class X11DisplayEndpoint(
    val runtimeDirectory: File,
    val socketDirectory: File,
    val transport: X11GuestTransport,
) {
    val requiresGuestBind: Boolean
        get() = transport == X11GuestTransport.PRIVATE_BIND
}
