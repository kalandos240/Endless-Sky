#pragma once
#include <atomic>
#include <cstdint>

// A single game-thread writer publishes stable, typed scene entries to the UI thread.
class AdBreakState {
public:
    enum class Place : uint64_t { NONE = 0, MENU = 1, PLANET = 2 };
    void Update(Place place)
    {
        const uint64_t kind = static_cast<uint64_t>(place);
        const uint64_t old = value.load();
        if(kind == 0)
            value.store(old & ~uint64_t{3});
        else if((old & 3) != kind)
            value.store((((old >> 2) + 1) << 2) | kind);
    }
    uint64_t Token() const
    {
        const uint64_t current = value.load();
        return (current & 3) ? current : 0;
    }
private:
    std::atomic<uint64_t> value{0};
};
