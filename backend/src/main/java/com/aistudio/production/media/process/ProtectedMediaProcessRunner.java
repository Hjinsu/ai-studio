package com.aistudio.production.media.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.media.storage.MediaCleanup;
import com.sun.jna.Platform;

@Component
@ConditionalOnProperty(prefix = "ai-studio.production.media", name = "enabled", havingValue = "true")
final class ProtectedMediaProcessRunner implements MediaProcessRunner {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long SCRATCH_FLOOR = 5 * GIB;
    private static final long MAX_SCRATCH_VOLUME = 32 * GIB;

    private record Binary(Path path, String sha256) {}

    private final String ffmpegPath;
    private final String ffmpegHash;
    private final String ffprobePath;
    private final String ffprobeHash;
    private final String scratchRoot;
    private final String workspaceRoot;
    private final String fontPath;
    private final String fontHash;
    private final String fontFamily;
    private final int renderTimeout;
    private final int probeTimeout;
    private final int decodeTimeout;
    private final int loudnessTimeout;
    private final ReentrantLock execution = new ReentrantLock(true);
    private final Object scratchAdmission = new Object();
    private final MediaCleanup cleanup;
    private long cleanupAt;
    private volatile boolean verified;
    private volatile boolean failed;
    private WindowsJobProcess windows;
    private LinuxMediaProcess linux;
    private final String linuxOwnerService;
    private final boolean linuxVerified;

    ProtectedMediaProcessRunner(
            @Value("${ai-studio.production.media.ffmpeg-path:}") String ffmpegPath,
            @Value("${ai-studio.production.media.ffmpeg-sha256:}") String ffmpegHash,
            @Value("${ai-studio.production.media.ffprobe-path:}") String ffprobePath,
            @Value("${ai-studio.production.media.ffprobe-sha256:}") String ffprobeHash,
            @Value("${ai-studio.production.media.scratch-root:}") String scratchRoot,
            @Value("${ai-studio.production.media.storage-root:}") String workspaceRoot,
            @Value("${ai-studio.production.media.font-path:}") String fontPath,
            @Value("${ai-studio.production.media.font-sha256:}") String fontHash,
            @Value("${ai-studio.production.media.font-family:Noto Sans KR}") String fontFamily,
            @Value("${ai-studio.production.media.render-timeout-seconds:600}") int renderTimeout,
            @Value("${ai-studio.production.media.probe-timeout-seconds:30}") int probeTimeout,
            @Value("${ai-studio.production.media.decode-timeout-seconds:120}") int decodeTimeout,
            @Value("${ai-studio.production.media.loudness-timeout-seconds:60}") int loudnessTimeout,
            @Value("${ai-studio.production.media.linux-owner-service:}") String linuxOwnerService,
            @Value("${ai-studio.production.media.linux-protection-verified:false}") boolean linuxVerified,
            MediaCleanup cleanup) {
        this.linuxOwnerService = linuxOwnerService;
        this.linuxVerified = linuxVerified;
        this.ffmpegPath = ffmpegPath;
        this.ffmpegHash = ffmpegHash;
        this.ffprobePath = ffprobePath;
        this.ffprobeHash = ffprobeHash;
        this.scratchRoot = scratchRoot;
        this.workspaceRoot = workspaceRoot;
        this.fontPath = fontPath;
        this.fontHash = fontHash;
        if (!fontFamily.matches("Noto Sans[ A-Za-z0-9]{0,40}")) {
            throw new IllegalArgumentException("Invalid media font family");
        }
        this.fontFamily = fontFamily;
        this.renderTimeout = bounded(renderTimeout, 600);
        this.probeTimeout = bounded(probeTimeout, 30);
        this.decodeTimeout = bounded(decodeTimeout, 120);
        this.loudnessTimeout = bounded(loudnessTimeout, 60);
        this.cleanup = cleanup;
    }

    @Override
    public synchronized boolean ready() {
        if (failed || (!Platform.isWindows() && !(Platform.isLinux() && linuxVerified))) {
            return false;
        }
        if (verified) {
            return true;
        }
        try {
            Path root = scratch();
            var volume = Files.getFileStore(root);
            if (volume.equals(Files.getFileStore(Path.of(workspaceRoot))) || volume.getTotalSpace() > MAX_SCRATCH_VOLUME) {
                throw new IOException("Media scratch requires a dedicated volume");
            }
            var ffmpeg = binary(Tool.FFMPEG);
            var ffprobe = binary(Tool.FFPROBE);
            if (Platform.isWindows()) {
                windows = new WindowsJobProcess();
            } else {
                linux = new LinuxMediaProcess(linuxOwnerService);
            }
            if (!startup(ffmpeg, root, "-version").contains("ffmpeg version")
                    || !startup(ffprobe, root, "-version").contains("ffprobe version")
                    || !all(startup(ffmpeg, root, "-encoders"), List.of("libx264", "aac"))
                    || !all(startup(ffmpeg, root, "-filters"),
                            List.of("scale", "pad", "zoompan", "fps", "xfade", "ass", "amix", "loudnorm",
                                    "silencedetect", "ebur128", "setparams", "settb"))) {
                throw new IOException("Media binary capabilities are missing");
            }
            smoke(ffmpeg, ffprobe, root);
            verified = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException | LinkageError e) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger(ProtectedMediaProcessRunner.class)
                    .warn("Media execution preparation failed: {}", e.getClass().getSimpleName());
        }
        return verified;
    }

    @Override
    public Path prepare(Scratch handle) throws IOException {
        if (!ready()) {
            throw new IOException("Media process protection is unavailable");
        }
        Path root = realDirectory(Path.of(scratchRoot));
        if (execution.tryLock()) {
            try {
                long now = System.nanoTime();
                if (cleanupAt == 0 || now - cleanupAt > Duration.ofMinutes(10).toNanos()) {
                    synchronized (scratchAdmission) {
                        cleanup.reap(root, Path.of(workspaceRoot));
                    }
                    cleanupAt = now;
                }
            } finally {
                execution.unlock();
            }
        }
        synchronized (scratchAdmission) {
            return reserveScratch(root, handle);
        }
    }

    private Path reserveScratch(Path root, Scratch handle) throws IOException {
        String name = "r" + handle.requestId() + "-i" + handle.itemId() + "-a" + handle.attempt();
        Path attempt = root.resolve(name);
        if (attempt.toString().length() > 200) {
            throw new IOException("Media attempt path is too long");
        }
        try (var entries = Files.list(root)) {
            long remainingAttempts = entries.filter(path -> !path.equals(attempt)
                    && path.getFileName().toString().matches("r[0-9]+-i[0-9]+-a[0-9]+")).limit(33).count();
            if (Files.getFileStore(root).getUsableSpace() < SCRATCH_FLOOR + (remainingAttempts + 1) * GIB) {
                throw new IOException("Media scratch reservation exceeds capacity");
            }
        }
        try {
            Files.createDirectory(attempt);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Existing directories are used for subsequent commands within the same attempt.
        }
        return realDirectory(attempt);
    }

    @Override
    public Result run(Command command, BooleanSupplier cancelled) throws IOException, InterruptedException {
        while (!execution.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("Media process cancelled while waiting");
            }
        }
        try {
            Path cwd = prepare(command.scratch());
            Binary executable = binary(command.tool());
            int seconds = switch (command.purpose()) {
                case RENDER -> renderTimeout;
                case PROBE -> probeTimeout;
                case DECODE_QC -> decodeTimeout;
                case LOUDNESS -> loudnessTimeout;
            };
            if ((command.purpose() == Purpose.PROBE) != (command.tool() == Tool.FFPROBE)) {
                throw new IOException("Tool does not match the media operation");
            }
            var args = new java.util.ArrayList<String>();
            if (command.tool() == Tool.FFMPEG) {
                args.add("-nostdin");
            }
            args.addAll(command.arguments());
            var volume = Files.getFileStore(cwd);
            long[] checkedAt = {0};
            return launch(executable.path(), args, cwd, Duration.ofSeconds(seconds), () -> {
                if (cancelled.getAsBoolean()) {
                    return true;
                }
                long now = System.nanoTime();
                if (checkedAt[0] != 0 && now - checkedAt[0] < 250_000_000L) {
                    return false;
                }
                checkedAt[0] = now;
                try {
                    return volume.getUsableSpace() < SCRATCH_FLOOR;
                } catch (IOException ex) {
                    return true;
                }
            });
        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().startsWith("CreateProcessW")
                    || e.getMessage().startsWith("IsProcessInJob") || e.getMessage().startsWith("SetInformationJobObject")
                    || e.getMessage().startsWith("TerminateJobObject") || e.getMessage().startsWith("Wait for empty Job")
                    || e.getMessage().startsWith("Linux media boundary")
                    || e.getMessage().startsWith("Linux media supervisor"))) {
                failed = true;
            }
            throw e;
        } finally {
            execution.unlock();
        }
    }

    private Path scratch() throws IOException {
        Path root = realDirectory(Path.of(scratchRoot));
        if (!root.toString().matches("[!-~]{1,150}") || Files.getFileStore(root).getUsableSpace() < SCRATCH_FLOOR + GIB) {
            throw new IOException("Media scratch path or available space is invalid");
        }
        return root;
    }

    private static Path realDirectory(Path path) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.equals(path.toRealPath())) {
            throw new IOException("Media requires a real absolute directory");
        }
        return path;
    }

    private Binary binary(Tool tool) throws IOException {
        Path path = Path.of(tool == Tool.FFMPEG ? ffmpegPath : ffprobePath);
        String expected = tool == Tool.FFMPEG ? ffmpegHash : ffprobeHash;
        verifyIdentity(path, expected);
        return new Binary(path, expected);
    }

    private static void verifyIdentity(Path path, String expected) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.equals(path.toRealPath()) || !expected.matches("[0-9a-f]{64}")) {
            throw new IOException("Media binary identity is invalid");
        }
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[64 * 1024];
            for (int count; (count = stream.read(bytes)) != -1;) {
                hash.update(bytes, 0, count);
            }
            if (!expected.equals(HexFormat.of().formatHex(hash.digest()))) {
                throw new IOException("Media binary SHA-256 mismatch");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Result launch(Path binary, List<String> args, Path cwd, Duration timeout, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return windows != null ? windows.run(binary, args, cwd, timeout, cancelled)
                : linux.run(binary, args, cwd, timeout, cancelled);
    }

    private String startup(Binary binary, Path cwd, String... arguments) throws IOException, InterruptedException {
        var result = launch(binary.path(), List.of(arguments), cwd, Duration.ofSeconds(probeTimeout), () -> false);
        if (result.exitCode() != 0) {
            throw new IOException("Media tool preparation failed");
        }
        return result.output() + result.diagnosticTail();
    }

    private void smoke(Binary ffmpeg, Binary ffprobe, Path root) throws IOException, InterruptedException {
        Path font = Path.of(fontPath);
        verifyIdentity(font, fontHash);
        Path attempt = Files.createTempDirectory(root, "prepare-");
        Path fonts = attempt.resolve("fonts");
        Path fontCopy = fonts.resolve("noto-bold.ttf");
        Path subtitle = attempt.resolve("sub.ass");
        Path video = attempt.resolve("smoke.mp4");
        try {
            Files.createDirectory(fonts);
            Files.copy(font, fontCopy);
            verifyIdentity(fontCopy, fontHash);
            Files.writeString(subtitle, """
                    [Script Info]
                    ScriptType: v4.00+
                    PlayResX: 320
                    PlayResY: 240
                    [V4+ Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                    Style: Default,%s,32,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
                    [Events]
                    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,Science \\N 과학
                    """.formatted(fontFamily));
            String burn = startup(ffmpeg, attempt, "-nostdin", "-f", "lavfi", "-i", "color=black:s=320x240:r=30",
                    "-vf", "ass=sub.ass:fontsdir=fonts", "-frames:v", "1", "-c:v", "libx264", "smoke.mp4");
            if (!burn.contains("fontselect") || burn.contains("failed to find any fallback")
                    || !startup(ffprobe, attempt, "-v", "error", "-show_entries", "stream=codec_name",
                            "-of", "csv=p=0", "smoke.mp4").contains("h264")) {
                throw new IOException("Media font or encode smoke failed");
            }
            startup(ffmpeg, attempt, "-nostdin", "-v", "error", "-i", "smoke.mp4", "-f", "null", "-");
        } finally {
            Files.deleteIfExists(video);
            Files.deleteIfExists(subtitle);
            Files.deleteIfExists(fontCopy);
            Files.deleteIfExists(fonts);
            Files.deleteIfExists(attempt);
        }
    }

    private static boolean all(String output, List<String> required) {
        return required.stream().allMatch(output::contains);
    }

    private static int bounded(int seconds, int maximum) {
        if (seconds < 1 || seconds > maximum) {
            throw new IllegalArgumentException("Invalid media timeout");
        }
        return seconds;
    }
}
