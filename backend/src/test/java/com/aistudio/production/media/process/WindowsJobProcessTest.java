package com.aistudio.production.media.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsJobProcessTest {
    @TempDir
    Path scratch;

    @Test
    void createsOwnedJobAndDrainsOutput() throws Exception {
        Path executable = Path.of(System.getenv("SystemRoot"), "System32", "where.exe");
        var result = new WindowsJobProcess().run(executable, List.of("/?"), scratch,
                Duration.ofSeconds(10), () -> false);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output() + result.diagnosticTail()).contains("WHERE");
    }

    @Test
    void terminatesAndConfirmsEmptyJobOnTimeout() {
        Path executable = Path.of(System.getenv("SystemRoot"), "System32", "ping.exe");
        assertThatThrownBy(() -> new WindowsJobProcess().run(executable, List.of("-n", "20", "127.0.0.1"), scratch,
                Duration.ofMillis(300), () -> false))
                .isInstanceOf(IOException.class).hasMessage("Media process cancelled or timed out");
    }

    @Test
    void cancellationStopsOwnedJobWithoutTouchingAnotherProcess() throws Exception {
        Path executable = Path.of(System.getenv("SystemRoot"), "System32", "ping.exe");
        List<String> arguments = List.of("-n", "20", "127.0.0.1");
        Process unrelated = new ProcessBuilder(executable.toString(), "-n", "20", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            var polls = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> new WindowsJobProcess().run(executable, arguments, scratch,
                    Duration.ofSeconds(10), () -> polls.incrementAndGet() > 10))
                    .isInstanceOf(IOException.class).hasMessage("Media process cancelled or timed out");
            assertThat(unrelated.isAlive()).isTrue();
        } finally {
            unrelated.destroyForcibly();
            unrelated.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void closesTheOwnedJobWhenLauncherJvmIsKilled() throws Exception {
        Path pidFile = scratch.resolve("owned-child.pid");
        String classpath = helperClasspath();
        Path javaBinary = javaBinary();
        Process launcher = new ProcessBuilder(javaBinary.toString(), "-cp", classpath, OwnedChild.class.getName(),
                "launch", pidFile.toString()).redirectErrorStream(true).start();
        ProcessHandle child = null;
        try {
            for (int i = 0; i < 200 && !java.nio.file.Files.exists(pidFile) && launcher.isAlive(); i++) {
                Thread.sleep(50);
            }
            assertThat(java.nio.file.Files.exists(pidFile)).isTrue();
            long pid = Long.parseLong(java.nio.file.Files.readString(pidFile));
            child = ProcessHandle.of(pid).orElseThrow();
            assertThat(child.isAlive()).isTrue();
            launcher.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            child.onExit().get(5, TimeUnit.SECONDS);
            assertThat(child.isAlive()).isFalse();
        } finally {
            if (launcher.isAlive()) {
                launcher.destroyForcibly();
            }
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void enforcesJobCommitLimitWithoutFillingPhysicalMemory() throws Exception {
        var result = new WindowsJobProcess().run(javaBinary(),
                List.of("-Xmx64m", "-cp", helperClasspath(), OwnedChild.class.getName(), "commit-limit",
                        scratch.resolve("unused.pid").toString()),
                scratch, Duration.ofSeconds(10), () -> false);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("job-commit-rejected");
    }

    @Test
    void rejectsChildCreationUnderAnIncompatibleOuterJob() throws Exception {
        Path pidFile = scratch.resolve("rejected-child.pid");
        Process launcher = new ProcessBuilder(javaBinary().toString(), "-Xmx64m", "-cp", helperClasspath(),
                OwnedChild.class.getName(), "restricted-launch", pidFile.toString()).redirectErrorStream(true).start();
        try {
            assertThat(launcher.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String diagnostic = new String(launcher.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(launcher.exitValue()).as(diagnostic).isZero();
            assertThat(diagnostic).contains("nested-creation-rejected");
            assertThat(pidFile).doesNotExist();
        } finally {
            if (launcher.isAlive()) {
                launcher.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Path javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe");
    }

    private static String helperClasspath() {
        return java.util.stream.Stream.of(OwnedChild.class, WindowsJobProcess.class,
                        com.sun.jna.Native.class, com.sun.jna.platform.win32.Kernel32.class)
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (java.net.URISyntaxException ex) {
                        throw new IllegalStateException(ex);
                    }
                }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    public static class OwnedChild {
        public interface OuterJobApi extends WindowsJobProcess.JobApi {
            boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
        }

        public static void main(String[] args) throws Exception {
            if ("commit-limit".equals(args[0])) {
                checkCommitLimit();
                return;
            }
            if ("restricted-launch".equals(args[0])) {
                checkIncompatibleJob(Path.of(args[1]));
                return;
            }
            Path output = Path.of(args[1]);
            if ("child".equals(args[0])) {
                Path temporary = output.resolveSibling("writing.pid");
                java.nio.file.Files.writeString(temporary, Long.toString(ProcessHandle.current().pid()));
                java.nio.file.Files.move(temporary, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(60_000);
                return;
            }
            Path binary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            new WindowsJobProcess().run(binary, List.of("-Xmx64m", "-cp", System.getProperty("java.class.path"),
                    OwnedChild.class.getName(), "child", output.toString()), output.getParent(),
                    Duration.ofSeconds(30), () -> false);
        }

        private static void checkCommitLimit() {
            var api = Native.load("kernel32", WindowsJobProcess.JobApi.class);
            var limits = new WindowsJobProcess.Limits();
            long cap = 3L * 1024 * 1024 * 1024;
            if (!api.QueryInformationJobObject(null, 9, limits.getPointer(), limits.size(), null)) {
                throw new IllegalStateException("Cannot read current test job limits");
            }
            limits.read();
            if ((limits.basic.flags & 0x200) == 0 || limits.jobMemory.longValue() != cap) {
                throw new IllegalStateException("Expected production memory cap before testing a commit");
            }
            var kernel = Kernel32.INSTANCE;
            HANDLE self = kernel.GetCurrentProcess();
            // Commit without touching pages. The kernel must refuse this request before backing memory is used.
            Pointer excess = kernel.VirtualAllocEx(self, null, new SIZE_T(cap + 4096), 0x3000, 0x04);
            if (excess != null) {
                kernel.VirtualFreeEx(self, excess, new SIZE_T(0), 0x8000);
                throw new IllegalStateException("Job accepted a commit larger than its memory cap");
            }
            int error = kernel.GetLastError();
            if (error != 8 && error != 1455 && error != 1816) {
                throw new IllegalStateException("Unexpected commit rejection error " + error);
            }
            System.out.println("job-commit-rejected " + error);
        }

        private static void checkIncompatibleJob(Path pidFile) throws Exception {
            var api = Native.load("kernel32", OuterJobApi.class);
            var kernel = Kernel32.INSTANCE;
            HANDLE outer = api.CreateJobObjectW(null, null);
            if (outer == null || outer.getPointer() == null) {
                throw new IllegalStateException("Unable to create isolated outer test job");
            }
            try {
                // The launcher accounts for the single allowed process in this outer Job.
                var limits = new WindowsJobProcess.Limits();
                limits.basic.flags = 0x8; // JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                limits.basic.activeProcessLimit = 1;
                limits.write();
                if (!api.SetInformationJobObject(outer, 9, limits.getPointer(), limits.size())
                        || !api.AssignProcessToJobObject(outer, kernel.GetCurrentProcess())) {
                    throw new IllegalStateException("Unable to configure isolated outer test job: " + kernel.GetLastError());
                }
                try {
                    new WindowsJobProcess().run(javaBinary(), List.of("-Xmx64m", "-cp",
                            System.getProperty("java.class.path"), OwnedChild.class.getName(), "child", pidFile.toString()),
                            pidFile.getParent(), Duration.ofSeconds(5), () -> false);
                } catch (IOException ex) {
                    if (ex.getMessage().startsWith("CreateProcessW failed")) {
                        System.out.println("nested-creation-rejected");
                        return;
                    }
                    throw ex;
                }
                throw new IllegalStateException("Incompatible nested Job accepted process creation");
            } finally {
                kernel.CloseHandle(outer);
            }
        }
    }
}
