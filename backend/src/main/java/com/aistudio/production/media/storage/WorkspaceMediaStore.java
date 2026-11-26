package com.aistudio.production.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.aistudio.production.contract.MediaStore;

/** Files are kept below a deployment-owned root, with a unique directory per attempt. */
public final class WorkspaceMediaStore implements MediaStore {
    private static final Object COMMIT_LOCK = new Object();
    private final Path root;

    public WorkspaceMediaStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        requireDirectory(this.root);
    }

    @Override
    public FileIdentity stage(Attempt attempt, Role role, InputStream source, long byteLimit) throws IOException {
        if (byteLimit < 1) {
            throw new IllegalArgumentException("Invalid media byte limit");
        }
        Path staged = sibling(assetPath(attempt, role, true));
        MessageDigest hash = sha256();
        long count = 0;
        try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = source.read(buffer)) != -1;) {
                if (n > byteLimit - count) {
                    throw new IOException("Media asset exceeds its byte limit");
                }
                count += n;
                hash.update(buffer, 0, n);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            if (count == 0) {
                throw new IOException("Media asset is empty");
            }
            channel.force(true);
        }
        return new FileIdentity(count, HexFormat.of().formatHex(hash.digest()));
    }

    @Override
    public void commit(Attempt attempt, Role role, FileIdentity expected) throws IOException {
        synchronized (COMMIT_LOCK) {
            commitVerified(attempt, role, expected);
        }
    }

    private void commitVerified(Attempt attempt, Role role, FileIdentity expected) throws IOException {
        Path target = assetPath(attempt, role, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try (FileChannel ignored = verified(target, expected)) {
                return;
            }
        }
        Path staged = sibling(target);
        try (FileChannel ignored = verified(staged, expected)) {
            // Verify before publishing. Close the handle before moving for Windows.
        }
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public FileChannel openVerified(Attempt attempt, Role role, FileIdentity expected) throws IOException {
        return verified(assetPath(attempt, role, false), expected);
    }

    private Path assetPath(Attempt attempt, Role role, boolean createParents) throws IOException {
        Path relative = Path.of(attempt.relativePath(role));
        Path parent = root;
        requireDirectory(parent);
        for (Path part : relative.getParent()) {
            parent = parent.resolve(part);
            if (createParents) {
                try {
                    Files.createDirectory(parent);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // All traversed directories are validated below.
                }
            }
            requireDirectory(parent);
        }
        return parent.resolve(relative.getFileName());
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Unsafe media directory");
        }
    }

    private static Path sibling(Path target) {
        return target.resolveSibling(target.getFileName() + ".partial");
    }

    private static FileChannel verified(Path path, FileIdentity expected) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != expected.bytes() || !path.toRealPath().equals(path)) {
            throw new IOException("Invalid media file");
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        boolean valid = false;
        try {
            MessageDigest hash = sha256();
            ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
            long size = 0;
            for (int n; (n = channel.read(bytes)) != -1;) {
                size += n;
                if (size > expected.bytes()) {
                    throw new IOException("Media length changed");
                }
                bytes.flip();
                hash.update(bytes);
                bytes.clear();
            }
            if (size != expected.bytes() || !expected.sha256().equals(HexFormat.of().formatHex(hash.digest()))) {
                throw new IOException("Media integrity check failed");
            }
            channel.position(0);
            valid = true;
            return channel;
        } finally {
            if (!valid) {
                channel.close();
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
