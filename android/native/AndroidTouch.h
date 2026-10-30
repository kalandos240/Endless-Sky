#pragma once

class Command;

namespace AndroidTouch {
    // Private virtual key codes carry touch commands through the existing panel event path.
    // Real SDL keyboard state is left untouched, so physical keys remain independent.
    constexpr int KEY_BASE = -0x455300;
    Command ForKey(int keycode);
    Command HeldCommands();
    void PrepareFrame(bool inFlight);
}
