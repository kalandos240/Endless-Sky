package io.github.kalandos240.endlesssky;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps extraction and integrity checks off the UI and SDL threads. */
public final class LauncherActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private Button retry;
    private int lastProgress = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int padding = (int) (32 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(20);
        layout.addView(status, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        layout.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setOnClickListener(view -> install());
        layout.addView(retry);
        setContentView(layout);
        if (getIntent().getBooleanExtra("startupError", false)) {
            status.setText(R.string.install_failed);
            progress.setVisibility(View.GONE);
            retry.setVisibility(View.VISIBLE);
        } else {
            install();
        }
    }

    private void install() {
        status.setText(R.string.installing_resources);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        retry.setVisibility(View.GONE);
        lastProgress = -1;
        // Only application context is used for assets while the activity can be destroyed.
        ResourceInstaller.Assets assets = name -> getApplicationContext().getAssets().open(name);
        worker.execute(() -> {
            try {
                new ResourceInstaller(getFilesDir()).ensureInstalled(assets, (done, total) -> {
                    int value = total == 0 ? 1000 : (int) (1000.0 * done / total);
                    if (value != lastProgress) {
                        lastProgress = value;
                        runOnUiThread(() -> progress.setProgress(value));
                    }
                });
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        startActivity(new Intent(this, GameActivity.class));
                        finish();
                    }
                });
            } catch (IOException | RuntimeException error) {
                Log.e("EndlessSky", "Resource installation failed", error);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(error instanceof ResourceInstaller.NotEnoughSpaceException
                                ? R.string.install_no_space : R.string.install_failed);
                        progress.setVisibility(View.GONE);
                        retry.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
