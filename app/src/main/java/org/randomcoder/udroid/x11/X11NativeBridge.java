package org.randomcoder.udroid.x11;

import android.os.ParcelFileDescriptor;

final class X11NativeBridge {
    static {
        System.loadLibrary("Xlorie");
    }

    private X11NativeBridge() {}

    static native boolean start(String[] arguments);
    static native ParcelFileDescriptor getXConnection();
}
