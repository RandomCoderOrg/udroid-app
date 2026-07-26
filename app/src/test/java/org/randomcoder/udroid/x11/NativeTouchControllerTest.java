package org.randomcoder.udroid.x11;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.x11.input.InputStub;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class NativeTouchControllerTest {
    private RecordingInputStub recording;
    private NativeTouchController controller;

    @Before
    public void setUp() {
        recording = new RecordingInputStub();
        controller =
                new NativeTouchController(
                        new StatefulX11InputSink(recording)
                );
    }

    @Test
    public void mapsSparseAndroidIdsToDenseStableSlots() {
        assertTrue(controller.handleDown(31, 10, 20));
        assertTrue(controller.handleDown(4, 30, 40));

        controller.beginMoveFrame();
        assertTrue(controller.handleMove(31, 11, 21));
        assertTrue(controller.handleMove(4, 31, 41));
        controller.endMoveFrame();

        assertTrue(controller.handleUp(31, 12, 22));
        assertTrue(controller.handleUp(4, 32, 42));

        assertEquals(
                List.of(
                        "touch 18 0 10 20",
                        "touch 18 1 30 40",
                        "touch 19 0 11 21",
                        "touch 19 1 31 41",
                        "touch 19 0 12 22",
                        "touch 20 0 12 22",
                        "touch 19 1 32 42",
                        "touch 20 1 32 42"
                ),
                recording.events
        );
        assertEquals(List.of(1, 1, 2, 2, 2), recording.touchFrameSizes);
        assertEquals(0, controller.activeCount());
    }

    @Test
    public void missingPointerInMoveFrameEndsOnlyThatContact() {
        controller.handleDown(2, 10, 10);
        controller.handleDown(9, 20, 20);

        controller.beginMoveFrame();
        controller.handleMove(9, 21, 22);
        controller.endMoveFrame();

        assertEquals(
                List.of(
                        "touch 18 0 10 10",
                        "touch 18 1 20 20",
                        "touch 19 1 21 22",
                        "touch 20 0 10 10"
                ),
                recording.events
        );
        assertEquals(-1, controller.slotForPointerId(2));
        assertEquals(1, controller.slotForPointerId(9));
    }

    @Test
    public void moveRecoversAContactWhoseDownWasMissing() {
        controller.beginMoveFrame();
        assertTrue(controller.handleMove(77, 100, 200));
        controller.endMoveFrame();

        assertEquals(List.of("touch 18 0 100 200"), recording.events);
        assertEquals(1, controller.activeCount());
    }

    @Test
    public void cancelEndsContactsOnceAndReusesFreedSlots() {
        controller.handleDown(100, 1, 2);
        controller.handleDown(200, 3, 4);
        controller.cancel();
        int eventCountAfterCancel = recording.events.size();
        controller.cancel();

        assertEquals(eventCountAfterCancel, recording.events.size());
        assertEquals(0, controller.activeCount());
        assertTrue(controller.handleDown(300, 5, 6));
        assertEquals(0, controller.slotForPointerId(300));
    }

    @Test
    public void rejectsInvalidIdsAndDropsOnlyContactsBeyondCapacity() {
        assertFalse(controller.handleDown(-1, 0, 0));
        for (int pointer = 0; pointer < X11InputSink.MAX_TOUCH_CONTACTS; pointer++) {
            assertTrue(controller.handleDown(1000 + pointer, pointer, pointer));
        }
        assertFalse(controller.handleDown(5000, 0, 0));
        assertEquals(X11InputSink.MAX_TOUCH_CONTACTS, controller.activeCount());

        assertTrue(controller.handleUp(1007, 7, 7));
        assertTrue(controller.handleDown(5000, 8, 8));
        assertEquals(7, controller.slotForPointerId(5000));
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
        }

        @Override
        public void sendMouseWheelEvent(float deltaX, float deltaY) {
        }

        @Override
        public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
            return true;
        }

        @Override
        public void sendTextEvent(byte[] utf8Bytes) {
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
        }
    }
}
