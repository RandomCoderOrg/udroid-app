package org.randomcoder.udroid.x11;

final class X11NativeBridge {
    static {
        System.loadLibrary("Xlorie");
    }

    private X11NativeBridge() {}

    static native boolean start(String[] arguments);
}
