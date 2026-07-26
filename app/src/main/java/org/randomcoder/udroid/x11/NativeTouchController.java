package org.randomcoder.udroid.x11;

import java.util.Arrays;

/**
 * Maps Android pointer IDs onto Lorie's bounded XI2 touch slots.
 *
 * Android pointer IDs are sparse and reusable, so they must never be used as
 * direct indexes into Lorie's fixed 20-contact device. This controller owns the
 * mapping and uses fixed primitive arrays so move-heavy input does not allocate.
 */
final class NativeTouchController {
    private static final int FREE_POINTER_ID = -1;

    private final X11InputSink input;
    private final int[] pointerIds = new int[X11InputSink.MAX_TOUCH_CONTACTS];
    private final int[] lastX = new int[X11InputSink.MAX_TOUCH_CONTACTS];
    private final int[] lastY = new int[X11InputSink.MAX_TOUCH_CONTACTS];
    private final int[] seenFrame = new int[X11InputSink.MAX_TOUCH_CONTACTS];
    private final int[] touchFrame =
            new int[X11InputSink.MAX_TOUCH_CONTACTS * X11InputSink.TOUCH_EVENT_STRIDE];
    private int touchFrameEventCount;
    private int frame = 1;

    NativeTouchController(X11InputSink input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        this.input = input;
        Arrays.fill(pointerIds, FREE_POINTER_ID);
    }

    boolean handleDown(int pointerId, int x, int y) {
        if (pointerId < 0) {
            return false;
        }
        int slot = findSlot(pointerId);
        if (slot >= 0) {
            lastX[slot] = x;
            lastY[slot] = y;
            return true;
        }
        slot = allocateSlot(pointerId);
        if (slot < 0) {
            return false;
        }
        lastX[slot] = x;
        lastY[slot] = y;
        beginTouchFrame();
        appendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, slot, x, y);
        flushTouchFrame();
        return true;
    }

    void beginMoveFrame() {
        beginTouchFrame();
        if (frame == Integer.MAX_VALUE) {
            Arrays.fill(seenFrame, 0);
            frame = 1;
        } else {
            frame++;
        }
    }

    boolean handleMove(int pointerId, int x, int y) {
        if (pointerId < 0) {
            return false;
        }
        int slot = findSlot(pointerId);
        if (slot < 0) {
            slot = allocateSlot(pointerId);
            if (slot < 0) {
                return false;
            }
            lastX[slot] = x;
            lastY[slot] = y;
            seenFrame[slot] = frame;
            appendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, slot, x, y);
            return true;
        }
        lastX[slot] = x;
        lastY[slot] = y;
        seenFrame[slot] = frame;
        appendTouchEvent(X11InputSink.XI_TOUCH_UPDATE, slot, x, y);
        return true;
    }

    void endMoveFrame() {
        for (int slot = 0; slot < pointerIds.length; slot++) {
            if (pointerIds[slot] != FREE_POINTER_ID && seenFrame[slot] != frame) {
                endSlot(slot, lastX[slot], lastY[slot]);
            }
        }
        flushTouchFrame();
    }

    boolean handleUp(int pointerId, int x, int y) {
        if (pointerId < 0) {
            return false;
        }
        int slot = findSlot(pointerId);
        if (slot < 0) {
            return false;
        }
        beginTouchFrame();
        appendTouchEvent(X11InputSink.XI_TOUCH_UPDATE, slot, x, y);
        endSlot(slot, x, y);
        flushTouchFrame();
        return true;
    }

    void cancel() {
        beginTouchFrame();
        for (int slot = 0; slot < pointerIds.length; slot++) {
            if (pointerIds[slot] != FREE_POINTER_ID) {
                endSlot(slot, lastX[slot], lastY[slot]);
            }
        }
        flushTouchFrame();
    }

    int activeCount() {
        int count = 0;
        for (int pointerId : pointerIds) {
            if (pointerId != FREE_POINTER_ID) {
                count++;
            }
        }
        return count;
    }

    int slotForPointerId(int pointerId) {
        return findSlot(pointerId);
    }

    private int findSlot(int pointerId) {
        for (int slot = 0; slot < pointerIds.length; slot++) {
            if (pointerIds[slot] == pointerId) {
                return slot;
            }
        }
        return -1;
    }

    private int allocateSlot(int pointerId) {
        for (int slot = 0; slot < pointerIds.length; slot++) {
            if (pointerIds[slot] == FREE_POINTER_ID) {
                pointerIds[slot] = pointerId;
                seenFrame[slot] = frame;
                return slot;
            }
        }
        return -1;
    }

    private void endSlot(int slot, int x, int y) {
        appendTouchEvent(X11InputSink.XI_TOUCH_END, slot, x, y);
        pointerIds[slot] = FREE_POINTER_ID;
        lastX[slot] = 0;
        lastY[slot] = 0;
        seenFrame[slot] = 0;
    }

    private void beginTouchFrame() {
        touchFrameEventCount = 0;
    }

    private void appendTouchEvent(int action, int slot, int x, int y) {
        if (touchFrameEventCount >= X11InputSink.MAX_TOUCH_CONTACTS) {
            return;
        }
        int offset = touchFrameEventCount * X11InputSink.TOUCH_EVENT_STRIDE;
        touchFrame[offset + X11InputSink.TOUCH_ACTION_OFFSET] = action;
        touchFrame[offset + X11InputSink.TOUCH_SLOT_OFFSET] = slot;
        touchFrame[offset + X11InputSink.TOUCH_X_OFFSET] = x;
        touchFrame[offset + X11InputSink.TOUCH_Y_OFFSET] = y;
        touchFrameEventCount++;
    }

    private void flushTouchFrame() {
        if (touchFrameEventCount == 0) {
            return;
        }
        input.sendTouchFrame(touchFrame, touchFrameEventCount);
        touchFrameEventCount = 0;
    }
}
