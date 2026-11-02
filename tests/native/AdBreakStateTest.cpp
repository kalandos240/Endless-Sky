#include "AdBreakState.h"
#include <cassert>
#include <thread>

int main()
{
    using Place = AdBreakState::Place;
    AdBreakState state;
    assert(state.Token() == 0);
    state.Update(Place::MENU);
    auto menu = state.Token();
    assert(menu != 0 && (menu & 3) == 1);
    state.Update(Place::MENU);
    assert(state.Token() == menu);
    state.Update(Place::PLANET);
    auto planet = state.Token();
    assert(planet > menu && (planet & 3) == 2);
    state.Update(Place::NONE);
    assert(state.Token() == 0);
    state.Update(Place::PLANET);
    assert(state.Token() > planet);
    std::atomic<bool> finished{false};
    std::thread reader([&] {
        while(!finished.load()) {
            const auto token = state.Token();
            assert(token == 0 || (token & 3) == 1 || (token & 3) == 2);
        }
    });
    for(int index = 0; index != 10000; ++index) {
        state.Update(Place::NONE);
        state.Update(Place::MENU);
        state.Update(Place::PLANET);
    }
    finished.store(true);
    reader.join();
}
