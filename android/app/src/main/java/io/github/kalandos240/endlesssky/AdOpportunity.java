package io.github.kalandos240.endlesssky;

/** Consume a scene-entry opportunity once, after transient panels have settled. */
final class AdOpportunity {
    static final long SETTLE_MS = 750;
    private long candidate;
    private long since;
    private long delivered;

    long consume(long nativeToken, long now) {
        if (nativeToken == 0) {
            clear();
            return 0;
        }
        if (candidate != nativeToken) {
            candidate = nativeToken;
            since = now;
            return 0;
        }
        if (nativeToken == delivered || now - since < SETTLE_MS) return 0;
        delivered = nativeToken;
        return nativeToken;
    }

    void clear() {
        candidate = 0;
        // Retain delivered so returning from an ad/background cannot repeat this scene.
    }
}
