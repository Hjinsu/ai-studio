package com.aistudio.production.media.product;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.contract.MediaProcessRunner.Command;
import com.aistudio.production.contract.MediaProcessRunner.Purpose;
import com.aistudio.production.contract.MediaProcessRunner.Result;
import com.aistudio.production.contract.MediaProcessRunner.Scratch;
import com.aistudio.production.contract.MediaProcessRunner.Tool;

/**
 * Thin product wrapper over the shared {@link MediaProcessRunner}: fixed ASCII aliases inside the server-owned
 * scratch directory, individual arguments only, and stable failure codes. Exit code 0 alone never adopts an output;
 * callers verify every produced file.
 */
final class MediaTools {
    private final MediaProcessRunner runner;
    private final Scratch scratch;
    private final BooleanSupplier cancelled;
    private final Path directory;

    private MediaTools(MediaProcessRunner runner, Scratch scratch, BooleanSupplier cancelled, Path directory) {
        this.runner = runner;
        this.scratch = scratch;
        this.cancelled = cancelled;
        this.directory = directory;
    }

    static MediaTools open(MediaProcessRunner runner, Scratch scratch, BooleanSupplier cancelled) {
        if (runner == null || !runner.ready()) {
            throw MediaFailure.retry("MEDIA_TOOLS_UNAVAILABLE", "Media process runner is not ready", null);
        }
        try {
            return new MediaTools(runner, scratch, cancelled, runner.prepare(scratch));
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_SCRATCH_UNAVAILABLE", "Scratch preparation failed", ex);
        }
    }

    Path directory() {
        return directory;
    }

    Path file(String alias) {
        if (!alias.matches("[a-z0-9][a-z0-9_.-]{0,40}(/[a-z0-9][a-z0-9_.-]{0,40})?")) {
            throw new IllegalArgumentException("Invalid scratch alias");
        }
        Path path = directory.resolve(alias).normalize();
        if (!path.startsWith(directory)) {
            throw new IllegalArgumentException("Scratch alias escapes the attempt");
        }
        return path;
    }

    void write(String alias, byte[] content) {
        try {
            Path path = file(alias);
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_SCRATCH_WRITE", "Scratch write failed", ex);
        }
    }

    void copy(String alias, FileChannel source) {
        try (source; InputStream in = Channels.newInputStream(source)) {
            Path path = file(alias);
            Files.createDirectories(path.getParent());
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_SCRATCH_WRITE", "Scratch copy failed", ex);
        }
    }

    byte[] read(String alias, long limit) {
        try {
            return FixtureCatalog.readBounded(file(alias), limit);
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_OUTPUT_MISSING", "Expected process output is missing", ex);
        }
    }

    boolean exists(String alias) {
        return Files.isRegularFile(file(alias), LinkOption.NOFOLLOW_LINKS);
    }

    void delete(String alias) {
        try {
            Files.deleteIfExists(file(alias));
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_SCRATCH_WRITE", "Scratch cleanup failed", ex);
        }
    }

    Result ffmpeg(Purpose purpose, List<String> arguments) {
        return run(Tool.FFMPEG, purpose, arguments);
    }

    Result ffprobe(List<String> arguments) {
        return run(Tool.FFPROBE, Purpose.PROBE, arguments);
    }

    private Result run(Tool tool, Purpose purpose, List<String> arguments) {
        if (cancelled.getAsBoolean()) {
            throw MediaFailure.retry("MEDIA_CANCELLED", "Media step cancelled", null);
        }
        Result result;
        try {
            result = runner.run(new Command(tool, purpose, arguments, scratch), cancelled);
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_PROCESS_FAILED", tool + " could not complete", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw MediaFailure.retry("MEDIA_PROCESS_INTERRUPTED", tool + " was interrupted", ex);
        }
        if (cancelled.getAsBoolean()) {
            throw MediaFailure.retry("MEDIA_CANCELLED", "Media step cancelled", null);
        }
        if (result.exitCode() != 0) {
            throw MediaFailure.retry("MEDIA_PROCESS_FAILED", tool + " exited with " + result.exitCode(), null);
        }
        return result;
    }
}
