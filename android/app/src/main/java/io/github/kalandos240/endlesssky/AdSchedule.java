package io.github.kalandos240.endlesssky;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Provider-independent interstitial policy. All calls must use the same thread.
 * The Android adapter must supply SystemClock.elapsedRealtime and real lifecycle state.
 * This class does not load or display ads.
 */
final class AdSchedule {
    static final long INTERVAL_MS = 150_000;
    static final long RETRY_MS = 30_000;
    private final LongSupplier clock;
    private long lastTick;
    private long activeMs;
    private long retryMs;
    private long generation;
    private long pending;
    private boolean foreground;
    private boolean shown;

    AdSchedule(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        lastTick = clock.getAsLong();
    }

    void foreground(boolean value) {
        tick();
        foreground = value;
    }

    /** Reserve one opportunity, only at an explicit natural break in the game.
     * A false readiness/safety flag leaves the opportunity queued, without a backlog.
     * Returns zero when no ad may start. Call finished even if SDK.show throws.
     */
    long tryBegin(boolean safeBreak, boolean adReady) {
        tick();
        if (!foreground || pending != 0 || !safeBreak || !adReady
                || activeMs < INTERVAL_MS || retryMs != 0) {
            return 0;
        }
        pending = ++generation;
        shown = false;
        return pending;
    }

    void shown(long token) {
        tick();
        if (token != 0 && token == pending) {
            shown = true;
            activeMs = 0;
        }
    }

    /** Late callbacks from an old ad cannot finish a newer ad. */
    void finished(long token) {
        tick();
        if (token == 0 || token != pending) {
            return;
        }
        pending = 0;
        if (shown) {
            activeMs = 0;
        } else {
            retryMs = RETRY_MS;
        }
        shown = false;
    }

    private void tick() {
        long now = clock.getAsLong();
        long elapsed = Math.max(0, now - lastTick);
        lastTick = now;
        // Background time and time spent in an ad never advance the next opportunity.
        if (foreground && pending == 0) {
            activeMs += Math.min(elapsed, INTERVAL_MS - activeMs);
            retryMs -= Math.min(elapsed, retryMs);
        }
    }
}
