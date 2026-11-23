# Keep explicit managed/native boundaries used by embedded runtime components.

# libXlorie resolves these classes and methods by their exact JNI names.
# They are not all called from managed code, so R8 would otherwise remove
# methods such as sendTouchEvent from optimized release builds.
-keep class org.randomcoder.udroid.x11.X11DisplayView {
    native <methods>;
    public void resetIme();
    public void setClipboardText(java.lang.String);
    public void requestClipboard();
}

-keep class org.randomcoder.udroid.x11.X11NativeBridge {
    native <methods>;
}
