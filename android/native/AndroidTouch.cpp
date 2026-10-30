#include "AndroidTouch.h"
#include "TouchState.h"
#include "Command.h"
#include "SDL.h"

#include <atomic>
#include <jni.h>

namespace {
    TouchState state;
    std::atomic<bool> backRequested{false};

    // Protocol IDs match TouchActions.java. Do not reorder when adding actions.
    const Command &Action(int index)
    {
        switch(index)
        {
            case 0: return Command::FORWARD;
            case 1: return Command::LEFT;
            case 2: return Command::RIGHT;
            case 3: return Command::BACK;
            case 4: return Command::PRIMARY;
            case 5: return Command::SECONDARY;
            case 6: return Command::AFTERBURNER;
            case 7: return Command::SELECT;
            case 8: return Command::TARGET;
            case 9: return Command::NEAREST;
            case 10: return Command::LAND;
            case 11: return Command::JUMP;
            case 12: return Command::BOARD;
            case 13: return Command::HAIL;
            case 14: return Command::MAP;
            case 15: return Command::INFO;
            case 16: return Command::PAUSE;
            case 17: return Command::MENU;
            default: return Command::NONE;
        }
    }

    void KeyEvent(int keycode, bool down)
    {
        SDL_Event event{};
        event.type = down ? SDL_KEYDOWN : SDL_KEYUP;
        event.key.state = down ? SDL_PRESSED : SDL_RELEASED;
        event.key.keysym.sym = keycode;
        SDL_PushEvent(&event);
    }
}

Command AndroidTouch::ForKey(int keycode)
{
    if(keycode > KEY_BASE || keycode <= KEY_BASE - TouchState::ACTION_COUNT)
        return Command::NONE;
    return Action(KEY_BASE - keycode);
}

Command AndroidTouch::HeldCommands()
{
    Command result;
    uint32_t held = state.Held();
    for(int index = 0; index < TouchState::ACTION_COUNT; ++index)
        if(held & (uint32_t{1} << index))
            result |= Action(index);
    return result;
}

void AndroidTouch::PrepareFrame(bool inFlight)
{
    state.Enable(inFlight);
    auto changes = state.TakeChanges();
    for(int index = 0; index < TouchState::ACTION_COUNT; ++index)
    {
        uint32_t bit = uint32_t{1} << index;
        if(changes.down & bit)
            KeyEvent(KEY_BASE - index, true);
        if(changes.up & bit)
            KeyEvent(KEY_BASE - index, false);
    }
    if(backRequested.exchange(false))
    {
        int key = inFlight ? KEY_BASE - 17 : SDLK_ESCAPE;
        KeyEvent(key, true);
        KeyEvent(key, false);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kalandos240_endlesssky_GameActivity_nativeSetAction(JNIEnv *, jclass, jint action, jboolean pressed)
{
    state.Set(action, pressed);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kalandos240_endlesssky_GameActivity_nativeResetTouch(JNIEnv *, jclass)
{
    state.Reset();
    backRequested.store(false);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kalandos240_endlesssky_GameActivity_nativeIsInFlight(JNIEnv *, jclass)
{
    return state.Enabled();
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kalandos240_endlesssky_GameActivity_nativeBack(JNIEnv *, jclass)
{
    backRequested.store(true);
}
