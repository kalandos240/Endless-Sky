package io.github.kalandos240.endlesssky;

import android.content.Intent;
import android.os.Bundle;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class GameActivity extends SDLActivity {
    private File resources;
    private File config;
    private TouchControls controls;
    private static volatile GameActivity currentSession;

    static boolean isRunning() { return currentSession != null; }

    static native void nativeSetAction(int action, boolean pressed);
    static native void nativeResetTouch();
    static native boolean nativeIsInFlight();
    private static native void nativeBack();

    @Override
    protected void onCreate(Bundle state) {
        boolean recoveryNeeded = new SaveBackup(getFilesDir()).needsRecovery();
        if (!recoveryNeeded) try (InputStream input = getAssets().open("resources.manifest")) {
            ResourceInstaller.Manifest manifest = ResourceInstaller.Manifest.read(input);
            resources = new ResourceInstaller(getFilesDir()).installedDirectory(manifest);
            config = new File(getFilesDir(), "config");
            if (!config.isDirectory() && !config.mkdirs()) {
                throw new IOException("Cannot create save directory");
            }
        } catch (IOException error) {
            Log.e("EndlessSky", "Resources unavailable before SDL startup", error);
            resources = null;
        }
        // SDL starts its native main thread only after resume and surface readiness.
        super.onCreate(state);
        if (resources == null) {
            startActivity(new Intent(this, LauncherActivity.class).putExtra("startupError", !recoveryNeeded));
            finish();
        } else if (!mBrokenLibraries && mLayout != null) {
            currentSession = this;
            nativeResetTouch();
            controls = new TouchControls(this, mLayout);
        }
    }

    @Override
    protected void onPause() {
        if (controls != null) {
            controls.focus(false);
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controls != null) {
            controls.focus(hasWindowFocus());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        if (controls != null) {
            controls.focus(focused);
        }
        super.onWindowFocusChanged(focused);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !mBrokenLibraries
                && !isScreenKeyboardShown()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                nativeBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        if (controls != null) {
            controls.release();
        }
        super.onConfigurationChanged(configuration);
    }

    @Override
    public void onBackPressed() {
        if (!mBrokenLibraries) {
            nativeBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (controls != null) {
            controls.dispose();
            controls = null;
        }
        try {
            super.onDestroy();
        } finally {
            if (currentSession == this) {
                currentSession = null;
                // SDL has joined the native main thread and flushed game preferences above.
                // Endless Sky retains static universe/profile caches; start the next session in a fresh process.
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "SDL2", "openal", "main"};
    }

    @Override
    protected String[] getArguments() {
        if (resources == null || config == null) {
            throw new IllegalStateException("Game resources are not installed");
        }
        return new String[] {"--resources", resources.getAbsolutePath(),
                "--config", config.getAbsolutePath()};
    }
}
