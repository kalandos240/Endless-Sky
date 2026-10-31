package io.github.kalandos240.endlesssky;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.yandex.mobile.ads.common.AdError;
import com.yandex.mobile.ads.common.AdRequest;
import com.yandex.mobile.ads.common.AdRequestError;
import com.yandex.mobile.ads.common.ImpressionData;
import com.yandex.mobile.ads.common.YandexAds;
import com.yandex.mobile.ads.interstitial.InterstitialAd;
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener;
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener;
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader;

/** Opt-in SDK integration. Debug always uses demo ads; release selects the configured unit. */
final class InterstitialController {
    private static final String TAG = "EndlessSkyAds";

    private final GameActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AdSchedule schedule = new AdSchedule(SystemClock::elapsedRealtime);
    private InterstitialAdLoader loader;
    private InterstitialAd ready;
    private InterstitialAd showing;
    private boolean initialized;
    private boolean loading;
    private boolean active;
    private boolean disposed;
    private long lastMenu;
    private long lastLoad = -30_000;
    private long loadedAt;
    private final Runnable poll = this::poll;

    InterstitialController(GameActivity activity) {
        this.activity = activity;
        try {
            // No assumed consent to personalization or location tracking.
            YandexAds.setUserConsent(false);
            YandexAds.setLocationTracking(false);
            YandexAds.initialize(activity.getApplicationContext(), () -> handler.post(() -> {
                if (disposed) return;
                initialized = true;
                loader = new InterstitialAdLoader(activity.getApplicationContext());
                if (active) load();
            }));
        } catch (RuntimeException error) {
            Log.w(TAG, "Ad initialization unavailable", error);
        }
    }

    void foreground(boolean value) {
        if (disposed) return;
        active = value;
        schedule.foreground(value);
        handler.removeCallbacks(poll);
        if (value) {
            load();
            handler.post(poll);
        }
    }

    private void load() {
        long now = SystemClock.elapsedRealtime();
        if (!initialized || !active || disposed || loading || ready != null || showing != null
                || now - lastLoad < 30_000) return;
        loading = true;
        lastLoad = now;
        try {
            loader.loadAd(new AdRequest.Builder(BuildConfig.YANDEX_AD_UNIT).build(), new InterstitialAdLoadListener() {
                @Override public void onAdLoaded(InterstitialAd ad) {
                    loading = false;
                    if (disposed) {
                        ad.setAdEventListener(null);
                        return;
                    }
                    ready = ad;
                    loadedAt = SystemClock.elapsedRealtime();
                    // Never show from a load callback: wait for the next menu transition.
                }
                @Override public void onAdFailedToLoad(AdRequestError error) {
                    loading = false;
                    Log.i(TAG, "Ad unavailable; gameplay continues");
                    // Retry only on a later menu entry/resume, with a cooldown.
                }
            });
        } catch (RuntimeException error) {
            loading = false;
            Log.w(TAG, "Ad load failed", error);
        }
    }

    private void poll() {
        if (!active || disposed) return;
        long menu = GameActivity.nativeAdBreakToken();
        boolean entered = menu != 0 && menu != lastMenu;
        if (menu != 0) lastMenu = menu;
        if (ready != null && SystemClock.elapsedRealtime() - loadedAt > 3_600_000) {
            ready.setAdEventListener(null);
            ready = null;
        }
        long token = schedule.tryBegin(entered, ready != null && showing == null);
        if (token != 0) {
            // Recheck that the native menu has not changed while polling.
            if (GameActivity.nativeAdBreakToken() == menu && !activity.isFinishing()) show(token);
            else schedule.finished(token);
        } else if (entered) {
            load();
        }
        if (active && !disposed) handler.postDelayed(poll, 250);
    }

    private void show(long token) {
        InterstitialAd ad = ready;
        ready = null;
        showing = ad;
        activity.prepareForAd();
        ad.setAdEventListener(new InterstitialAdEventListener() {
            @Override public void onAdShown() { if (!disposed) schedule.shown(token); }
            @Override public void onAdFailedToShow(AdError error) { finish(ad, token); }
            @Override public void onAdDismissed() { finish(ad, token); }
            @Override public void onAdClicked() { }
            @Override public void onAdImpression(ImpressionData data) { }
        });
        try {
            ad.show(activity);
        } catch (RuntimeException error) {
            Log.w(TAG, "Ad show failed", error);
            finish(ad, token);
        }
    }

    private void finish(InterstitialAd ad, long token) {
        ad.setAdEventListener(null);
        if (disposed || showing != ad) return;
        showing = null;
        schedule.finished(token);
        // SDL's Activity lifecycle handles pausing/resuming the game and audio.
        load();
    }

    void dispose() {
        if (disposed) return;
        schedule.foreground(false);
        disposed = true;
        active = false;
        handler.removeCallbacksAndMessages(null);
        if (loader != null) loader.cancelLoading();
        loader = null;
        if (ready != null) ready.setAdEventListener(null);
        if (showing != null) showing.setAdEventListener(null);
        ready = showing = null;
    }
}
