package org.randomcoder.udroid.x11;

import com.termux.x11.input.InputStub;

/**
 * Tracks guest-visible input state while forwarding events to Lorie.
 *
 * This class intentionally knows nothing about MotionEvent or view geometry.
 * Gesture strategies and coordinate mapping can therefore be tested
 * independently from the native transport.
 */
public final class StatefulX11InputSink implements X11InputSink {
    private final InputStub delegate;
    private final boolean[] pressedButtons = new boolean[BUTTON_RIGHT + 1];
    private final boolean[] relativeButtons = new boolean[BUTTON_RIGHT + 1];
    private final boolean[] activeTouches = new boolean[MAX_TOUCH_CONTACTS];
    private final int[] validatedTouchFrame =
            new int[MAX_TOUCH_CONTACTS * TOUCH_EVENT_STRIDE];
    private final int[] releaseTouchFrame =
            new int[MAX_TOUCH_CONTACTS * TOUCH_EVENT_STRIDE];

    public StatefulX11InputSink(InputStub delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.delegate = delegate;
    }

    @Override
    public void sendMouseEvent(
            float x,
            float y,
            int whichButton,
            boolean buttonDown,
            boolean relative
    ) {
        if (whichButton >= BUTTON_LEFT && whichButton <= BUTTON_RIGHT) {
            if (pressedButtons[whichButton] == buttonDown) {
                return;
            }
            pressedButtons[whichButton] = buttonDown;
            relativeButtons[whichButton] = relative;
        }
        delegate.sendMouseEvent(x, y, whichButton, buttonDown, relative);
    }

    @Override
    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        delegate.sendMouseWheelEvent(deltaX, deltaY);
    }

    @Override
    public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
        return delegate.sendKeyEvent(scanCode, keyCode, keyDown);
    }

    @Override
    public void sendTextEvent(byte[] utf8Bytes) {
        delegate.sendTextEvent(utf8Bytes);
    }

    @Override
    public void sendTouchEvent(int action, int pointerId, int x, int y) {
        if (!acceptTouchEvent(action, pointerId)) {
            return;
        }
        delegate.sendTouchEvent(action, pointerId, x, y);
    }

    @Override
    public void sendTouchFrame(int[] events, int eventCount) {
        if (events == null || eventCount <= 0) {
            return;
        }
        int availableEvents = events.length / TOUCH_EVENT_STRIDE;
        int count = Math.min(Math.min(eventCount, availableEvents), MAX_TOUCH_CONTACTS);
        int accepted = 0;
        for (int index = 0; index < count; index++) {
            int source = index * TOUCH_EVENT_STRIDE;
            int action = events[source + TOUCH_ACTION_OFFSET];
            int slot = events[source + TOUCH_SLOT_OFFSET];
            if (!acceptTouchEvent(action, slot)) {
                continue;
            }
            int destination = accepted * TOUCH_EVENT_STRIDE;
            validatedTouchFrame[destination + TOUCH_ACTION_OFFSET] = action;
            validatedTouchFrame[destination + TOUCH_SLOT_OFFSET] = slot;
            validatedTouchFrame[destination + TOUCH_X_OFFSET] =
                    events[source + TOUCH_X_OFFSET];
            validatedTouchFrame[destination + TOUCH_Y_OFFSET] =
                    events[source + TOUCH_Y_OFFSET];
            accepted++;
        }
        if (accepted == 0) {
            return;
        }
        if (delegate instanceof X11InputSink.TouchFrameTransport) {
            ((X11InputSink.TouchFrameTransport) delegate)
                    .sendTouchFrame(validatedTouchFrame, accepted);
            return;
        }
        for (int index = 0; index < accepted; index++) {
            int offset = index * TOUCH_EVENT_STRIDE;
            delegate.sendTouchEvent(
                    validatedTouchFrame[offset + TOUCH_ACTION_OFFSET],
                    validatedTouchFrame[offset + TOUCH_SLOT_OFFSET],
                    validatedTouchFrame[offset + TOUCH_X_OFFSET],
                    validatedTouchFrame[offset + TOUCH_Y_OFFSET]
            );
        }
    }

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
        delegate.sendStylusEvent(
                x,
                y,
                pressure,
                tiltX,
                tiltY,
                orientation,
                buttons,
                eraser,
                mouseMode
        );
    }

    @Override
    public void releaseAllInput() {
        for (int button = BUTTON_LEFT; button <= BUTTON_RIGHT; button++) {
            if (!pressedButtons[button]) {
                continue;
            }
            delegate.sendMouseEvent(
                    0,
                    0,
                    button,
                    false,
                    relativeButtons[button]
            );
            pressedButtons[button] = false;
            relativeButtons[button] = false;
        }
        int touchCount = 0;
        for (int slot = 0; slot < activeTouches.length; slot++) {
            if (!activeTouches[slot]) {
                continue;
            }
            int offset = touchCount * TOUCH_EVENT_STRIDE;
            releaseTouchFrame[offset + TOUCH_ACTION_OFFSET] = XI_TOUCH_END;
            releaseTouchFrame[offset + TOUCH_SLOT_OFFSET] = slot;
            releaseTouchFrame[offset + TOUCH_X_OFFSET] = 0;
            releaseTouchFrame[offset + TOUCH_Y_OFFSET] = 0;
            touchCount++;
        }
        if (touchCount > 0) {
            sendTouchFrame(releaseTouchFrame, touchCount);
        }
    }

    boolean isMouseButtonPressed(int button) {
        return button >= BUTTON_LEFT &&
                button <= BUTTON_RIGHT &&
                pressedButtons[button];
    }

    boolean isTouchActive(int slot) {
        return isTouchSlot(slot) && activeTouches[slot];
    }

    private static boolean isTouchSlot(int slot) {
        return slot >= 0 && slot < MAX_TOUCH_CONTACTS;
    }

    private boolean acceptTouchEvent(int action, int pointerId) {
        if (!isTouchSlot(pointerId)) {
            return false;
        }
        switch (action) {
            case XI_TOUCH_BEGIN:
                if (activeTouches[pointerId]) {
                    return false;
                }
                activeTouches[pointerId] = true;
                return true;
            case XI_TOUCH_UPDATE:
                return activeTouches[pointerId];
            case XI_TOUCH_END:
                if (!activeTouches[pointerId]) {
                    return false;
                }
                activeTouches[pointerId] = false;
                return true;
            default:
                return false;
        }
    }
}
