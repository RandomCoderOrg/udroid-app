package org.randomcoder.udroid.x11;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;

final class MouseButtonState {
    enum Source { DIRECT, PHYSICAL, PALETTE, OVERLAY }

    private final EnumMap<Source, Set<Integer>> buttons = new EnumMap<>(Source.class);

    MouseButtonState() {
        for (Source source : Source.values()) buttons.put(source, new HashSet<>());
    }

    /** Returns the new effective state only when the X button state changed. */
    Boolean set(Source source, int button, boolean pressed) {
        boolean before = isDown(button);
        if (pressed) buttons.get(source).add(button);
        else buttons.get(source).remove(button);
        boolean after = isDown(button);
        return before == after ? null : after;
    }

    boolean isDown(int button) {
        for (Set<Integer> source : buttons.values()) {
            if (source.contains(button)) return true;
        }
        return false;
    }

    boolean hasExternalButton() {
        return !buttons.get(Source.PALETTE).isEmpty() ||
                !buttons.get(Source.OVERLAY).isEmpty();
    }

    Set<Integer> pressedBy(Source source) {
        return new HashSet<>(buttons.get(source));
    }

    void clear() {
        for (Set<Integer> source : buttons.values()) source.clear();
    }
}
