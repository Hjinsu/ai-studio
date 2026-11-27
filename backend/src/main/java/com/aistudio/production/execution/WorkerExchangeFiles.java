package com.aistudio.production.execution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

import com.aistudio.production.contract.WorkerExchange.Identity;

/** File transport rooted in an existing, server-controlled and ACL-restricted directory. */
final class WorkerExchangeFiles {
    private static final int MAX_BYTES = 1024 * 1024;

    enum Kind {
        REQUEST("requestScript"), DRAFT("draftScript"), RESULT("resultScript");

        private final String directory;

        Kind(String directory) {
            this.directory = directory;
        }
    }

    private final Path root;

    WorkerExchangeFiles(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        verifyDirectory(this.root);
    }

    void publish(Kind kind, Identity identity, String json) throws IOException {
        byte[] bytes = Objects.requireNonNull(json).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Worker envelope exceeds size bounds");
        }

        Path target = path(kind, identity);
        Path stage = Files.createTempFile(target.getParent(), ".worker-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                file.force(true);
            }
            try {
                // As with WorkspaceMediaStore, link publication is atomic and never replaces a final file.
                Files.createLink(target, stage);
            } catch (FileAlreadyExistsException e) {
                if (!Arrays.equals(bytes, read(target))) {
                    throw new IOException("Conflicting worker envelope", e);
                }
            } catch (UnsupportedOperationException e) {
                throw new IOException("Worker volume lacks atomic no-replace publication", e);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    String read(Kind kind, Identity identity) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(read(path(kind, identity)))).toString();
    }

    private Path path(Kind kind, Identity identity) throws IOException {
        verifyDirectory(root);
        Path directory = root.resolve(kind.directory);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
            // Replaying a job does not recreate an exchange folder.
        }
        verifyDirectory(directory);
        return directory.resolve("%d-%d-%d.json".formatted(identity.jobId(), identity.attempt(), identity.epoch()));
    }

    private static void verifyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Worker directory is missing or redirected");
        }
    }

    private static byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Worker envelope is missing or redirected");
        }
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IOException("Worker envelope exceeds size bounds");
            }
            return bytes;
        }
    }
}
