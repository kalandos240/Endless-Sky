package io.github.kalandos240.endlesssky;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class GameActivity extends SDLActivity {
    private File resources;
    private File config;

    @Override
    protected void onCreate(Bundle state) {
        try (InputStream input = getAssets().open("resources.manifest")) {
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
            startActivity(new Intent(this, LauncherActivity.class).putExtra("startupError", true));
            finish();
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
