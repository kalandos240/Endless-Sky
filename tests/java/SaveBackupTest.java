package io.github.kalandos240.endlesssky;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SaveBackupTest {
    private static int checks;
    private interface Checked { void run() throws IOException; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) { throw new AssertionError(message); }
    }
    private static void fails(Checked action) throws IOException {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Invalid operation accepted");
    }
    private static void write(Path root, String path, String data) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, data);
    }
    private static String read(Path root, String path) throws IOException {
        return Files.readString(root.resolve(path));
    }
    private static Map<String, byte[]> unpack(byte[] archive) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) { files.put(entry.getName(), zip.readAllBytes()); }
        }
        return files;
    }
    private static byte[] pack(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static byte[] manifest(String body, long total) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        return ("ESSAVE1\t" + hash + "\t" + total + "\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        Path source = Files.createTempDirectory(root, "source-");
        write(source, "config/saves/Пилот Земля.txt", "pilot\n\tplanet Earth\n");
        write(source, "config/saves/Пилот Земля~~previous-1.txt", "older save");
        write(source, "config/pilots/Пилот Земля.txt", "playtime 1200");
        write(source, "config/keys.txt", "my key bindings");
        write(source, "config/global conditions.txt", "story progress");
        write(source, "config/recent.txt", "/data/user/0/old.package/files/config/saves/Пилот Земля.txt");
        write(source, "config/plugins/private/plugin.txt", "plugin assets");
        write(source, "config/errors.txt", "not for portable export");
        SaveBackup exporter = new SaveBackup(source.toFile());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.exportArchive(output);
        byte[] archive = output.toByteArray();
        Map<String, byte[]> entries = unpack(archive);
        check(entries.size() == 6, "Unexpected backup scope");
        check(!entries.containsKey("recent.txt") && !entries.containsKey("errors.txt")
                && !entries.containsKey("plugins/private/plugin.txt"), "Nonportable files leaked into backup");
        Path target = Files.createTempDirectory(root, "target-");
        write(target, "config/saves/current.txt", "current pilot");
        write(target, "config/preferences.txt", "current preferences");
        write(target, "config/plugins/keep.txt", "local plugin");
        write(target, "config/recent.txt", "old absolute path");
        write(target, "resources/do-not-touch.txt", "game resources");
        SaveBackup restorer = new SaveBackup(target.toFile());
        SaveBackup.PreparedRestore prepared = restorer.prepareRestore(new ByteArrayInputStream(archive));
        check(prepared.fileCount == 5 && prepared.pilotCount == 1, "Wrong confirmation summary");
        check(read(target, "config/saves/current.txt").equals("current pilot"), "Preparation changed live saves");
        restorer.cancelPrepared();
        check(!restorer.needsRecovery(), "Cancelled preparation left staging behind");
        check(read(target, "config/saves/current.txt").equals("current pilot"), "Cancel lost existing pilot");
        prepared = restorer.prepareRestore(new ByteArrayInputStream(archive));
        restorer.commit(prepared);
        check(read(target, "config/saves/Пилот Земля.txt").equals("pilot\n\tplanet Earth\n"), "Unicode save round trip failed");
        check(read(target, "config/saves/Пилот Земля~~previous-1.txt").equals("older save"), "Snapshots were lost");
        check(read(target, "config/keys.txt").equals("my key bindings"), "Settings not restored");
        check(!Files.exists(target.resolve("config/recent.txt")), "Nonportable recent path survived restoration");
        check(!Files.exists(target.resolve("config/preferences.txt")), "Missing settings should revert to game defaults");
        check(read(target, "config/plugins/keep.txt").equals("local plugin"), "Local plugins were removed");
        check(read(target, "config-before-restore/saves/current.txt").equals("current pilot"), "Rollback copy missing");
        check(read(target, "resources/do-not-touch.txt").equals("game resources"), "Restore touched game resources");
        check(restorer.canUndo() && !restorer.needsRecovery(), "Restore did not finalize cleanly");
        SaveBackup.PreparedRestore consumed = prepared;
        fails(() -> restorer.commit(consumed));
        restorer.commit(restorer.prepareUndo());
        check(read(target, "config/saves/current.txt").equals("current pilot"), "Undo failed");
        restorer.commit(restorer.prepareUndo());
        check(Files.exists(target.resolve("config/saves/Пилот Земля.txt")), "Undo of undo lost the imported pilot");

        for (String variant : new String[] {"corrupt", "missing", "extra", "traversal", "oversized"}) {
            Map<String, byte[]> modified = new LinkedHashMap<>(entries);
            if (variant.equals("corrupt")) { modified.put("saves/Пилот Земля.txt", new byte[] {1}); }
            if (variant.equals("missing")) { modified.remove("pilots/Пилот Земля.txt"); }
            if (variant.equals("extra")) { modified.put("../outside.txt", new byte[] {1}); }
            if (variant.equals("traversal")) {
                String body = "0".repeat(64) + "\t1\tsaves/../../outside.txt\n";
                modified.put("backup.manifest", manifest(body, 1));
            }
            if (variant.equals("oversized")) {
                long size = 65L * 1024 * 1024;
                modified.put("backup.manifest", manifest("0".repeat(64) + "\t" + size + "\tsaves/large.txt\n", size));
            }
            fails(() -> restorer.prepareRestore(new ByteArrayInputStream(pack(modified))));
            check(Files.exists(target.resolve("config/saves/Пилот Земля.txt")), variant + " lost current saves");
            check(!restorer.needsRecovery(), variant + " left an unfinished preparation");
            check(!Files.exists(root.resolve("outside.txt")), "Backup escaped its staging directory");
        }
        Path empty = Files.createTempDirectory(root, "empty-");
        SaveBackup fresh = new SaveBackup(empty.toFile());
        fresh.commit(fresh.prepareRestore(new ByteArrayInputStream(archive)));
        check(fresh.hasPilots() && !fresh.canUndo(), "First restoration requires no pre-existing config");
        try {
            Thread.currentThread().interrupt();
            fails(() -> fresh.prepareRestore(new ByteArrayInputStream(archive)));
        } finally { Thread.interrupted(); }
        check(fresh.hasPilots(), "Cancellation changed live files");

        // Simulate process death between renaming current config and publishing the staged replacement.
        Path crash = Files.createTempDirectory(root, "crash-");
        write(crash, "config/saves/survivor.txt", "original data");
        Files.move(crash.resolve("config"), crash.resolve("config-before-restore"));
        write(crash, ".restore-staging/saves/partial.txt", "unfinished import");
        write(crash, ".restore-ready", "stale token");
        write(crash, ".restore-discard/saves/obsolete.txt", "older rollback");
        SaveBackup recovery = new SaveBackup(crash.toFile());
        check(recovery.needsRecovery(), "Interrupted rename not detected");
        recovery.recover();
        check(read(crash, "config/saves/survivor.txt").equals("original data"), "Process recovery lost original config");
        check(!recovery.needsRecovery(), "Process recovery left transaction remnants");
        recovery.recover();
        check(recovery.hasPilots(), "Recovery is not repeatable");

        Path link = Files.createTempDirectory(root, "links-");
        write(link, "config/saves/real.txt", "safe");
        write(link, "outside.txt", "not a save");
        Files.createSymbolicLink(link.resolve("config/saves/link.txt"), link.resolve("outside.txt"));
        SaveBackup linked = new SaveBackup(link.toFile());
        fails(() -> linked.exportArchive(new ByteArrayOutputStream()));
        fails(() -> linked.prepareRestore(new ByteArrayInputStream(archive)));
        check(read(link, "outside.txt").equals("not a save"), "Restore followed a config link");
        System.out.println("SaveBackup: " + checks + " checks passed");
    }
}
