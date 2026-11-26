package com.aistudio.production.media.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
    void closesTheOwnedJobWhenLauncherJvmIsKilled() throws Exception {
        Path pidFile = scratch.resolve("owned-child.pid");
        String classpath = java.util.stream.Stream.of(OwnedChild.class, WindowsJobProcess.class,
                        com.sun.jna.Native.class, com.sun.jna.platform.win32.Kernel32.class)
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (java.net.URISyntaxException ex) {
                        throw new IllegalStateException(ex);
                    }
                }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
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
            launcher.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
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

    public static class OwnedChild {
        public static void main(String[] args) throws Exception {
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
    }
}
