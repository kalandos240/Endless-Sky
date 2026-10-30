package io.github.kalandos240.endlesssky;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable save snapshots. Call only while the native game is closed. */
public final class SaveBackup {
    private static final Object PROCESS_LOCK = new Object();
    private static final long MAX_FILE = 64L * 1024 * 1024;
    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_FILES = 10000;
    private static final String MANIFEST = "backup.manifest";
    private static final Set<String> SETTINGS = new HashSet<>(Arrays.asList(
            "preferences.txt", "keys.txt", "global conditions.txt", "global statistics.txt"));
    private final Path files;
    private final Path config;
    private final Path staging;
    private final Path previous;
    private final Path discard;
    private final Path ready;

    public static final class PreparedRestore {
        public final int fileCount;
        public final int pilotCount;
        private final String token;
        private PreparedRestore(int fileCount, int pilotCount, String token) {
            this.fileCount = fileCount;
            this.pilotCount = pilotCount;
            this.token = token;
        }
    }

    private static final class Entry {
        final long size;
        final String hash;
        Entry(long size, String hash) { this.size = size; this.hash = hash; }
    }

    private interface Operation<T> { T run() throws IOException; }

    public SaveBackup(File filesDirectory) {
        files = filesDirectory.toPath();
        config = files.resolve("config");
        staging = files.resolve(".restore-staging");
        previous = files.resolve("config-before-restore");
        discard = files.resolve(".restore-discard");
        ready = files.resolve(".restore-ready");
    }

    private <T> T locked(Operation<T> operation) throws IOException {
        synchronized (PROCESS_LOCK) {
            interrupted();
            Files.createDirectories(files);
            try (RandomAccessFile file = new RandomAccessFile(files.resolve(".save-backup.lock").toFile(), "rw");
                    FileLock lock = file.getChannel().lock()) {
                if (!lock.isValid()) { throw new IOException("Save backup lock unavailable"); }
                return operation.run();
            }
        }
    }

    public boolean needsRecovery() {
        return exists(staging) || exists(ready) || exists(discard) || (!exists(config) && exists(previous));
    }

    /** Recover a rename interrupted by process death before cleaning abandoned staging data. */
    public void recover() throws IOException {
        locked(() -> {
            recoverMissingConfig();
            delete(staging);
            Files.deleteIfExists(ready);
            delete(discard);
            return null;
        });
    }

    public boolean canUndo() {
        return Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS);
    }

    public boolean hasPilots() throws IOException {
        if (!exists(config)) { return false; }
        realDirectory(config);
        for (String name : Arrays.asList("saves", "pilots")) {
            Path directory = config.resolve(name);
            if (!exists(directory)) { continue; }
            realDirectory(directory);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.txt")) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) { return true; }
                }
            }
        }
        return false;
    }

    public void exportArchive(OutputStream output) throws IOException {
        locked(() -> {
            realDirectory(config);
            Map<String, Entry> entries = collect();
            boolean hasPilot = entries.keySet().stream().anyMatch(SaveBackup::pilotFile);
            if (!hasPilot) { throw new IOException("No saved pilots to export"); }
            byte[] body = manifestBody(entries);
            long total = entries.values().stream().mapToLong(entry -> entry.size).sum();
            String header = "ESSAVE1\t" + hex(sha().digest(body)) + "\t" + total + "\n";
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(header.getBytes(StandardCharsets.UTF_8));
                zip.write(body);
                zip.closeEntry();
                for (Map.Entry<String, Entry> item : entries.entrySet()) {
                    Path source = config.resolve(item.getKey());
                    ZipEntry entry = new ZipEntry(item.getKey());
                    entry.setLastModifiedTime(Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS));
                    zip.putNextEntry(entry);
                    try (InputStream input = Files.newInputStream(source)) {
                        copyChecked(input, zip, item.getValue());
                    }
                    zip.closeEntry();
                }
            }
            return null;
        });
    }

    /** Fully validate/extract without changing any live saves; caller can now ask for confirmation. */
    public PreparedRestore prepareRestore(InputStream input) throws IOException {
        return locked(() -> {
            recoverMissingConfig();
            clearPreparation();
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry first = zip.getNextEntry();
                if (first == null || !first.getName().equals(MANIFEST)) {
                    throw new IOException("Not an Endless Sky save backup");
                }
                Map<String, Entry> entries = parseManifest(readLimited(zip, 4 * 1024 * 1024));
                zip.closeEntry();
                cloneConfig(config, staging);
                // The archive is a complete snapshot of these paths, not a partial merge.
                delete(staging.resolve("saves"));
                delete(staging.resolve("pilots"));
                for (String setting : SETTINGS) { Files.deleteIfExists(staging.resolve(setting)); }
                Files.deleteIfExists(staging.resolve("recent.txt")); // Absolute paths are not portable.
                Set<String> remaining = new HashSet<>(entries.keySet());
                ZipEntry item;
                while ((item = zip.getNextEntry()) != null) {
                    Entry expected = entries.get(item.getName());
                    if (item.isDirectory() || expected == null || !remaining.remove(item.getName())) {
                        throw new IOException("Unexpected or duplicate backup entry");
                    }
                    Path destination = staging.resolve(item.getName());
                    Files.createDirectories(destination.getParent());
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(destination.toFile())) {
                        copyChecked(zip, output, expected);
                        output.getFD().sync();
                    }
                    if (item.getTime() > 0) {
                        Files.setLastModifiedTime(destination, FileTime.fromMillis(item.getTime()));
                    }
                    zip.closeEntry();
                }
                if (!remaining.isEmpty()) { throw new IOException("Files missing from backup"); }
                return markReady(entries);
            } catch (IOException | RuntimeException error) {
                clearPreparation();
                throw error;
            }
        });
    }

    public PreparedRestore prepareUndo() throws IOException {
        return locked(() -> {
            recoverMissingConfig();
            if (!canUndo()) { throw new IOException("No previous save snapshot"); }
            clearPreparation();
            try {
                cloneConfig(previous, staging);
                Files.deleteIfExists(staging.resolve("recent.txt"));
                return markReady(new LinkedHashMap<>());
            } catch (IOException | RuntimeException error) {
                clearPreparation();
                throw error;
            }
        });
    }

    public void cancelPrepared() throws IOException {
        locked(() -> { clearPreparation(); return null; });
    }

    public void commit(PreparedRestore prepared) throws IOException {
        locked(() -> {
            realDirectory(staging);
            if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Restore preparation is no longer valid");
            }
            try (InputStream marker = Files.newInputStream(ready)) {
                if (!new String(readLimited(marker, 128), StandardCharsets.US_ASCII).equals(prepared.token)) {
                    throw new IOException("Restore preparation is no longer valid");
                }
            }
            interrupted();
            // Retire an older rollback by renaming it before deletion; a partial delete is never offered as Undo.
            delete(discard);
            if (exists(previous)) { Files.move(previous, discard); }
            delete(discard);
            if (exists(config)) {
                realDirectory(config);
                Files.move(config, previous);
            }
            try {
                Files.move(staging, config);
            } catch (IOException error) {
                if (!exists(config) && exists(previous)) { Files.move(previous, config); }
                throw error;
            }
            Files.deleteIfExists(ready);
            return null;
        });
    }

    private PreparedRestore markReady(Map<String, Entry> entries) throws IOException {
        String token = UUID.randomUUID().toString();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(ready.toFile())) {
            output.write(token.getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        int pilots = (int) entries.keySet().stream().filter(name -> name.startsWith("pilots/")).count();
        int saves = (int) entries.keySet().stream().filter(name -> name.startsWith("saves/")).count();
        return new PreparedRestore(entries.size(), pilots == 0 ? saves : pilots, token);
    }

    private Map<String, Entry> collect() throws IOException {
        List<String> paths = new ArrayList<>();
        for (String folder : Arrays.asList("saves", "pilots")) {
            Path directory = config.resolve(folder);
            if (!exists(directory)) { continue; }
            realDirectory(directory);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory, "*.txt")) {
                for (Path child : children) {
                    if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Save files must not be links or directories");
                    }
                    paths.add(folder + "/" + child.getFileName());
                }
            }
        }
        for (String setting : SETTINGS) {
            if (exists(config.resolve(setting))) { paths.add(setting); }
        }
        paths.sort(String::compareTo);
        Map<String, Entry> entries = new LinkedHashMap<>();
        long total = 0;
        for (String name : paths) {
            Path path = config.resolve(name);
            if (!validPath(name) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsupported save filename or type");
            }
            long size = Files.size(path);
            if (size > MAX_FILE || size > MAX_TOTAL - total || entries.size() >= MAX_FILES) {
                throw new IOException("Backup exceeds supported size");
            }
            total += size;
            MessageDigest hash = sha();
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[128 * 1024];
                long read = 0;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    interrupted();
                    read += count;
                    if (read > size) { throw new IOException("Save changed during export"); }
                    hash.update(buffer, 0, count);
                }
                if (read != size) { throw new IOException("Save changed during export"); }
            }
            entries.put(name, new Entry(size, hex(hash.digest())));
        }
        return entries;
    }

    private static byte[] manifestBody(Map<String, Entry> entries) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            body.append(item.getValue().hash).append('\t').append(item.getValue().size)
                    .append('\t').append(item.getKey()).append('\n');
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Entry> parseManifest(byte[] data) throws IOException {
        String text = new String(data, StandardCharsets.UTF_8);
        int newline = text.indexOf('\n');
        if (newline < 0 || !text.endsWith("\n")) { throw new IOException("Incomplete backup manifest"); }
        String[] header = text.substring(0, newline).split("\t", -1);
        byte[] body = text.substring(newline + 1).getBytes(StandardCharsets.UTF_8);
        if (header.length != 3 || !header[0].equals("ESSAVE1")
                || !header[1].equals(hex(sha().digest(body)))) {
            throw new IOException("Invalid backup manifest");
        }
        long declared = size(header[2]);
        long total = 0;
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String line : text.substring(newline + 1).split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 3 || !parts[0].matches("[a-f0-9]{64}") || !validPath(parts[2])) {
                throw new IOException("Invalid backup path or digest");
            }
            long length = size(parts[1]);
            if (length > MAX_FILE || length > MAX_TOTAL - total || entries.size() >= MAX_FILES
                    || entries.put(parts[2], new Entry(length, parts[0])) != null) {
                throw new IOException("Duplicate or oversized backup entry");
            }
            total += length;
        }
        if (total != declared || entries.keySet().stream().noneMatch(SaveBackup::pilotFile)) {
            throw new IOException("Incomplete save backup");
        }
        return entries;
    }

    private static boolean pilotFile(String name) { return name.startsWith("saves/") || name.startsWith("pilots/"); }

    private static boolean validPath(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 32 || name.charAt(i) == '\\') { return false; }
        }
        if (SETTINGS.contains(name)) { return true; }
        String[] parts = name.split("/", -1);
        return parts.length == 2 && (parts[0].equals("saves") || parts[0].equals("pilots"))
                && !parts[1].isEmpty() && !parts[1].equals(".") && !parts[1].equals("..")
                && parts[1].endsWith(".txt");
    }

    private static long size(String value) throws IOException {
        try {
            long size = Long.parseLong(value);
            if (size < 0 || size > MAX_TOTAL) { throw new NumberFormatException(); }
            return size;
        } catch (NumberFormatException error) { throw new IOException("Invalid backup size", error); }
    }

    private void recoverMissingConfig() throws IOException {
        if (!exists(config) && exists(previous)) {
            realDirectory(previous);
            Files.move(previous, config);
        }
    }

    private void clearPreparation() throws IOException { delete(staging); Files.deleteIfExists(ready); }

    private static void cloneConfig(Path source, Path destination) throws IOException {
        Files.createDirectory(destination);
        if (!exists(source)) { return; }
        realDirectory(source);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                interrupted();
                if (!dir.equals(source)) { Files.createDirectory(destination.resolve(source.relativize(dir))); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                interrupted();
                if (!attrs.isRegularFile()) { throw new IOException("Config links are not supported"); }
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void realDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Not a real directory: " + path); }
    }

    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }

    private static void delete(Path root) throws IOException {
        if (!exists(root)) { return; }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) { throw error; }
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyChecked(InputStream input, OutputStream output, Entry expected) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        MessageDigest hash = sha();
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if (count > expected.size - total) { throw new IOException("Backup file exceeds declared size"); }
            total += count;
            hash.update(buffer, 0, count);
            output.write(buffer, 0, count);
        }
        if (total != expected.size || !hex(hash.digest()).equals(expected.hash)) {
            throw new IOException("Backup file integrity check failed");
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if (count > limit - output.size()) { throw new IOException("Backup metadata too large"); }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Backup operation cancelled"); }
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
