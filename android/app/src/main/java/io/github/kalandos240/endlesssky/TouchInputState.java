package io.github.kalandos240.endlesssky;

import java.util.HashMap;
import java.util.Map;

/** UI-thread pointer ownership; two fingers on one action must not release each other. */
public final class TouchInputState {
    public interface Sink {
        void setAction(int action, boolean pressed);
    }

    private final Sink sink;
    private final Map<Integer, Integer> pointers = new HashMap<>();
    private final int[] holders = new int[TouchActions.COUNT];

    public TouchInputState(Sink sink) {
        this.sink = sink;
    }

    public void press(int pointer, int action) {
        if (action < 0 || action >= holders.length) {
            throw new IllegalArgumentException("Unknown touch action");
        }
        Integer previous = pointers.get(pointer);
        if (previous != null && previous == action) {
            return;
        }
        release(pointer);
        pointers.put(pointer, action);
        if (holders[action]++ == 0) {
            sink.setAction(action, true);
        }
    }

    public void release(int pointer) {
        Integer action = pointers.remove(pointer);
        if (action != null && --holders[action] == 0) {
            sink.setAction(action, false);
        }
    }

    public boolean isHeld(int action) {
        return holders[action] != 0;
    }

    public void releaseAll() {
        for (int action = 0; action < holders.length; action++) {
            if (holders[action] != 0) {
                sink.setAction(action, false);
                holders[action] = 0;
            }
        }
        pointers.clear();
    }
}
