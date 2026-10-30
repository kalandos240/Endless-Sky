#pragma once

#include <cstdint>
#include <mutex>

// UI-thread input is consumed on the game thread. Short taps retain a press edge.
class TouchState {
public:
    static constexpr int ACTION_COUNT = 18;
    struct Changes { uint32_t down; uint32_t up; };

    void Set(int action, bool pressed)
    {
        std::lock_guard lock(mutex);
        if(action < 0 || action >= ACTION_COUNT || !enabled)
            return;
        uint32_t bit = uint32_t{1} << action;
        if(pressed)
        {
            if(!(held & bit))
                down |= bit;
            held |= bit;
        }
        else
        {
            if(held & bit)
                up |= bit;
            held &= ~bit;
        }
    }

    void Enable(bool value)
    {
        std::lock_guard lock(mutex);
        enabled = value;
        if(!value)
            held = down = up = pulse = 0;
    }

    void Reset()
    {
        std::lock_guard lock(mutex);
        held = down = up = pulse = 0;
    }

    uint32_t Held() const
    {
        std::lock_guard lock(mutex);
        return enabled ? (held | pulse) : 0;
    }

    Changes TakeChanges()
    {
        std::lock_guard lock(mutex);
        Changes result{down, up};
        pulse = down;
        down = up = 0;
        return result;
    }

    bool Enabled() const
    {
        std::lock_guard lock(mutex);
        return enabled;
    }

private:
    mutable std::mutex mutex;
    bool enabled = false;
    uint32_t held = 0, down = 0, up = 0, pulse = 0;
};
