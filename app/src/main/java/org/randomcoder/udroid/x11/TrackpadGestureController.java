package org.randomcoder.udroid.x11;

import android.view.MotionEvent;

import com.termux.x11.input.InputStub;

import java.util.Arrays;

/**
 * Converts touchscreen gestures into a desktop-style relative pointer stream.
 *
 * MotionEvent is read in place and all gesture state lives in bounded primitive
 * arrays. The move path does not allocate.
 */
final class TrackpadGestureController {
    private static final int MAX_ANDROID_POINTER_IDS = 32;

    private final X11InputSink sink;
    private final float touchSlop;
    private final float touchSlopSquared;
    private final float doubleTapSlopSquared;
    private final long doubleTapTimeoutMillis;
    private final long longPressTimeoutMillis;
    private final boolean[] activePointers = new boolean[MAX_ANDROID_POINTER_IDS];
    private final float[] initialPointerX = new float[MAX_ANDROID_POINTER_IDS];
    private final float[] initialPointerY = new float[MAX_ANDROID_POINTER_IDS];

    private boolean gestureActive;
    private boolean tapCancelled;
    private boolean scrollActive;
    private boolean pinchActive;
    private boolean dragActive;
    private boolean doubleTapDragCandidate;
    private boolean longPressEligible;
    private int maxPointerCount;
    private long downTimeMillis;
    private float lastPrimaryX;
    private float lastPrimaryY;
    private float initialCentroidX;
    private float initialCentroidY;
    private float initialSpan;
    private float lastCentroidX;
    private float lastCentroidY;
    private long lastTapTimeMillis = Long.MIN_VALUE;
    private float lastTapX;
    private float lastTapY;
    private float speed = 1f;

    TrackpadGestureController(
            X11InputSink sink,
            float touchSlop,
            float doubleTapSlop,
            long doubleTapTimeoutMillis,
            long longPressTimeoutMillis
    ) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        this.sink = sink;
        this.touchSlop = touchSlop;
        this.touchSlopSquared = touchSlop * touchSlop;
        this.doubleTapSlopSquared = doubleTapSlop * doubleTapSlop;
        this.doubleTapTimeoutMillis = doubleTapTimeoutMillis;
        this.longPressTimeoutMillis = longPressTimeoutMillis;
    }

    void setSpeed(float speed) {
        this.speed = Math.max(0.25f, Math.min(3f, speed));
    }

    boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        long eventTime = event.getEventTime();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                handleDown(
                        eventTime,
                        event.getPointerId(0),
                        event.getX(0),
                        event.getY(0)
                );
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                handlePointerDown(
                        eventTime,
                        event.getPointerId(actionIndex),
                        event.getX(actionIndex),
                        event.getY(actionIndex),
                        event.getPointerCount(),
                        centroidX(event, -1),
                        centroidY(event, -1),
                        span(event, -1)
                );
                return true;
            case MotionEvent.ACTION_MOVE:
                handleMove(
                        eventTime,
                        event.getPointerCount(),
                        event.getX(0),
                        event.getY(0),
                        centroidX(event, -1),
                        centroidY(event, -1),
                        span(event, -1),
                        maxDisplacementSquared(event)
                );
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                handlePointerUp(
                        event.getPointerId(actionIndex),
                        event.getPointerCount() - 1,
                        centroidX(event, actionIndex),
                        centroidY(event, actionIndex),
                        span(event, actionIndex),
                        maxDisplacementSquared(event)
                );
                return true;
            case MotionEvent.ACTION_UP:
                handleUp(
                        eventTime,
                        event.getPointerId(0),
                        event.getX(0),
                        event.getY(0),
                        maxDisplacementSquared(event)
                );
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
            default:
                return true;
        }
    }

    void handleDown(long eventTime, int pointerId, float x, float y) {
        resetGestureState();
        gestureActive = true;
        downTimeMillis = eventTime;
        lastPrimaryX = x;
        lastPrimaryY = y;
        initialCentroidX = x;
        initialCentroidY = y;
        lastCentroidX = x;
        lastCentroidY = y;
        maxPointerCount = 1;
        longPressEligible = true;
        setPointerInitialPosition(pointerId, x, y);

        long interval = eventTime - lastTapTimeMillis;
        float deltaX = x - lastTapX;
        float deltaY = y - lastTapY;
        doubleTapDragCandidate =
                lastTapTimeMillis != Long.MIN_VALUE &&
                        interval >= 0 &&
                        interval <= doubleTapTimeoutMillis &&
                        deltaX * deltaX + deltaY * deltaY <= doubleTapSlopSquared;
    }

    void handlePointerDown(
            long eventTime,
            int pointerId,
            float x,
            float y,
            int pointerCount,
            float centroidX,
            float centroidY,
            float span
    ) {
        if (!gestureActive) {
            handleDown(eventTime, pointerId, x, y);
            return;
        }
        setPointerInitialPosition(pointerId, x, y);
        maxPointerCount = Math.max(maxPointerCount, pointerCount);
        longPressEligible = false;
        doubleTapDragCandidate = false;
        initialCentroidX = centroidX;
        initialCentroidY = centroidY;
        lastCentroidX = centroidX;
        lastCentroidY = centroidY;
        initialSpan = span;
    }

    void handleMove(
            long eventTime,
            int pointerCount,
            float primaryX,
            float primaryY,
            float centroidX,
            float centroidY,
            float span,
            float maxDisplacementSquared
    ) {
        if (!gestureActive) {
            return;
        }
        boolean movedBeyondSlop = maxDisplacementSquared > touchSlopSquared;

        if (pointerCount == 1 && maxPointerCount == 1) {
            float deltaX = primaryX - lastPrimaryX;
            float deltaY = primaryY - lastPrimaryY;
            long heldFor = eventTime - downTimeMillis;

            if (!dragActive &&
                    doubleTapDragCandidate &&
                    movedBeyondSlop) {
                beginDrag();
            } else if (!dragActive &&
                    longPressEligible &&
                    heldFor >= longPressTimeoutMillis) {
                beginDrag();
            }

            if (movedBeyondSlop && heldFor < longPressTimeoutMillis) {
                longPressEligible = false;
            }
            if (movedBeyondSlop) {
                tapCancelled = true;
            }

            if (deltaX != 0 || deltaY != 0) {
                sink.sendMouseEvent(
                        deltaX * speed,
                        deltaY * speed,
                        InputStub.BUTTON_UNDEFINED,
                        false,
                        true
                );
            }
            lastPrimaryX = primaryX;
            lastPrimaryY = primaryY;
            return;
        }

        if (movedBeyondSlop) {
            tapCancelled = true;
        }
        if (pointerCount != 2 || maxPointerCount > 2) {
            return;
        }

        float centroidDeltaX = centroidX - initialCentroidX;
        float centroidDeltaY = centroidY - initialCentroidY;
        float centroidTravel =
                (float) Math.hypot(centroidDeltaX, centroidDeltaY);
        float spanChange = Math.abs(span - initialSpan);
        if (!scrollActive && !pinchActive) {
            if (spanChange > touchSlop && spanChange > centroidTravel) {
                pinchActive = true;
            } else if (centroidTravel > touchSlop) {
                scrollActive = true;
            }
        }

        if (scrollActive) {
            float distanceX = lastCentroidX - centroidX;
            float distanceY = lastCentroidY - centroidY;
            if (distanceX != 0 || distanceY != 0) {
                sink.sendMouseWheelEvent(distanceX, distanceY);
            }
        }
        lastCentroidX = centroidX;
        lastCentroidY = centroidY;
    }

    void handlePointerUp(
            int pointerId,
            int remainingPointerCount,
            float centroidX,
            float centroidY,
            float span,
            float maxDisplacementSquared
    ) {
        if (!gestureActive) {
            return;
        }
        if (maxDisplacementSquared > touchSlopSquared) {
            tapCancelled = true;
        }
        clearPointer(pointerId);
        if (remainingPointerCount > 0) {
            initialCentroidX = centroidX;
            initialCentroidY = centroidY;
            lastCentroidX = centroidX;
            lastCentroidY = centroidY;
            initialSpan = span;
        }
    }

    void handleUp(
            long eventTime,
            int pointerId,
            float x,
            float y,
            float maxDisplacementSquared
    ) {
        if (!gestureActive) {
            return;
        }
        if (maxDisplacementSquared > touchSlopSquared) {
            tapCancelled = true;
        }
        clearPointer(pointerId);

        if (dragActive) {
            sink.sendMouseEvent(0, 0, InputStub.BUTTON_LEFT, false, true);
            clearTapHistory();
        } else if (!tapCancelled &&
                !scrollActive &&
                !pinchActive &&
                eventTime - downTimeMillis < longPressTimeoutMillis) {
            int button = buttonForPointerCount(maxPointerCount);
            if (button != InputStub.BUTTON_UNDEFINED) {
                sendClick(button);
                if (button == InputStub.BUTTON_LEFT) {
                    lastTapTimeMillis = eventTime;
                    lastTapX = x;
                    lastTapY = y;
                } else {
                    clearTapHistory();
                }
            }
        } else {
            clearTapHistory();
        }
        resetGestureState();
    }

    void cancel() {
        sink.releaseAllInput();
        clearTapHistory();
        resetGestureState();
    }

    private void beginDrag() {
        sink.sendMouseEvent(0, 0, InputStub.BUTTON_LEFT, true, true);
        dragActive = true;
        tapCancelled = true;
        clearTapHistory();
    }

    private void sendClick(int button) {
        sink.sendMouseEvent(0, 0, button, true, true);
        sink.sendMouseEvent(0, 0, button, false, true);
    }

    private void resetGestureState() {
        gestureActive = false;
        tapCancelled = false;
        scrollActive = false;
        pinchActive = false;
        dragActive = false;
        doubleTapDragCandidate = false;
        longPressEligible = false;
        maxPointerCount = 0;
        downTimeMillis = 0;
        initialSpan = 0;
        Arrays.fill(activePointers, false);
    }

    private void clearTapHistory() {
        lastTapTimeMillis = Long.MIN_VALUE;
        lastTapX = 0;
        lastTapY = 0;
    }

    private void setPointerInitialPosition(int pointerId, float x, float y) {
        if (pointerId < 0 || pointerId >= MAX_ANDROID_POINTER_IDS) {
            return;
        }
        activePointers[pointerId] = true;
        initialPointerX[pointerId] = x;
        initialPointerY[pointerId] = y;
    }

    private void clearPointer(int pointerId) {
        if (pointerId >= 0 && pointerId < MAX_ANDROID_POINTER_IDS) {
            activePointers[pointerId] = false;
        }
    }

    private float maxDisplacementSquared(MotionEvent event) {
        float maximum = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            int pointerId = event.getPointerId(index);
            if (pointerId < 0 ||
                    pointerId >= MAX_ANDROID_POINTER_IDS ||
                    !activePointers[pointerId]) {
                continue;
            }
            float deltaX = event.getX(index) - initialPointerX[pointerId];
            float deltaY = event.getY(index) - initialPointerY[pointerId];
            maximum = Math.max(maximum, deltaX * deltaX + deltaY * deltaY);
        }
        return maximum;
    }

    private static int buttonForPointerCount(int pointerCount) {
        switch (pointerCount) {
            case 1:
                return InputStub.BUTTON_LEFT;
            case 2:
                return InputStub.BUTTON_RIGHT;
            case 3:
                return InputStub.BUTTON_MIDDLE;
            default:
                return InputStub.BUTTON_UNDEFINED;
        }
    }

    private static float centroidX(MotionEvent event, int excludedIndex) {
        float total = 0;
        int count = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index == excludedIndex) {
                continue;
            }
            total += event.getX(index);
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    private static float centroidY(MotionEvent event, int excludedIndex) {
        float total = 0;
        int count = 0;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index == excludedIndex) {
                continue;
            }
            total += event.getY(index);
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    private static float span(MotionEvent event, int excludedIndex) {
        int first = -1;
        int second = -1;
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (index == excludedIndex) {
                continue;
            }
            if (first == -1) {
                first = index;
            } else {
                second = index;
                break;
            }
        }
        if (second == -1) {
            return 0;
        }
        return (float) Math.hypot(
                event.getX(second) - event.getX(first),
                event.getY(second) - event.getY(first)
        );
    }
}
