package io.github.kalandos240.endlesssky;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ResourceInstallerTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static ResourceInstaller.Assets assets(Path fixtures, String variant) {
        return name -> new FileInputStream(fixtures.resolve(variant).resolve(name).toFile());
    }

    private static ResourceInstaller.Manifest manifest(ResourceInstaller.Assets assets) throws IOException {
        try (InputStream input = assets.open("resources.manifest")) {
            return ResourceInstaller.Manifest.read(input);
        }
    }

    private static void expectFailure(ResourceInstaller installer, ResourceInstaller.Assets assets)
            throws IOException {
        boolean failed = false;
        try {
            installer.ensureInstalled(assets, (done, total) -> {});
        } catch (IOException expected) {
            failed = true;
        }
        check(failed, "Invalid package accepted");
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        Path root = Files.createTempDirectory("endless-sky-installer-test");
        ResourceInstaller.Assets good = assets(fixtures, "good");
        ResourceInstaller installer = new ResourceInstaller(root.toFile(), () -> Long.MAX_VALUE);
        File installed = installer.ensureInstalled(good, (done, total) -> {
            check(done >= 0 && done <= total, "Invalid progress");
        });
        check(installed.isDirectory(), "Installation did not publish a directory");
        check(Files.readString(installed.toPath().resolve("data/sample.txt")).equals("galaxy"),
                "Extracted bytes differ");
        check(installed.equals(installer.installedDirectory(manifest(good))), "No ready installation");
        // A valid install must be reused without reopening or extracting the ZIP.
        File reused = installer.ensureInstalled(name -> {
            if (name.endsWith(".zip")) {
                throw new AssertionError("Archive unnecessarily reopened");
            }
            return good.open(name);
        }, (done, total) -> {});
        check(installed.equals(reused), "Installation was not reused");

        Path save = root.resolve("config/saves/pilot.txt");
        Files.createDirectories(save.getParent());
        Files.writeString(save, "pilot save");
        ResourceInstaller lowSpace = new ResourceInstaller(root.toFile(), () -> 1L);
        expectFailure(lowSpace, assets(fixtures, "updated"));
        check(installer.installedDirectory(manifest(good)) != null, "Failed update lost previous resources");
        check(Files.readString(save).equals("pilot save"), "Installer changed a save");
        File updated = installer.ensureInstalled(assets(fixtures, "updated"), (done, total) -> {});
        check(!updated.equals(installed), "Updated content reused the old directory");

        for (String variant : Arrays.asList("corrupt", "missing", "extra", "duplicate", "oversized", "traversal")) {
            Path directory = Files.createTempDirectory(root, "bad-");
            ResourceInstaller fresh = new ResourceInstaller(directory.toFile(), () -> Long.MAX_VALUE);
            expectFailure(fresh, assets(fixtures, variant));
            check(fresh.installedDirectory(manifest(good)) == null, variant + " published a ready marker");
            check(!Files.exists(directory.resolve("outside.txt")), "ZIP traversal escaped destination");
        }

        Path interrupted = Files.createTempDirectory(root, "interrupted-");
        ResourceInstaller recovering = new ResourceInstaller(interrupted.toFile(), () -> Long.MAX_VALUE);
        try {
            recovering.ensureInstalled(good, (done, total) -> {
                if (done > 0) {
                    Thread.currentThread().interrupt();
                }
            });
            throw new AssertionError("Installation ignored interruption");
        } catch (IOException expected) {
            check(true, "Interrupted");
        } finally {
            Thread.interrupted();
        }
        check(recovering.installedDirectory(manifest(good)) == null, "Partial installation marked ready");
        check(recovering.ensureInstalled(good, (done, total) -> {}).isDirectory(), "Retry failed");
        // A damaged ready marker must be repairable rather than blocking all retries.
        Files.writeString(updated.toPath().resolve(".complete"), "x".repeat(256));
        check(installer.installedDirectory(manifest(assets(fixtures, "updated"))) == null,
                "Damaged marker accepted");
        updated = installer.ensureInstalled(assets(fixtures, "updated"), (done, total) -> {});
        // Missing installed resources invalidate the cache and trigger a repair.
        Files.delete(updated.toPath().resolve("images/sample.txt"));
        check(installer.installedDirectory(manifest(assets(fixtures, "updated"))) == null,
                "Incomplete installation accepted");
        check(installer.ensureInstalled(assets(fixtures, "updated"), (done, total) -> {})
                .toPath().resolve("images/sample.txt").toFile().isFile(), "Repair failed");
        System.out.println("ResourceInstaller: " + checks + " checks passed");
    }
}
