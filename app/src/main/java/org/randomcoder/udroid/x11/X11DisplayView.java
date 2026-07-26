package org.randomcoder.udroid.x11;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.opengl.GLES20;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal uDroid-owned Android surface for the Termux:X11 renderer.
 *
 * This intentionally has no dependency on Termux:X11's Activity, preferences,
 * broadcasts, or navigation. The X server is owned by the supervisor and can
 * continue running while this view is detached.
 */
public final class X11DisplayView extends SurfaceView
        implements InputStub, X11InputSink.TouchFrameTransport {
    private static final int HAL_PIXEL_FORMAT_BGRA_8888 = 5;
    private final InputEventSender inputSender;
    private final X11InputSink inputSink;
    private final TrackpadGestureController trackpadGestures;
    private final NativeTouchController nativeTouches;
    private final float[] mappedPoint = new float[2];
    private final InputMethodManager inputMethodManager;
    private final BaseInputConnection inputConnection;
    private boolean rendererAttached;
    private boolean surfaceAvailable;
    private boolean imeHasCommittedText;
    private CharSequence composingText;
    private int pressedMouseButton = BUTTON_UNDEFINED;
    private int viewportLeft;
    private int viewportTop;
    private int viewportWidth;
    private int viewportHeight;
    private int guestWidth;
    private int guestHeight;
    private X11Settings settings = new X11Settings();

    public X11DisplayView(Context context) {
        super(context);
        inputSink = new StatefulX11InputSink(this);
        inputSender = new InputEventSender(inputSink);
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        trackpadGestures =
                new TrackpadGestureController(
                        inputSink,
                        viewConfiguration.getScaledTouchSlop(),
                        viewConfiguration.getScaledDoubleTapSlop(),
                        ViewConfiguration.getDoubleTapTimeout(),
                        ViewConfiguration.getLongPressTimeout()
                );
        nativeTouches = new NativeTouchController(inputSink);
        inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        inputConnection = new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                return replaceImeText(text, false);
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return replaceImeText(text, true);
            }

            @Override
            public boolean finishComposingText() {
                composingText = null;
                return true;
            }

            @Override
            public Editable getEditable() {
                return null;
            }

            @Override
            public CharSequence getTextBeforeCursor(int length, int flags) {
                return " ";
            }

            @Override
            public CharSequence getTextAfterCursor(int length, int flags) {
                return " ";
            }

            @Override
            public boolean setComposingRegion(int start, int end) {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                sendDeleteKeys(beforeLength);
                sendForwardDeleteKeys(afterLength);
                composingText = null;
                return true;
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(
                    int beforeLength,
                    int afterLength
            ) {
                sendDeleteKeys(beforeLength);
                sendForwardDeleteKeys(afterLength);
                composingText = null;
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return dispatchKeyEvent(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                sendAndroidKey(KeyEvent.KEYCODE_ENTER);
                return true;
            }
        };
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
                releaseAllInput();
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
        releaseAllInput();
        connect(-1);
        rendererAttached = false;
    }

    public boolean isRendererAttached() {
        return rendererAttached && connected();
    }

    public void applySettings(X11Settings updatedSettings) {
        if (updatedSettings == null) return;
        boolean inputModeChanged = updatedSettings.getTouchMode() != settings.getTouchMode();
        boolean geometryChanged =
                updatedSettings.getResolutionMode() != settings.getResolutionMode() ||
                        updatedSettings.getDisplayScalePercent() !=
                                settings.getDisplayScalePercent() ||
                        updatedSettings.getExactWidth() != settings.getExactWidth() ||
                        updatedSettings.getExactHeight() != settings.getExactHeight() ||
                        updatedSettings.getStretchDisplay() != settings.getStretchDisplay();
        boolean filterChanged =
                updatedSettings.getDisplayFilter() != settings.getDisplayFilter();
        if (inputModeChanged) {
            releaseAllInput();
        }
        settings = updatedSettings;
        inputSender.preferScancodes = settings.getPreferScancodes();
        trackpadGestures.setSpeed(settings.getTrackpadSpeedPercent() / 100f);
        setKeepScreenOn(settings.getKeepScreenOn());
        if (rendererAttached && geometryChanged) {
            publishGeometry(getWidth(), getHeight());
        } else if (rendererAttached && filterChanged) {
            setFiltering(
                    settings.getDisplayFilter() == X11DisplayFilter.NEAREST
                            ? GLES20.GL_NEAREST
                            : GLES20.GL_LINEAR
            );
        }
    }

    private void publishGeometry(int width, int height) {
        if (!rendererAttached || width <= 0 || height <= 0) return;
        X11Viewport viewport = X11GeometryCalculator.calculate(width, height, settings);
        viewportLeft = viewport.getLeft();
        viewportTop = viewport.getTop();
        viewportWidth = viewport.getWidth();
        viewportHeight = viewport.getHeight();
        guestWidth = viewport.getGuestWidth();
        guestHeight = viewport.getGuestHeight();
        int refreshRate = getDisplay() == null ? 60 : Math.round(getDisplay().getRefreshRate());
        setViewport(
                viewportLeft,
                viewportTop,
                viewportWidth,
                viewportHeight,
                guestWidth,
                guestHeight
        );
        setFiltering(
                settings.getDisplayFilter() == X11DisplayFilter.NEAREST
                        ? GLES20.GL_NEAREST
                        : GLES20.GL_LINEAR
        );
        sendWindowChange(guestWidth, guestHeight, refreshRate, "builtin");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        if (rendererAttached) {
            return inputSender.sendKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!rendererAttached) return false;
        requestFocus();
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            return handleMouseEvent(event);
        }

        int action = event.getActionMasked();
        if (settings.getTouchMode() == X11TouchMode.TRACKPAD) {
            return handleTrackpadTouch(event);
        }
        if (settings.getTouchMode() == X11TouchMode.NATIVE) {
            return handleNativeTouch(event);
        }

        mapToGuest(
                event.getX(event.getActionIndex()),
                event.getY(event.getActionIndex()),
                mappedPoint
        );
        float pointX = mappedPoint[0];
        float pointY = mappedPoint[1];
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                inputSink.sendMouseEvent(
                        pointX,
                        pointY,
                        BUTTON_UNDEFINED,
                        false,
                        false
                );
                inputSink.sendMouseEvent(0, 0, BUTTON_LEFT, true, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                mapToGuest(event.getX(0), event.getY(0), mappedPoint);
                inputSink.sendMouseEvent(
                        mappedPoint[0],
                        mappedPoint[1],
                        BUTTON_UNDEFINED,
                        false,
                        false
                );
                return true;
            case MotionEvent.ACTION_UP:
                inputSink.sendMouseEvent(
                        pointX,
                        pointY,
                        BUTTON_UNDEFINED,
                        false,
                        false
                );
                releaseTouchButton();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAllInput();
                return true;
            default:
                return true;
        }
    }

    private boolean handleTrackpadTouch(MotionEvent event) {
        return trackpadGestures.onTouchEvent(event);
    }

    private boolean handleNativeTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                nativeTouches.cancel();
                mapToGuest(event.getX(actionIndex), event.getY(actionIndex), mappedPoint);
                nativeTouches.handleDown(
                        event.getPointerId(actionIndex),
                        Math.round(mappedPoint[0]),
                        Math.round(mappedPoint[1])
                );
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                mapToGuest(event.getX(actionIndex), event.getY(actionIndex), mappedPoint);
                nativeTouches.handleDown(
                        event.getPointerId(actionIndex),
                        Math.round(mappedPoint[0]),
                        Math.round(mappedPoint[1])
                );
                return true;
            case MotionEvent.ACTION_MOVE:
                // Historical samples are already stale when this callback runs. Replaying
                // them as individual JNI/socket writes creates visible catch-up latency,
                // especially when several contacts share one batched MotionEvent.
                sendNativeMoveFrame(event);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                mapToGuest(event.getX(actionIndex), event.getY(actionIndex), mappedPoint);
                nativeTouches.handleUp(
                        event.getPointerId(actionIndex),
                        Math.round(mappedPoint[0]),
                        Math.round(mappedPoint[1])
                );
                if (action == MotionEvent.ACTION_UP) {
                    nativeTouches.cancel();
                }
                if (action == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                nativeTouches.cancel();
                return true;
            default:
                return true;
        }
    }

    private void sendNativeMoveFrame(MotionEvent event) {
        nativeTouches.beginMoveFrame();
        for (int pointerIndex = 0; pointerIndex < event.getPointerCount(); pointerIndex++) {
            mapToGuest(
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                    mappedPoint
            );
            nativeTouches.handleMove(
                    event.getPointerId(pointerIndex),
                    Math.round(mappedPoint[0]),
                    Math.round(mappedPoint[1])
            );
        }
        nativeTouches.endMoveFrame();
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!rendererAttached ||
                (!event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                        !event.isFromSource(InputDevice.SOURCE_TOUCHPAD))) {
            return super.onGenericMotionEvent(event);
        }
        return handleMouseEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType =
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
        outAttrs.actionLabel = "↵";
        return inputConnection;
    }

    public void showKeyboard() {
        requestFocus();
        post(() -> inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT));
    }

    private boolean handleMouseEvent(MotionEvent event) {
        mapToGuest(event.getX(), event.getY(), mappedPoint);
        inputSink.sendMouseEvent(
                mappedPoint[0],
                mappedPoint[1],
                BUTTON_UNDEFINED,
                false,
                false
        );
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_SCROLL:
                inputSink.sendMouseWheelEvent(
                        -120f * event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        120f * event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                );
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_DOWN:
                int pressed = mapButton(event);
                if (pressed != BUTTON_UNDEFINED && pressedMouseButton != pressed) {
                    inputSink.sendMouseEvent(0, 0, pressed, true, false);
                    pressedMouseButton = pressed;
                }
                return true;
            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressedMouseButton != BUTTON_UNDEFINED) {
                    inputSink.sendMouseEvent(
                            0,
                            0,
                            pressedMouseButton,
                            false,
                            false
                    );
                    pressedMouseButton = BUTTON_UNDEFINED;
                }
                return true;
            default:
                return true;
        }
    }

    private void mapToGuest(float x, float y, float[] destination) {
        if (viewportWidth <= 0 || viewportHeight <= 0 ||
                guestWidth <= 0 || guestHeight <= 0) {
            destination[0] = x;
            destination[1] = y;
            return;
        }
        float mappedX =
                (x - viewportLeft) * guestWidth / (float) viewportWidth;
        float mappedY =
                (y - viewportTop) * guestHeight / (float) viewportHeight;
        destination[0] = Math.max(0, Math.min(guestWidth, mappedX));
        destination[1] = Math.max(0, Math.min(guestHeight, mappedY));
    }

    private static int mapButton(MotionEvent event) {
        int button = event.getActionButton();
        if (button == 0) {
            int state = event.getButtonState();
            if ((state & MotionEvent.BUTTON_PRIMARY) != 0) button = MotionEvent.BUTTON_PRIMARY;
            else if ((state & MotionEvent.BUTTON_SECONDARY) != 0) {
                button = MotionEvent.BUTTON_SECONDARY;
            } else if ((state & MotionEvent.BUTTON_TERTIARY) != 0) {
                button = MotionEvent.BUTTON_TERTIARY;
            }
        }
        if (button == MotionEvent.BUTTON_PRIMARY) return BUTTON_LEFT;
        if (button == MotionEvent.BUTTON_SECONDARY) return BUTTON_RIGHT;
        if (button == MotionEvent.BUTTON_TERTIARY) return BUTTON_MIDDLE;
        return BUTTON_UNDEFINED;
    }

    private void releaseTouchButton() {
        inputSink.sendMouseEvent(0, 0, BUTTON_LEFT, false, false);
    }

    private void releaseAllInput() {
        trackpadGestures.cancel();
        nativeTouches.cancel();
        inputSink.releaseAllInput();
        pressedMouseButton = BUTTON_UNDEFINED;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            releaseAllInput();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAllInput();
        super.onDetachedFromWindow();
    }

    private boolean replaceImeText(CharSequence replacement, boolean keepComposing) {
        String previous = composingText == null ? "" : composingText.toString();
        String next = replacement == null ? "" : replacement.toString();
        if (!rendererAttached) {
            composingText = keepComposing ? next : null;
            return true;
        }
        int sharedPrefix = 0;
        int sharedLimit = Math.min(previous.length(), next.length());
        while (sharedPrefix < sharedLimit &&
                previous.charAt(sharedPrefix) == next.charAt(sharedPrefix)) {
            sharedPrefix++;
        }
        sendDeleteKeys(previous.length() - sharedPrefix);
        if (sharedPrefix < next.length()) {
            sendTextEvent(
                    next.substring(sharedPrefix).getBytes(StandardCharsets.UTF_8)
            );
        }
        composingText = keepComposing ? next : null;
        imeHasCommittedText = true;
        return true;
    }

    private void sendDeleteKeys(int count) {
        for (int index = 0; index < count; index++) {
            sendAndroidKey(KeyEvent.KEYCODE_DEL);
        }
    }

    private void sendForwardDeleteKeys(int count) {
        for (int index = 0; index < count; index++) {
            sendAndroidKey(KeyEvent.KEYCODE_FORWARD_DEL);
        }
    }

    private void sendAndroidKey(int keyCode) {
        sendKeyEvent(0, keyCode, true);
        sendKeyEvent(0, keyCode, false);
    }

    @SuppressWarnings("unused")
    public void resetIme() {
        if (!imeHasCommittedText) return;
        imeHasCommittedText = false;
        post(() -> inputMethodManager.restartInput(this));
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
    private static native void setFiltering(int filtering);
    private static native void connect(int fd);
    private static native boolean connected();
    private static native void sendWindowChange(
            int width,
            int height,
            int refreshRate,
            String displayName
    );
    @Override
    public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
        return sendKeyEvent(scanCode, keyCode, keyDown, 0);
    }

    private native boolean sendKeyEvent(
            int scanCode,
            int keyCode,
            boolean keyDown,
            int reserved
    );

    @Override
    public native void sendMouseEvent(
            float x,
            float y,
            int button,
            boolean buttonDown,
            boolean relative
    );

    @Override
    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        sendMouseEvent(deltaX, deltaY, BUTTON_SCROLL, false, true);
    }

    @Override
    public native void sendTouchEvent(int action, int id, int x, int y);

    @Override
    public native void sendTouchFrame(int[] events, int eventCount);

    @Override
    public native void sendTextEvent(byte[] text);

    @Override
    public void sendStylusEvent(
            float x,
            float y,
            int pressure,
            int tiltX,
            int tiltY,
            int orientation,
            int buttons,
            boolean eraser,
            boolean mouseMode
    ) {
        sendMouseEvent(x, y, BUTTON_UNDEFINED, false, false);
    }

    static {
        System.loadLibrary("Xlorie");
    }
}
