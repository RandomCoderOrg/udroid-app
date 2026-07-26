package org.randomcoder.udroid.x11;

import com.termux.x11.input.InputStub;

/**
 * Stateful boundary between Android input handling and Lorie's event transport.
 *
 * Implementations are called from the Android UI thread. Keeping the boundary
 * primitive-only avoids allocating objects on move-heavy input paths and makes
 * the emitted event stream straightforward to record in host-side tests.
 */
public interface X11InputSink extends InputStub {
    int XI_TOUCH_BEGIN = 18;
    int XI_TOUCH_UPDATE = 19;
    int XI_TOUCH_END = 20;
    int MAX_TOUCH_CONTACTS = 20;
    int TOUCH_EVENT_STRIDE = 4;
    int TOUCH_ACTION_OFFSET = 0;
    int TOUCH_SLOT_OFFSET = 1;
    int TOUCH_X_OFFSET = 2;
    int TOUCH_Y_OFFSET = 3;

    /**
     * Sends one Android touch frame as a single transport operation.
     *
     * Each event occupies four consecutive integers: XI2 action, guest slot,
     * x, and y. Begin and end events are never coalesced away; a move frame
     * normally contains one update for every active contact.
     */
    void sendTouchFrame(int[] events, int eventCount);

    /**
     * Releases every button and touch contact tracked by this sink.
     *
     * The operation must be idempotent so lifecycle callbacks can call it
     * defensively.
     */
    void releaseAllInput();

    /**
     * Optional fast transport implemented by the embedded Lorie client.
     *
     * StatefulX11InputSink retains an individual-event fallback so host-side
     * tests and upstream InputStub implementations remain usable.
     */
    interface TouchFrameTransport extends InputStub {
        void sendTouchFrame(int[] events, int eventCount);
    }
}
