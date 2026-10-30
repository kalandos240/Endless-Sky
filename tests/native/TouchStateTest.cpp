#include "TouchState.h"

#include <cassert>
#include <iostream>
#include <thread>

int main()
{
    TouchState state;
    state.Set(0, true);
    assert(state.Held() == 0); // Loading screens cannot receive flight input.
    state.Enable(true);
    state.Set(0, true);
    state.Set(1, true);
    state.Set(4, true);
    assert(state.Held() == ((1u << 0) | (1u << 1) | (1u << 4)));
    auto changes = state.TakeChanges();
    assert(changes.down == state.Held() && changes.up == 0);
    state.Set(0, true);
    assert(state.TakeChanges().down == 0); // MOVE does not repeat command presses.
    state.Set(0, false);
    changes = state.TakeChanges();
    assert(changes.up == 1u);
    assert(state.Held() == ((1u << 1) | (1u << 4))); // Turn/fire continue after thrust is released.
    state.Reset();
    state.Set(10, true);
    state.Set(10, false); // A short tap between game frames still activates Land once.
    changes = state.TakeChanges();
    assert(changes.down == (1u << 10) && changes.up == (1u << 10));
    assert(state.Held() == (1u << 10));
    state.TakeChanges();
    assert(state.Held() == 0);
    state.Set(-1, true);
    state.Set(18, true);
    assert(state.Held() == 0);
    state.Set(4, true);
    state.Enable(false); // Opening a map/menu clears all holds and pending edges.
    state.Enable(true);
    assert(state.Held() == 0 && state.TakeChanges().down == 0);
    std::thread ui([&] {
        for(int i = 0; i < 10000; ++i) { state.Set(2, true); state.Set(2, false); }
    });
    for(int i = 0; i < 10000; ++i) { state.Enable(i % 2); state.TakeChanges(); }
    ui.join();
    state.Enable(false);
    state.Enable(true);
    assert(state.Held() == 0 && state.TakeChanges().down == 0);
    std::cout << "TouchState: simultaneous holds, short taps, menu transitions and thread stress passed\n";
}
