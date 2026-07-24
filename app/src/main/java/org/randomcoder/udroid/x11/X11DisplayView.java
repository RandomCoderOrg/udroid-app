package org.randomcoder.udroid.x11;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * Minimal uDroid-owned Android surface for the Termux:X11 renderer.
 *
 * This intentionally has no dependency on Termux:X11's Activity, preferences,
 * broadcasts, or navigation. The X server is owned by the supervisor and can
 * continue running while this view is detached.
 */
public final class X11DisplayView extends SurfaceView {
    private static final int HAL_PIXEL_FORMAT_BGRA_8888 = 5;
    private boolean rendererAttached;
    private boolean surfaceAvailable;

    public X11DisplayView(Context context) {
        super(context);
        setBackground(new ColorDrawable(Color.TRANSPARENT) {
            @Override
            public boolean isStateful() {
                return true;
            }

            @Override
            public boolean hasFocusStateSpecified() {
                return true;
            }
        });
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                holder.setFormat(HAL_PIXEL_FORMAT_BGRA_8888);
                surfaceAvailable = true;
            }

            @Override
            public void surfaceChanged(
                    @NonNull SurfaceHolder holder,
                    int format,
                    int width,
                    int height
            ) {
                surfaceAvailable = true;
                X11DisplayView.this.surfaceChanged(holder.getSurface());
                publishGeometry(width, height);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                surfaceAvailable = false;
                X11DisplayView.this.surfaceChanged(null);
            }
        });
        nativeInit();
    }

    public void attachRenderer(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        if (rendererAttached) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
            return;
        }
        connect(descriptor.detachFd());
        rendererAttached = true;
        if (surfaceAvailable && getHolder().getSurface().isValid()) {
            surfaceChanged(getHolder().getSurface());
            publishGeometry(getWidth(), getHeight());
        }
        requestFocus();
    }

    public void detachRenderer() {
        if (!rendererAttached) return;
        connect(-1);
        rendererAttached = false;
    }

    public boolean isRendererAttached() {
        return rendererAttached && connected();
    }

    private void publishGeometry(int width, int height) {
        if (!rendererAttached || width <= 0 || height <= 0) return;
        int refreshRate = getDisplay() == null ? 60 : Math.round(getDisplay().getRefreshRate());
        setViewport(0, 0, width, height, width, height);
        sendWindowChange(width, height, refreshRate, "builtin");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (rendererAttached &&
                (event.getAction() == KeyEvent.ACTION_DOWN ||
                        event.getAction() == KeyEvent.ACTION_UP)) {
            return sendKeyEvent(
                    event.getScanCode(),
                    event.getKeyCode(),
                    event.getAction() == KeyEvent.ACTION_DOWN,
                    0
            );
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("unused")
    public void resetIme() {
        // IME integration is a later input checkpoint.
    }

    @SuppressWarnings("unused")
    public void setClipboardText(String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("X11 clipboard", text));
    }

    @SuppressWarnings("unused")
    public void requestClipboard() {
        // Clipboard synchronization is deliberately disabled for the first
        // renderer/surface checkpoint.
    }

    private native void nativeInit();
    private native void surfaceChanged(Surface surface);
    private static native void setViewport(
            int x,
            int y,
            int width,
            int height,
            int expectedWidth,
            int expectedHeight
    );
    private static native void connect(int fd);
    private static native boolean connected();
    private static native void sendWindowChange(
            int width,
            int height,
            int refreshRate,
            String displayName
    );
    private native boolean sendKeyEvent(
            int scanCode,
            int keyCode,
            boolean keyDown,
            int reserved
    );

    @SuppressWarnings("unused")
    private native void sendMouseEvent(
            float x,
            float y,
            int button,
            boolean buttonDown,
            boolean relative
    );

    @SuppressWarnings("unused")
    private native void sendTouchEvent(int action, int id, int x, int y);

    @SuppressWarnings("unused")
    private native void sendTextEvent(byte[] text);

    static {
        System.loadLibrary("Xlorie");
    }
}
