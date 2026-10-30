package io.github.kalandos240.endlesssky;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs immutable, content-addressed game resources without touching saves. */
public final class ResourceInstaller {
    public interface Assets {
        InputStream open(String name) throws IOException;
    }

    public interface Progress {
        void update(long completedBytes, long totalBytes);
    }

    public static final class NotEnoughSpaceException extends IOException {
        private static final long serialVersionUID = 1L;
        NotEnoughSpaceException() {
            super("Not enough free internal storage for game resources");
        }
    }

    private static final class Entry {
        final String hash;
        final long size;

        Entry(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }

    public static final class Manifest {
        final String version;
        final long totalBytes;
        final Map<String, Entry> entries;

        private Manifest(String version, long totalBytes, Map<String, Entry> entries) {
            this.version = version;
            this.totalBytes = totalBytes;
            this.entries = entries;
        }

        public static Manifest read(InputStream input) throws IOException {
            byte[] bytes = readLimited(input, 16 * 1024 * 1024);
            String text = new String(bytes, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            if (newline < 0 || !text.endsWith("\n")) {
                throw new IOException("Incomplete resource manifest");
            }
            String[] header = text.substring(0, newline).split("\t", -1);
            byte[] body = text.substring(newline + 1).getBytes(StandardCharsets.UTF_8);
            if (header.length != 3 || !header[0].equals("ESRES1")
                    || !header[1].matches("[a-f0-9]{64}")
                    || !header[1].equals(hex(sha256().digest(body)))) {
                throw new IOException("Invalid resource manifest digest or format");
            }
            long total = number(header[2]);
            Map<String, Entry> entries = new LinkedHashMap<>();
            long sum = 0;
            Set<String> roots = new HashSet<>();
            for (String line : text.substring(newline + 1).split("\n")) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 3 || !parts[0].matches("[a-f0-9]{64}")
                        || !validPath(parts[2])) {
                    throw new IOException("Invalid resource entry");
                }
                long size = number(parts[1]);
                if (entries.put(parts[2], new Entry(parts[0], size)) != null) {
                    throw new IOException("Duplicate resource entry");
                }
                if (size > total - sum) {
                    throw new IOException("Resource sizes exceed declared total");
                }
                sum += size;
                roots.add(parts[2].split("/", 2)[0]);
            }
            if (sum != total || entries.size() > 100000
                    || !roots.containsAll(new HashSet<>(Arrays.asList("data", "images", "shaders", "sounds",
                            "credits.txt", "keys.txt", "copyright", "license.txt", "changelog")))) {
                throw new IOException("Incomplete resource manifest");
            }
            return new Manifest(header[1], total, entries);
        }

        private static boolean validPath(String path) {
            if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.indexOf('\r') >= 0) {
                return false;
            }
            String[] parts = path.split("/", -1);
            for (String part : parts) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    return false;
                }
            }
            if (parts.length == 1) {
                return new HashSet<>(Arrays.asList("credits.txt", "keys.txt", "copyright", "license.txt", "changelog"))
                        .contains(parts[0]);
            }
            return new HashSet<>(Arrays.asList("data", "images", "shaders", "sounds")).contains(parts[0]);
        }

        private static long number(String text) throws IOException {
            try {
                long value = Long.parseLong(text);
                if (value < 0) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid resource size", exception);
            }
        }
    }

    private final File filesDirectory;
    private final LongSupplier freeBytes;

    public ResourceInstaller(File filesDirectory) {
        this(filesDirectory, filesDirectory::getUsableSpace);
    }

    ResourceInstaller(File filesDirectory, LongSupplier freeBytes) {
        this.filesDirectory = filesDirectory;
        this.freeBytes = freeBytes;
    }

    public File installedDirectory(Manifest manifest) throws IOException {
        File root = new File(filesDirectory, "resources");
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        File directory = new File(root, manifest.version);
        File marker = new File(directory, ".complete");
        if (!marker.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(marker)) {
            if (!new String(readLimited(input, 128), StandardCharsets.US_ASCII)
                    .equals(manifest.version)) {
                return null;
            }
        } catch (IOException invalidMarker) {
            return null;
        }
        for (Map.Entry<String, Entry> entry : manifest.entries.entrySet()) {
            File resource = new File(directory, entry.getKey());
            if (!resource.isFile() || resource.length() != entry.getValue().size) {
                return null;
            }
        }
        return directory;
    }

    public File ensureInstalled(Assets assets, Progress progress) throws IOException {
        Manifest manifest;
        try (InputStream input = assets.open("resources.manifest")) {
            manifest = Manifest.read(input);
        }
        Files.createDirectories(filesDirectory.toPath());
        try (RandomAccessFile lockFile = new RandomAccessFile(
                new File(filesDirectory, ".resources-install.lock"), "rw");
                FileLock lock = lockFile.getChannel().lock()) {
            if (!lock.isValid()) {
                throw new IOException("Cannot lock resource installation");
            }
            File installed = installedDirectory(manifest);
            if (installed != null) {
                pruneOldResources(manifest.version, true);
                progress.update(manifest.totalBytes, manifest.totalBytes);
                return installed;
            }
            File resources = new File(filesDirectory, "resources");
            Files.createDirectories(resources.toPath());
            if (!Files.isDirectory(resources.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Resource root must be a real directory");
            }
            File staging = new File(resources, ".installing-" + manifest.version);
            deleteTree(staging.toPath()); // Discard an interrupted extraction before the space check.
            pruneOldResources(manifest.version, false);
            long available = freeBytes.getAsLong();
            if (available < 32L * 1024 * 1024
                    || manifest.totalBytes > available - 32L * 1024 * 1024) {
                throw new NotEnoughSpaceException();
            }
            Files.createDirectory(staging.toPath());
            try {
                extract(assets, manifest, staging, progress);
                File marker = new File(staging, ".complete");
                try (FileOutputStream output = new FileOutputStream(marker)) {
                    output.write(manifest.version.getBytes(StandardCharsets.US_ASCII));
                    output.getFD().sync();
                }
                File destination = new File(resources, manifest.version);
                // A corrupt install may exist, but other versions and saves stay intact.
                deleteTree(destination.toPath());
                Files.move(staging.toPath(), destination.toPath());
                pruneOldResources(manifest.version, true);
                progress.update(manifest.totalBytes, manifest.totalBytes);
                return destination;
            } finally {
                deleteTree(staging.toPath());
            }
        }
    }

    // Only installer-owned names under resources/ are eligible. Cleanup never follows links.
    // Failed cleanup does not make a verified installation unusable; retry on the next launch.
    private void pruneOldResources(String currentVersion, boolean completeVersions) {
        Path root = new File(filesDirectory, "resources").toPath();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.equals(currentVersion) && ((completeVersions && name.matches("[a-f0-9]{64}"))
                        || name.matches("\\.installing-[a-f0-9]{64}"))) {
                    try {
                        deleteTree(path);
                    } catch (IOException | SecurityException error) {
                        System.err.println("Endless Sky resource cleanup deferred: " + error.getMessage());
                    }
                }
            }
        } catch (IOException | SecurityException error) {
            System.err.println("Endless Sky resource cleanup deferred: " + error.getMessage());
        }
    }

    private static void extract(Assets assets, Manifest manifest, File staging, Progress progress)
            throws IOException {
        Set<String> remaining = new HashSet<>(manifest.entries.keySet());
        long completed = 0;
        byte[] buffer = new byte[128 * 1024];
        progress.update(0, manifest.totalBytes);
        try (ZipInputStream archive = new ZipInputStream(assets.open("resources.zip"))) {
            ZipEntry item;
            while ((item = archive.getNextEntry()) != null) {
                String name = item.getName();
                Entry entry = manifest.entries.get(name);
                if (item.isDirectory() || entry == null || !remaining.remove(name)) {
                    throw new IOException("Unexpected or duplicate ZIP entry");
                }
                // Manifest validation rejects absolute paths, traversal, and marker collisions.
                File destination = new File(staging, name);
                Files.createDirectories(destination.getParentFile().toPath());
                MessageDigest digest = sha256();
                long written = 0;
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    int count;
                    while ((count = archive.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Resource installation interrupted");
                        }
                        if (count > entry.size - written) {
                            throw new IOException("Resource larger than declared size");
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        written += count;
                        completed += count;
                        progress.update(completed, manifest.totalBytes);
                    }
                    output.getFD().sync();
                }
                if (written != entry.size || !hex(digest.digest()).equals(entry.hash)) {
                    throw new IOException("Resource integrity check failed: " + name);
                }
                archive.closeEntry();
            }
        }
        if (!remaining.isEmpty()) {
            throw new IOException("Resources missing from ZIP");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) {
                throw new IOException("Resource metadata too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
