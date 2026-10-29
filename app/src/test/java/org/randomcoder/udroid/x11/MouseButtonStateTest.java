package org.randomcoder.udroid.x11;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.randomcoder.udroid.x11.MouseButtonState.Source;

public class MouseButtonStateTest {
    @Test
    public void sameButtonAcrossPalettesReleasesOnlyAfterLastOwner() {
        MouseButtonState state = new MouseButtonState();
        assertEquals(Boolean.TRUE, state.set(Source.PALETTE, 1, true));
        assertNull(state.set(Source.OVERLAY, 1, true));
        assertNull(state.set(Source.PALETTE, 1, false));
        assertTrue(state.hasExternalButton());
        assertEquals(Boolean.FALSE, state.set(Source.OVERLAY, 1, false));
        assertFalse(state.hasExternalButton());
    }

    @Test
    public void directTouchAndOverlayCanShareLeftButton() {
        MouseButtonState state = new MouseButtonState();
        assertEquals(Boolean.TRUE, state.set(Source.DIRECT, 1, true));
        assertNull(state.set(Source.OVERLAY, 1, true));
        assertNull(state.set(Source.DIRECT, 1, false));
        assertEquals(Boolean.FALSE, state.set(Source.OVERLAY, 1, false));
    }

    @Test
    public void releaseAllClearsEverySource() {
        MouseButtonState state = new MouseButtonState();
        state.set(Source.PHYSICAL, 1, true);
        state.set(Source.OVERLAY, 3, true);
        assertEquals(1, state.pressedBy(Source.PHYSICAL).size());
        state.clear();
        assertFalse(state.isDown(1));
        assertFalse(state.isDown(3));
        assertFalse(state.hasExternalButton());
    }
}
