package io.github.kalandos240.endlesssky;

import java.util.ArrayList;
import java.util.List;

public final class TouchInputStateTest {
    private static void check(boolean value) {
        if (!value) {
            throw new AssertionError("Pointer ownership or release sequence is incorrect");
        }
    }

    public static void main(String[] args) {
        List<String> events = new ArrayList<>();
        TouchInputState state = new TouchInputState((action, down) -> events.add(action + ":" + down));
        state.press(101, TouchActions.FORWARD);
        state.press(202, TouchActions.LEFT);
        state.press(303, TouchActions.PRIMARY);
        check(state.isHeld(TouchActions.FORWARD) && state.isHeld(TouchActions.LEFT)
                && state.isHeld(TouchActions.PRIMARY));
        state.release(202);
        check(!state.isHeld(TouchActions.LEFT) && state.isHeld(TouchActions.PRIMARY));
        state.press(404, TouchActions.PRIMARY);
        int before = events.size();
        state.release(303);
        check(events.size() == before && state.isHeld(TouchActions.PRIMARY));
        state.press(404, TouchActions.PRIMARY);
        check(events.size() == before); // Same pointer MOVE must not generate another press.
        state.press(404, TouchActions.SECONDARY);
        check(!state.isHeld(TouchActions.PRIMARY) && state.isHeld(TouchActions.SECONDARY));
        state.releaseAll();
        check(!state.isHeld(TouchActions.SECONDARY) && !state.isHeld(TouchActions.FORWARD));
        before = events.size();
        state.release(404);
        state.releaseAll();
        check(events.size() == before); // CANCEL/UP after pause must be harmless.
        state.press(101, TouchActions.FORWARD); // Reused pointer ID after resume.
        check(events.get(events.size() - 1).equals("0:true"));
        state.releaseAll();
        try {
            state.press(0, TouchActions.COUNT);
            throw new AssertionError("Invalid action accepted");
        } catch (IllegalArgumentException expected) {
            check(true);
        }
        System.out.println("TouchInputState: multi-finger input, ownership, cancellation and resume passed");
    }
}
