package io.github.kalandos240.endlesssky;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Individual root children allow Android to split touches between controls and SDL's surface. */
final class TouchControls {
    private final GameActivity activity;
    private final ViewGroup root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TouchInputState input = new TouchInputState(GameActivity::nativeSetAction);
    private final List<Button> buttons = new ArrayList<>();
    private final List<Runnable> pointerResets = new ArrayList<>();
    private final SharedPreferences preferences;
    private final Button toggle;
    private boolean flight;
    private boolean focused;
    private boolean shown;
    private boolean disposed;
    private final View.OnLayoutChangeListener layoutListener = (view, l, t, r, b, ol, ot, or, ob) -> position();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (disposed || !focused) {
                return;
            }
            boolean next = GameActivity.nativeIsInFlight();
            if (next != flight) {
                flight = next;
                updateVisibility();
            }
            handler.postDelayed(this, 150);
        }
    };

    TouchControls(GameActivity activity, ViewGroup root) {
        this.activity = activity;
        this.root = root;
        this.preferences = activity.getSharedPreferences("touch-controls", 0);
        shown = preferences.getBoolean("shown", true);
        focused = activity.hasWindowFocus();
        root.setMotionEventSplittingEnabled(true);
        add(R.string.touch_thrust, TouchActions.FORWARD);
        add(R.string.touch_left, TouchActions.LEFT);
        add(R.string.touch_right, TouchActions.RIGHT);
        add(R.string.touch_reverse, TouchActions.BACK);
        add(R.string.touch_fire, TouchActions.PRIMARY);
        add(R.string.touch_secondary, TouchActions.SECONDARY);
        add(R.string.touch_boost, TouchActions.AFTERBURNER);
        add(R.string.touch_weapon, TouchActions.SELECT);
        add(R.string.touch_target, TouchActions.TARGET);
        add(R.string.touch_enemy, TouchActions.NEAREST);
        add(R.string.touch_land, TouchActions.LAND);
        add(R.string.touch_jump, TouchActions.JUMP);
        add(R.string.touch_board, TouchActions.BOARD);
        add(R.string.touch_hail, TouchActions.HAIL);
        add(R.string.touch_map, TouchActions.MAP);
        add(R.string.touch_info, TouchActions.INFO);
        add(R.string.touch_pause, TouchActions.PAUSE);
        add(R.string.touch_menu, TouchActions.MENU);
        toggle = button(R.string.touch_hide);
        toggle.setOnClickListener(view -> {
            shown = !shown;
            preferences.edit().putBoolean("shown", shown).apply();
            updateVisibility();
        });
        root.addView(toggle, new RelativeLayout.LayoutParams(dp(48), dp(48)));
        root.addOnLayoutChangeListener(layoutListener);
        updateVisibility();
        root.post(this::position);
        handler.post(poll);
    }

    private Button button(int label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setContentDescription(activity.getString(label));
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setFocusable(false); // Do not steal hardware keyboard focus from SDL.
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x99404b60);
        background.setStroke(dp(1), 0xbba6bddd);
        background.setCornerRadius(dp(12));
        button.setBackground(background);
        return button;
    }

    private void add(int label, int action) {
        Button button = button(label);
        Set<Integer> ownedPointers = new HashSet<>();
        pointerResets.add(ownedPointers::clear);
        boolean[] touchClick = {false};
        button.setOnTouchListener((view, event) -> {
            if (!flight || !shown || !focused) {
                for (int pointer : ownedPointers) {
                    input.release(pointer);
                }
                ownedPointers.clear();
                return true;
            }
            int kind = event.getActionMasked();
            int index = event.getActionIndex();
            int pointer = event.getPointerId(index);
            if (kind == MotionEvent.ACTION_DOWN || kind == MotionEvent.ACTION_POINTER_DOWN) {
                ownedPointers.add(pointer);
                input.press(pointer, action);
            } else if (kind == MotionEvent.ACTION_UP || kind == MotionEvent.ACTION_POINTER_UP) {
                ownedPointers.remove(pointer);
                input.release(pointer);
                if (kind == MotionEvent.ACTION_UP) {
                    touchClick[0] = true;
                    view.performClick();
                    touchClick[0] = false;
                }
            } else if (kind == MotionEvent.ACTION_CANCEL) {
                for (int id : ownedPointers) {
                    input.release(id);
                }
                ownedPointers.clear();
            } else if (kind == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    if (ownedPointers.contains(id)) {
                        boolean inside = event.getX(i) >= 0 && event.getX(i) < view.getWidth()
                                && event.getY(i) >= 0 && event.getY(i) < view.getHeight();
                        if (inside) {
                            input.press(id, action);
                        } else {
                            input.release(id);
                        }
                    }
                }
            }
            view.setPressed(input.isHeld(action));
            view.setAlpha(input.isHeld(action) ? 1f : 0.8f);
            return true;
        });
        // Accessibility activation is a short tap through the same command path.
        button.setOnClickListener(view -> {
            if (!touchClick[0] && !input.isHeld(action) && flight && shown && focused) {
                GameActivity.nativeSetAction(action, true);
                GameActivity.nativeSetAction(action, false);
            }
        });
        buttons.add(button);
        root.addView(button, new RelativeLayout.LayoutParams(dp(48), dp(48)));
    }

    private void updateVisibility() {
        release();
        for (Button button : buttons) {
            button.setVisibility(flight && shown ? View.VISIBLE : View.GONE);
        }
        toggle.setText(shown ? R.string.touch_hide : R.string.touch_show);
        toggle.setVisibility(flight ? View.VISIBLE : View.GONE);
        position();
    }

    void focus(boolean value) {
        focused = value;
        handler.removeCallbacks(poll);
        if (!value) {
            release();
        } else if (!disposed) {
            handler.post(poll);
        }
    }

    void release() {
        input.releaseAll();
        for (Runnable reset : pointerResets) {
            reset.run();
        }
        GameActivity.nativeResetTouch();
        for (Button button : buttons) {
            button.setPressed(false);
            button.setAlpha(0.8f);
        }
    }

    void dispose() {
        disposed = true;
        release();
        handler.removeCallbacks(poll);
        root.removeOnLayoutChangeListener(layoutListener);
        for (Button button : buttons) {
            root.removeView(button);
        }
        root.removeView(toggle);
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void place(View view, int x, int y, int width, int height) {
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) view.getLayoutParams();
        if (params.leftMargin != x || params.topMargin != y || params.width != width || params.height != height) {
            params.leftMargin = x;
            params.topMargin = y;
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    private void position() {
        if (disposed || toggle == null || root.getWidth() == 0 || root.getHeight() == 0) {
            return;
        }
        int left = dp(8), top = dp(8), right = root.getWidth() - dp(8), bottom = root.getHeight() - dp(8);
        WindowInsets window = root.getRootWindowInsets();
        if (window != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets insets = window.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom;
            } else {
                left += window.getSystemWindowInsetLeft(); top += window.getSystemWindowInsetTop();
                right -= window.getSystemWindowInsetRight(); bottom -= window.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && window.getDisplayCutout() != null) {
                    left = Math.max(left, window.getDisplayCutout().getSafeInsetLeft() + dp(8));
                    right = Math.min(right, root.getWidth() - window.getDisplayCutout().getSafeInsetRight() - dp(8));
                }
            }
        }
        int unit = dp(48), gap = dp(4), step = unit + gap;
        int big = dp(60), combatStep = big + gap;
        place(buttons.get(0), left + step, bottom - 3 * step + gap, unit, unit);
        place(buttons.get(1), left, bottom - 2 * step + gap, unit, unit);
        place(buttons.get(2), left + 2 * step, bottom - 2 * step + gap, unit, unit);
        place(buttons.get(3), left + step, bottom - unit, unit, unit);
        place(buttons.get(4), right - big, bottom - big, big, big);
        place(buttons.get(5), right - big - combatStep, bottom - big, big, big);
        place(buttons.get(6), right - big, bottom - big - combatStep, big, big);
        place(buttons.get(7), right - big - combatStep, bottom - big - combatStep, big, big);
        int center = (left + right - 2 * unit - gap) / 2;
        int[] centerActions = {8, 9, 12, 13, 10, 11};
        for (int i = 0; i < centerActions.length; i++) {
            place(buttons.get(centerActions[i]), center + (i % 2) * step,
                    bottom - 3 * step + gap + (i / 2) * step, unit, unit);
        }
        int toolbar = (left + right - 4 * unit - 3 * gap) / 2;
        for (int i = 14; i < 18; i++) {
            place(buttons.get(i), toolbar + (i - 14) * step, top, unit, unit);
        }
        place(toggle, right - unit, top, unit, unit);
    }
}
