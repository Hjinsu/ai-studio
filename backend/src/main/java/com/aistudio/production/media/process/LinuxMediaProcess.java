package com.aistudio.production.media.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.aistudio.production.contract.MediaProcessRunner.Result;

/** Each process starts as a transient user service in its own cgroup, bound to the JVM's service. */
final class LinuxMediaProcess {
    private static final Path CGROUP = Path.of("/sys/fs/cgroup");
    private static final String SYSTEMCTL = "/usr/bin/systemctl";
    private static final String SYSTEMD_RUN = "/usr/bin/systemd-run";
    private final String owner;

    LinuxMediaProcess(String owner) throws IOException, InterruptedException {
        if (!owner.matches("[A-Za-z0-9_.@-]{1,100}\\.service")
                || owner.startsWith("-") || !Files.isRegularFile(CGROUP.resolve("cgroup.controllers"))) {
            throw new IOException("Linux media requires a dedicated systemd user service and cgroup v2");
        }
        this.owner = owner;
        verifyOwner();
    }

    private void verifyOwner() throws IOException, InterruptedException {
        var properties = control("show", "--property=MainPID,ActiveState,ControlGroup", "--", owner).lines().toList();
        String group = properties.stream().filter(line -> line.startsWith("ControlGroup="))
                .map(line -> line.substring("ControlGroup=".length())).findFirst().orElse("");
        if (!properties.contains("MainPID=" + ProcessHandle.current().pid())
                || !properties.contains("ActiveState=active") || !group.startsWith("/")
                || !Files.readAllLines(Path.of("/proc/self/cgroup")).contains("0::" + group)) {
            throw new IOException("Linux media supervisor does not own the JVM");
        }
    }

    Result run(Path binary, List<String> arguments, Path cwd, Duration timeout, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        verifyOwner();
        if (!cwd.toString().matches("/[A-Za-z0-9_./-]+")) {
            throw new IOException("Linux media scratch contains unsupported characters");
        }
        if (cancelled.getAsBoolean()) {
            throw new IOException("Media process cancelled before creation");
        }
        String unit = "ai-studio-media-" + UUID.randomUUID() + ".service";
        Path marker = Files.createTempFile(cwd, ".launch-", ".permit");
        Process client = null;
        try {
            var command = new ArrayList<>(List.of(SYSTEMD_RUN, "--user", "--wait", "--pipe", "--quiet",
                    "--expand-environment=no", "--unit=" + unit, "--working-directory=" + cwd,
                    "--property=BindsTo=" + owner, "--property=After=" + owner,
                    "--property=Type=exec", "--property=ExitType=cgroup", "--property=KillMode=control-group",
                    "--property=TimeoutStopSec=2s", "--property=SendSIGKILL=yes", "--property=MemoryMax=3221225472",
                    "--property=MemorySwapMax=0", "--property=OOMPolicy=kill", "--property=TasksMax=128",
                    "--property=RuntimeMaxSec=" + Math.max(1, timeout.toSeconds()) + "s",
                    "--property=ExecCondition=/usr/bin/test -f " + marker + " -a -d /proc/"
                            + ProcessHandle.current().pid(), "--", binary.toString()));
            command.addAll(arguments);
            client = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .start();
            var overflow = new AtomicBoolean();
            var stdout = new Capture(client.getInputStream(), 2 * 1024 * 1024, false, overflow);
            var stderr = new Capture(client.getErrorStream(), 64 * 1024, true, overflow);
            Thread out = Thread.startVirtualThread(stdout);
            Thread err = Thread.startVirtualThread(stderr);
            long start = System.nanoTime();
            while (!client.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || overflow.get() || System.nanoTime() - start >= timeout.toNanos()) {
                    throw new IOException("Media process cancelled, timed out, or exceeded its output limit");
                }
            }
            out.join(1000);
            err.join(1000);
            if (out.isAlive() || err.isAlive() || overflow.get() || stdout.failed || stderr.failed) {
                throw new IOException("Media process output could not be captured");
            }
            return new Result(client.exitValue(), stdout.text(), stderr.text());
        } finally {
            // Revoke a queued launch before attempting to stop the transient unit.
            try {
                Files.deleteIfExists(marker);
            } finally {
                if (client != null) {
                    client.destroyForcibly();
                    stopAndVerify(unit);
                }
            }
        }
    }

    private void stopAndVerify(String unit) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            try {
                control("stop", "--", unit);
            } catch (IOException ex) {
                // A successful transient service can already be unloaded. Verify explicitly below.
            }
            var properties = control("show", "--property=LoadState,ActiveState,ControlGroup", "--", unit).lines().toList();
            if (properties.contains("LoadState=not-found") && properties.contains("ActiveState=inactive")) {
                return;
            }
            if (!properties.contains("ActiveState=inactive") && !properties.contains("ActiveState=failed")) {
                throw new IOException("Linux media boundary did not stop");
            }
            String group = properties.stream().filter(line -> line.startsWith("ControlGroup="))
                    .map(line -> line.substring("ControlGroup=".length())).findFirst()
                    .orElseThrow(() -> new IOException("Linux media cgroup status missing"));
            if (!group.isEmpty()) {
                if (!group.startsWith("/")) {
                    throw new IOException("Linux media cgroup path is invalid");
                }
                Path events = CGROUP.resolve(group.substring(1)).resolve("cgroup.events").normalize();
                if (!events.startsWith(CGROUP)) {
                    throw new IOException("Linux media cgroup path escaped");
                }
                try {
                    if (!Files.readAllLines(events).contains("populated 0")) {
                        throw new IOException("Linux media cgroup is still populated");
                    }
                } catch (java.nio.file.NoSuchFileException removed) {
                    // A populated cgroup cannot be removed by systemd.
                }
            }
        } catch (IOException ex) {
            throw new IOException("Linux media boundary could not be confirmed empty", ex);
        } catch (InterruptedException ex) {
            interrupted = true;
            throw new IOException("Linux media boundary verification interrupted", ex);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String control(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of(SYSTEMCTL, "--user", "--no-pager", "--all"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            // show outputs only the small fixed property set; stop has no stdout on success.
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                throw new IOException("systemctl did not finish");
            }
            String output = new String(process.getInputStream().readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && !(arguments[0].equals("show") && output.contains("LoadState=not-found"))) {
                throw new IOException("systemctl could not verify the Linux media supervisor");
            }
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    private static final class Capture implements Runnable {
        private final java.io.InputStream input;
        private final byte[] buffer;
        private final boolean tail;
        private final AtomicBoolean overflow;
        private int count;
        private int offset;
        private boolean failed;

        Capture(java.io.InputStream input, int limit, boolean tail, AtomicBoolean overflow) {
            this.input = input;
            this.buffer = new byte[limit];
            this.tail = tail;
            this.overflow = overflow;
        }

        @Override
        public void run() {
            try (input) {
                byte[] chunk = new byte[8192];
                for (int size; (size = input.read(chunk)) != -1;) {
                    if (!tail && size > buffer.length - count) {
                        overflow.set(true);
                        return;
                    }
                    for (int i = 0; i < size; i++) {
                        buffer[(offset + count) % buffer.length] = chunk[i];
                        if (count == buffer.length) {
                            offset = (offset + 1) % buffer.length;
                        } else {
                            count++;
                        }
                    }
                }
            } catch (IOException ex) {
                failed = true;
            }
        }

        String text() {
            var ordered = new byte[count];
            for (int i = 0; i < count; i++) {
                ordered[i] = buffer[(offset + i) % buffer.length];
            }
            return new String(ordered, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
