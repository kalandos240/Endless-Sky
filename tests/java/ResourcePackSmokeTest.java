package io.github.kalandos240.endlesssky;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises the installer against the complete, real game resource package. */
public final class ResourcePackSmokeTest {
    public static void main(String[] args) throws Exception {
        Path assets = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Files.createDirectories(target);
        ResourceInstaller installer = new ResourceInstaller(target.toFile(), () -> Long.MAX_VALUE);
        File installed = installer.ensureInstalled(
                name -> new FileInputStream(assets.resolve(name).toFile()), (done, total) -> {});
        ResourceInstaller.Manifest manifest;
        try (InputStream input = new FileInputStream(assets.resolve("resources.manifest").toFile())) {
            manifest = ResourceInstaller.Manifest.read(input);
        }
        if (!installed.equals(installer.installedDirectory(manifest))) {
            throw new AssertionError("Full game resource installation is incomplete");
        }
        System.out.println("Full game pack: " + manifest.entries.size() + " files, "
                + manifest.totalBytes + " verified bytes, installed and reusable");
    }
}
