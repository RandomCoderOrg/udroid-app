package org.randomcoder.udroid.x11;

import static com.termux.x11.input.InputStub.BUTTON_LEFT;
import static com.termux.x11.input.InputStub.BUTTON_MIDDLE;
import static com.termux.x11.input.InputStub.BUTTON_RIGHT;
import static com.termux.x11.input.InputStub.BUTTON_UNDEFINED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.x11.input.InputStub;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class StatefulX11InputSinkTest {
    @Test
    public void recordsOrderedPointerTrace() {
        RecordingInputStub recording = new RecordingInputStub();
        StatefulX11InputSink sink = new StatefulX11InputSink(recording);

        sink.sendMouseEvent(100, 200, BUTTON_UNDEFINED, false, false);
        sink.sendMouseEvent(0, 0, BUTTON_LEFT, true, false);
        sink.sendMouseEvent(120, 220, BUTTON_UNDEFINED, false, false);
        sink.sendMouseEvent(0, 0, BUTTON_LEFT, false, false);

        assertEquals(
                List.of(
                        "mouse 100.0 200.0 0 false false",
                        "mouse 0.0 0.0 1 true false",
                        "mouse 120.0 220.0 0 false false",
                        "mouse 0.0 0.0 1 false false"
                ),
                recording.events
        );
        assertFalse(sink.isMouseButtonPressed(BUTTON_LEFT));
    }

    @Test
    public void releaseAllInputClearsButtonsAndTouchesExactlyOnce() {
        RecordingInputStub recording = new RecordingInputStub();
        StatefulX11InputSink sink = new StatefulX11InputSink(recording);

        sink.sendMouseEvent(0, 0, BUTTON_LEFT, true, false);
        sink.sendMouseEvent(0, 0, BUTTON_RIGHT, true, true);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, 3, 10, 20);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, 17, 30, 40);

        sink.releaseAllInput();
        int eventCountAfterFirstRelease = recording.events.size();
        sink.releaseAllInput();

        assertEquals(eventCountAfterFirstRelease, recording.events.size());
        assertEquals(
                List.of(
                        "mouse 0.0 0.0 1 true false",
                        "mouse 0.0 0.0 3 true true",
                        "touch 18 3 10 20",
                        "touch 18 17 30 40",
                        "mouse 0.0 0.0 1 false false",
                        "mouse 0.0 0.0 3 false true",
                        "touch 20 3 0 0",
                        "touch 20 17 0 0"
                ),
                recording.events
        );
        assertFalse(sink.isMouseButtonPressed(BUTTON_LEFT));
        assertFalse(sink.isMouseButtonPressed(BUTTON_RIGHT));
        assertFalse(sink.isTouchActive(3));
        assertFalse(sink.isTouchActive(17));
    }

    @Test
    public void rejectsInvalidAndUnbalancedTouchEvents() {
        RecordingInputStub recording = new RecordingInputStub();
        StatefulX11InputSink sink = new StatefulX11InputSink(recording);

        sink.sendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, -1, 0, 0);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, 20, 0, 0);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_UPDATE, 4, 10, 10);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_END, 4, 10, 10);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, 4, 10, 10);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_BEGIN, 4, 10, 10);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_UPDATE, 4, 11, 12);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_END, 4, 11, 12);
        sink.sendTouchEvent(X11InputSink.XI_TOUCH_END, 4, 11, 12);

        assertEquals(
                List.of(
                        "touch 18 4 10 10",
                        "touch 19 4 11 12",
                        "touch 20 4 11 12"
                ),
                recording.events
        );
        assertFalse(sink.isTouchActive(4));
    }

    @Test
    public void tracksAllSupportedButtonsIndependently() {
        RecordingInputStub recording = new RecordingInputStub();
        StatefulX11InputSink sink = new StatefulX11InputSink(recording);

        sink.sendMouseEvent(0, 0, BUTTON_LEFT, true, false);
        sink.sendMouseEvent(0, 0, BUTTON_MIDDLE, true, false);
        sink.sendMouseEvent(0, 0, BUTTON_RIGHT, true, false);

        assertTrue(sink.isMouseButtonPressed(BUTTON_LEFT));
        assertTrue(sink.isMouseButtonPressed(BUTTON_MIDDLE));
        assertTrue(sink.isMouseButtonPressed(BUTTON_RIGHT));

        sink.releaseAllInput();

        assertFalse(sink.isMouseButtonPressed(BUTTON_LEFT));
        assertFalse(sink.isMouseButtonPressed(BUTTON_MIDDLE));
        assertFalse(sink.isMouseButtonPressed(BUTTON_RIGHT));
    }

    @Test
    public void validatesAndForwardsOneAtomicTouchFrame() {
        RecordingInputStub recording = new RecordingInputStub();
        StatefulX11InputSink sink = new StatefulX11InputSink(recording);
        int[] frame = {
                X11InputSink.XI_TOUCH_BEGIN, 3, 10, 20,
                X11InputSink.XI_TOUCH_UPDATE, 3, 11, 21,
                X11InputSink.XI_TOUCH_UPDATE, 9, 50, 60,
        };

        sink.sendTouchFrame(frame, 3);

        assertEquals(List.of(2), recording.touchFrameSizes);
        assertEquals(
                List.of(
                        "touch 18 3 10 20",
                        "touch 19 3 11 21"
                ),
                recording.events
        );
    }

    private static final class RecordingInputStub
            implements X11InputSink.TouchFrameTransport {
        private final List<String> events = new ArrayList<>();
        private final List<Integer> touchFrameSizes = new ArrayList<>();

        @Override
        public void sendMouseEvent(
                float x,
                float y,
                int whichButton,
                boolean buttonDown,
                boolean relative
        ) {
            events.add(
                    "mouse " + x + " " + y + " " + whichButton + " " +
                            buttonDown + " " + relative
            );
        }

        @Override
        public void sendMouseWheelEvent(float deltaX, float deltaY) {
            events.add("wheel " + deltaX + " " + deltaY);
        }

        @Override
        public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
            events.add("key " + scanCode + " " + keyCode + " " + keyDown);
            return true;
        }

        @Override
        public void sendTextEvent(byte[] utf8Bytes) {
            events.add("text " + new String(utf8Bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void sendTouchEvent(int action, int pointerId, int x, int y) {
            events.add("touch " + action + " " + pointerId + " " + x + " " + y);
        }

        @Override
        public void sendTouchFrame(int[] packedEvents, int eventCount) {
            touchFrameSizes.add(eventCount);
            for (int index = 0; index < eventCount; index++) {
                int offset = index * X11InputSink.TOUCH_EVENT_STRIDE;
                sendTouchEvent(
                        packedEvents[offset + X11InputSink.TOUCH_ACTION_OFFSET],
                        packedEvents[offset + X11InputSink.TOUCH_SLOT_OFFSET],
                        packedEvents[offset + X11InputSink.TOUCH_X_OFFSET],
                        packedEvents[offset + X11InputSink.TOUCH_Y_OFFSET]
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
            events.add("stylus");
        }
    }
}
