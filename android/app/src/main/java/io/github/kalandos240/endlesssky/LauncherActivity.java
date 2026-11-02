package io.github.kalandos240.endlesssky;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Startup and portable saves stay outside the native game session. */
public final class LauncherActivity extends Activity {
    private static final int EXPORT = 101;
    private static final int IMPORT = 102;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private TextView status;
    private ProgressBar progress;
    private Button play, export, restore, undo;
    private SaveBackup backups;
    private boolean pickerInFlight;
    private boolean confirmation;
    private boolean closed;
    private volatile boolean hasPilots;
    private volatile boolean canUndo;
    private int lastProgress = -1;

    private interface Work { void run() throws IOException; }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (GameActivity.isRunning()) {
            startActivity(new Intent(this, GameActivity.class));
            finish();
            return;
        }
        backups = new SaveBackup(getFilesDir());
        pickerInFlight = state != null && state.getBoolean("pickerInFlight");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(16);
        layout.addView(status, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        layout.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        play = button(layout, R.string.play_game, view -> install());
        export = button(layout, R.string.export_pilots, view -> chooseFile(EXPORT));
        restore = button(layout, R.string.import_pilots, view -> chooseFile(IMPORT));
        undo = button(layout, R.string.undo_restore, view -> prepareUndo());
        button(layout, R.string.about_app, view -> showAbout());
        scroll.addView(layout);
        setContentView(scroll);
        InterstitialController.showPrivacyIfNeeded(this);
        submit(R.string.preparing_saves, () -> {
            backups.recover();
            try { Files.deleteIfExists(new File(getCacheDir(), "pilot-export.zip").toPath()); }
            catch (IOException | SecurityException cleanupError) { Log.w("EndlessSky", "Legacy export cache retained", cleanupError); }
            try (java.nio.file.DirectoryStream<java.nio.file.Path> leftovers = Files.newDirectoryStream(
                    getCacheDir().toPath(), "pilot-export-*.zip")) {
                for (java.nio.file.Path leftover : leftovers) { Files.deleteIfExists(leftover); }
            } catch (IOException | SecurityException cleanupError) {
                Log.w("EndlessSky", "Export cache cleanup deferred", cleanupError);
            }
        }, R.string.backup_intro);
    }

    private void showAbout() {
        String notice = getString(R.string.ads_notice);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_details, BuildConfig.VERSION_NAME) + "\n\n" + notice)
                .setPositiveButton(android.R.string.ok, null);
        if (!notice.isEmpty()) {
            dialog.setNeutralButton(R.string.ads_privacy_title,
                    (ignored, which) -> InterstitialController.showPrivacy(this));
        }
        dialog.show();
    }

    private Button button(LinearLayout parent, int label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private boolean available() {
        return !closed && !isFinishing() && !isDestroyed() && pending.get() == 0 && !pickerInFlight && !confirmation && !GameActivity.isRunning();
    }

    private void controls() {
        boolean enabled = available();
        play.setEnabled(enabled);
        export.setEnabled(enabled && hasPilots);
        restore.setEnabled(enabled);
        undo.setEnabled(enabled && canUndo);
        undo.setVisibility(canUndo ? View.VISIBLE : View.GONE);
        progress.setVisibility(pending.get() != 0 ? View.VISIBLE : View.GONE);
    }

    private void submit(int message, Work work, int success) {
        submit(message, work, success, null);
    }

    private void submit(int message, Work work, int success, Runnable afterSuccess) {
        if (closed || isFinishing() || isDestroyed()) { return; }
        pending.incrementAndGet();
        status.setText(message);
        progress.setIndeterminate(true);
        controls();
        worker.execute(() -> {
            Throwable failure = null;
            try {
                if (GameActivity.isRunning()) { throw new IOException("Close the game before managing saves"); }
                work.run();
            } catch (IOException | RuntimeException error) {
                failure = error;
                Log.e("EndlessSky", "Startup/save operation failed", error);
            }
            if (failure != null && !GameActivity.isRunning()) {
                try { backups.recover(); }
                catch (IOException | RuntimeException recoveryError) {
                    Log.e("EndlessSky", "Save recovery deferred to next startup", recoveryError);
                }
            }
            try {
                hasPilots = backups.hasPilots();
                canUndo = backups.canUndo();
            } catch (IOException metadataError) {
                Log.w("EndlessSky", "Save metadata unavailable", metadataError);
            }
            Throwable result = failure;
            pending.decrementAndGet();
            runOnUiThread(() -> {
                if (closed || isFinishing() || isDestroyed() || pending.get() != 0) { return; }
                status.setText(result == null ? success : (result instanceof ResourceInstaller.NotEnoughSpaceException
                        ? R.string.install_no_space : (message == R.string.installing_resources
                                ? R.string.install_failed : R.string.backup_failed)));
                controls();
                if (result == null && afterSuccess != null) { afterSuccess.run(); }
            });
        });
    }

    private void install() {
        if (!available()) { return; }
        progress.setProgress(0);
        lastProgress = -1;
        submit(R.string.installing_resources, () -> {
            ResourceInstaller.Assets assets = name -> getApplicationContext().getAssets().open(name);
            new ResourceInstaller(getFilesDir()).ensureInstalled(assets, (done, total) -> {
                int value = total == 0 ? 1000 : (int) (1000.0 * done / total);
                if (value != lastProgress) {
                    lastProgress = value;
                    runOnUiThread(() -> {
                        progress.setIndeterminate(false);
                        progress.setProgress(value);
                    });
                }
            });
        }, R.string.backup_intro, () -> {
            startActivity(new Intent(this, GameActivity.class));
            finish();
        });
    }

    private void chooseFile(int request) {
        if (!available()) { return; }
        Intent intent = new Intent(request == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (request == EXPORT) {
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "endless-sky-pilots-"
                    + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date()) + ".zip");
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        }
        pickerInFlight = true;
        controls();
        try {
            startActivityForResult(intent, request);
        } catch (RuntimeException error) {
            pickerInFlight = false;
            status.setText(R.string.file_picker_unavailable);
            controls();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != EXPORT && request != IMPORT) || backups == null || isFinishing()) { return; }
        pickerInFlight = false;
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) {
            status.setText(R.string.backup_intro);
            controls();
            return;
        }
        // SAF uses content URIs. A file:// result must never redirect export into private app data.
        if (!"content".equals(uri.getScheme())) {
            status.setText(R.string.backup_failed);
            controls();
            return;
        }
        if (request == EXPORT) {
            submit(R.string.exporting_pilots, () -> {
                File temporary = File.createTempFile("pilot-export-", ".zip", getCacheDir());
                try {
                    try (OutputStream output = Files.newOutputStream(temporary.toPath())) {
                        backups.exportArchive(output);
                    }
                    try (InputStream input = Files.newInputStream(temporary.toPath());
                            OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) { throw new IOException("Document provider did not open output"); }
                        byte[] buffer = new byte[128 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) { throw new IOException("Export cancelled"); }
                            output.write(buffer, 0, count);
                        }
                    }
                } finally {
                    Files.deleteIfExists(temporary.toPath());
                }
            }, R.string.export_complete);
        } else {
            SaveBackup.PreparedRestore[] candidate = new SaveBackup.PreparedRestore[1];
            submit(R.string.checking_backup, () -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) { throw new IOException("Document provider did not open backup"); }
                    candidate[0] = backups.prepareRestore(input);
                }
            }, R.string.confirm_backup, () -> confirm(candidate[0], false));
        }
    }

    private void prepareUndo() {
        if (!available()) { return; }
        SaveBackup.PreparedRestore[] candidate = new SaveBackup.PreparedRestore[1];
        submit(R.string.checking_backup, () -> candidate[0] = backups.prepareUndo(),
                R.string.confirm_backup, () -> confirm(candidate[0], true));
    }

    private void confirm(SaveBackup.PreparedRestore prepared, boolean undoing) {
        if (isFinishing() || isDestroyed()) { return; }
        confirmation = true;
        controls();
        String message = undoing ? getString(R.string.undo_confirm)
                : getString(R.string.restore_confirm, prepared.pilotCount, prepared.fileCount);
        new AlertDialog.Builder(this)
                .setTitle(undoing ? R.string.undo_restore : R.string.import_pilots)
                .setMessage(message)
                .setPositiveButton(R.string.restore_now, (dialog, which) -> {
                    confirmation = false;
                    submit(R.string.restoring_pilots, () -> backups.commit(prepared), R.string.restore_complete);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> cancelRestore())
                .setOnCancelListener(dialog -> cancelRestore())
                .show();
    }

    private void cancelRestore() {
        confirmation = false;
        submit(R.string.preparing_saves, () -> backups.cancelPrepared(), R.string.backup_intro);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pickerInFlight", pickerInFlight);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        closed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
