package com.aistudio.production.media.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "AI_STUDIO_TEST_LINUX_MEDIA", matches = "true")
class LinuxMediaProcessTest {
    @TempDir
    Path scratch;

    @Test
    void supervisesTimeoutCancellationAndOwnerJvmDeath() throws Exception {
        String owner = "ai-studio-owner-" + UUID.randomUUID() + ".service";
        String classpath = String.join(":", location(Fixture.class), location(LinuxMediaProcess.class));
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = scratch.resolve("launcher.log");
        Process client = new ProcessBuilder("/usr/bin/systemd-run", "--user", "--wait", "--pipe", "--quiet",
                "--unit=" + owner, "--property=Type=exec", "--",
                javaBinary.toString(), "-cp", classpath, Fixture.class.getName(), "owner", owner, scratch.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            Path pidFile = scratch.resolve("active");
            for (int i = 0; i < 300 && !Files.exists(pidFile) && client.isAlive(); i++) {
                Thread.sleep(100);
            }
            assertThat(Files.exists(pidFile)).as(Files.readString(log)).isTrue();
            var ids = Files.readAllLines(pidFile).stream().map(Long::parseLong).toList();
            assertThat(ids).hasSize(2);
            assertThat(ids).allMatch(LinuxMediaProcessTest::live);
            control("kill", "--kill-whom=main", "--signal=KILL", owner);
            for (int i = 0; i < 100 && ids.stream().anyMatch(LinuxMediaProcessTest::live); i++) {
                Thread.sleep(100);
            }
            assertThat(ids).noneMatch(LinuxMediaProcessTest::live);
            assertThat(client.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            control("stop", owner);
            client.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static boolean live(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            return stat.charAt(stat.lastIndexOf(')') + 2) != 'Z';
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    private static void control(String... arguments) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("/usr/bin/systemctl", "--user"));
        cmd.addAll(List.of(arguments));
        var process = new ProcessBuilder(cmd).inheritIO().start();
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("systemctl timed out in test");
        }
        if (process.exitValue() != 0 && !"stop".equals(arguments[0])) {
            throw new IllegalStateException("systemctl could not run the test command");
        }
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("child")) {
                String group = Files.readString(Path.of("/proc/self/cgroup")).strip().substring(3);
                String limit = Files.readString(Path.of("/sys/fs/cgroup").resolve(group.substring(1))
                        .resolve("memory.max")).strip();
                if (!"3221225472".equals(limit) || !group.contains("ai-studio-media-")) {
                    throw new IllegalStateException("Child is outside the limited media cgroup");
                }
                var other = new ProcessBuilder("/usr/bin/sleep", "180").start();
                Path path = Path.of(args[1]);
                Path temp = Files.createTempFile(path.getParent(), "pid-", ".tmp");
                Files.writeString(temp, ProcessHandle.current().pid() + "\n" + other.pid() + "\n");
                Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                other.waitFor();
                return;
            }
            var runner = new LinuxMediaProcess(args[1]);
            var scratch = Path.of(args[2]);
            if (!"protected".equals(runner.run(Path.of("/usr/bin/printf"), List.of("%s", "protected"),
                    scratch, Duration.ofSeconds(8), () -> false).output())) {
                throw new IllegalStateException("Protected process did not return its stdout");
            }
            for (String check : List.of("timeout", "cancel")) {
                Path pid = scratch.resolve(check);
                boolean failed = false;
                try {
                    failed = runner.run(java(), childArgs(pid), scratch,
                            Duration.ofSeconds(check.equals("timeout") ? 5 : 12),
                            () -> check.equals("cancel") && Files.exists(pid)).exitCode() != 0;
                } catch (java.io.IOException expected) {
                    failed = true;
                }
                if (!failed || !Files.exists(pid)
                        || Files.readAllLines(pid).stream().map(Long::parseLong).anyMatch(LinuxMediaProcessTest::live)) {
                    throw new IllegalStateException(check + " left processes behind or did not start");
                }
            }
            runner.run(java(), childArgs(scratch.resolve("active")), scratch, Duration.ofSeconds(90), () -> false);
        }

        private static Path java() {
            return Path.of(System.getProperty("java.home"), "bin", "java");
        }

        private static List<String> childArgs(Path pidFile) {
            return List.of("-Xmx64m", "-cp", System.getProperty("java.class.path"),
                    Fixture.class.getName(), "child", pidFile.toString());
        }
    }
}
