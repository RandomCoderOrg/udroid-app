package org.randomcoder.udroid.x11;

import static org.junit.Assert.assertEquals;

import com.termux.x11.input.InputStub;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class TrackpadGestureControllerTest {
    private RecordingInputStub recording;
    private TrackpadGestureController controller;

    @Before
    public void setUp() {
        recording = new RecordingInputStub();
        controller =
                new TrackpadGestureController(
                        new StatefulX11InputSink(recording),
                        10,
                        40,
                        300,
                        500
                );
    }

    @Test
    public void oneFingerTapEmitsLeftClick() {
        controller.handleDown(0, 0, 100, 200);
        controller.handleUp(100, 0, 100, 200, 0);

        assertEquals(
                List.of(
                        "mouse 0.0 0.0 1 true true",
                        "mouse 0.0 0.0 1 false true"
                ),
                recording.events
        );
    }

    @Test
    public void oneFingerMoveIsRelativeAndDoesNotClick() {
        controller.setSpeed(1.5f);
        controller.handleDown(0, 0, 100, 200);
        controller.handleMove(50, 1, 120, 190, 120, 190, 0, 500);
        controller.handleUp(80, 0, 120, 190, 500);

        assertEquals(
                List.of("mouse 30.0 -15.0 0 false true"),
                recording.events
        );
    }

    @Test
    public void twoFingerTapEmitsRightClick() {
        controller.handleDown(0, 2, 10, 10);
        controller.handlePointerDown(20, 19, 30, 10, 2, 20, 10, 20);
        controller.handlePointerUp(19, 1, 10, 10, 0, 0);
        controller.handleUp(70, 2, 10, 10, 0);

        assertEquals(
                List.of(
                        "mouse 0.0 0.0 3 true true",
                        "mouse 0.0 0.0 3 false true"
                ),
                recording.events
        );
    }

    @Test
    public void threeFingerTapEmitsMiddleClick() {
        controller.handleDown(0, 1, 10, 10);
        controller.handlePointerDown(10, 7, 30, 10, 2, 20, 10, 20);
        controller.handlePointerDown(20, 21, 20, 30, 3, 20, 16, 20);
        controller.handlePointerUp(21, 2, 20, 10, 20, 0);
        controller.handlePointerUp(7, 1, 10, 10, 0, 0);
        controller.handleUp(70, 1, 10, 10, 0);

        assertEquals(
                List.of(
                        "mouse 0.0 0.0 2 true true",
                        "mouse 0.0 0.0 2 false true"
                ),
                recording.events
        );
    }

    @Test
    public void parallelTwoFingerMoveEmitsWheelDistance() {
        controller.handleDown(0, 0, 10, 10);
        controller.handlePointerDown(10, 7, 30, 10, 2, 20, 10, 20);
        controller.handleMove(30, 2, 10, 30, 20, 30, 20, 400);
        controller.handlePointerUp(7, 1, 10, 30, 0, 400);
        controller.handleUp(50, 0, 10, 30, 400);

        assertEquals(List.of("wheel 0.0 -20.0"), recording.events);
    }

    @Test
    public void pinchDoesNotLeakScrollOrClickEvents() {
        controller.handleDown(0, 0, 10, 10);
        controller.handlePointerDown(10, 7, 30, 10, 2, 20, 10, 20);
        controller.handleMove(30, 2, 0, 10, 20, 10, 40, 100);
        controller.handlePointerUp(7, 1, 0, 10, 0, 100);
        controller.handleUp(50, 0, 0, 10, 100);

        assertEquals(List.of(), recording.events);
    }

    @Test
    public void doubleTapMoveDragsAndCancelReleasesButton() {
        controller.handleDown(0, 0, 100, 100);
        controller.handleUp(50, 0, 100, 100, 0);
        controller.handleDown(150, 0, 102, 100);
        controller.handleMove(180, 1, 120, 100, 120, 100, 0, 324);
        controller.cancel();

        assertEquals(
                List.of(
                        "mouse 0.0 0.0 1 true true",
                        "mouse 0.0 0.0 1 false true",
                        "mouse 0.0 0.0 1 true true",
                        "mouse 18.0 0.0 0 false true",
                        "mouse 0.0 0.0 1 false true"
                ),
                recording.events
        );
    }

    @Test
    public void longPressMoveDragsUntilFingerUp() {
        controller.handleDown(0, 0, 100, 100);
        controller.handleMove(550, 1, 101, 100, 101, 100, 0, 1);
        controller.handleUp(600, 0, 101, 100, 1);

        assertEquals(
                List.of(
                        "mouse 0.0 0.0 1 true true",
                        "mouse 1.0 0.0 0 false true",
                        "mouse 0.0 0.0 1 false true"
                ),
                recording.events
        );
    }

    @Test
    public void cancelIsIdempotent() {
        controller.handleDown(0, 0, 100, 100);
        controller.handleMove(550, 1, 101, 100, 101, 100, 0, 1);
        controller.cancel();
        int firstCancelCount = recording.events.size();
        controller.cancel();

        assertEquals(firstCancelCount, recording.events.size());
    }

    private static final class RecordingInputStub implements InputStub {
        private final List<String> events = new ArrayList<>();

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
            return true;
        }

        @Override
        public void sendTextEvent(byte[] utf8Bytes) {
        }

        @Override
        public void sendTouchEvent(int action, int pointerId, int x, int y) {
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
