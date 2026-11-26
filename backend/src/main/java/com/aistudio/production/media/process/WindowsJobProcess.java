package com.aistudio.production.media.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.aistudio.production.contract.MediaProcessRunner.Result;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.WinBase.STARTUPINFO;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.platform.win32.WinNT.IO_COUNTERS;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

final class WindowsJobProcess {
    private static final int JOB_LIST = 0x0002000d;
    private static final int HANDLE_LIST = 0x00020002;
    private static final int CREATE_FLAGS = 0x08080004; // NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT | SUSPENDED
    private static final int BROKEN_PIPE = 109;
    private static final int STDOUT_LIMIT = 2 * 1024 * 1024;
    private static final int STDERR_LIMIT = 64 * 1024;

    public interface JobApi extends StdCallLibrary {
        HANDLE CreateJobObjectW(Pointer attributes, WString name);
        boolean SetInformationJobObject(HANDLE job, int informationClass, Pointer information, int size);
        boolean QueryInformationJobObject(HANDLE job, int informationClass, Pointer information, int size,
                IntByReference returned);
        boolean TerminateJobObject(HANDLE job, int exitCode);
        boolean IsProcessInJob(HANDLE process, HANDLE job, IntByReference member);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, Pointer size);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, SIZE_T key, Pointer value, SIZE_T bytes,
                Pointer previous, Pointer returned);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean CreateProcessW(WString executable, char[] commandLine, Pointer processAttributes,
                Pointer threadAttributes, boolean inherit, int flags, Pointer environment, WString directory,
                StartupEx startup, PROCESS_INFORMATION process);
        int ResumeThread(HANDLE thread);
    }

    @Structure.FieldOrder({"startup", "attributes"})
    public static class StartupEx extends Structure {
        public STARTUPINFO startup = new STARTUPINFO();
        public Pointer attributes;
    }

    @Structure.FieldOrder({"processTime", "jobTime", "flags", "minimumWorkingSet", "maximumWorkingSet",
            "activeProcessLimit", "affinity", "priority", "scheduling"})
    public static class BasicLimits extends Structure {
        public long processTime;
        public long jobTime;
        public int flags;
        public SIZE_T minimumWorkingSet = new SIZE_T();
        public SIZE_T maximumWorkingSet = new SIZE_T();
        public int activeProcessLimit;
        public SIZE_T affinity = new SIZE_T();
        public int priority;
        public int scheduling;
    }

    @Structure.FieldOrder({"basic", "io", "processMemory", "jobMemory", "peakProcess", "peakJob"})
    public static class Limits extends Structure {
        public BasicLimits basic = new BasicLimits();
        public IO_COUNTERS io = new IO_COUNTERS();
        public SIZE_T processMemory = new SIZE_T();
        public SIZE_T jobMemory = new SIZE_T();
        public SIZE_T peakProcess = new SIZE_T();
        public SIZE_T peakJob = new SIZE_T();
    }

    private final Kernel32 kernel = Kernel32.INSTANCE;
    private final JobApi api = Native.load("kernel32", JobApi.class);

    Result run(Path binary, List<String> arguments, Path cwd, Duration timeout, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        HANDLE job = api.CreateJobObjectW(null, null);
        require(valid(job), "CreateJobObjectW");
        PROCESS_INFORMATION process = new PROCESS_INFORMATION();
        SECURITY_ATTRIBUTES inheritable = new SECURITY_ATTRIBUTES();
        inheritable.bInheritHandle = true;
        HANDLE input = null;
        Pipe stdout = null;
        Pipe stderr = null;
        boolean created = false;
        boolean assigned = false;
        try {
            var limits = new Limits();
            limits.basic.flags = 0x2200; // KILL_ON_JOB_CLOSE | JOB_MEMORY
            limits.jobMemory = new SIZE_T(3L * 1024 * 1024 * 1024);
            limits.write();
            require(api.SetInformationJobObject(job, 9, limits.getPointer(), limits.size()), "SetInformationJobObject");
            input = kernel.CreateFile("NUL", 0x80000000, 3, inheritable, 3, 0x80, null);
            require(valid(input), "Open NUL");
            stdout = pipe(inheritable);
            stderr = pipe(inheritable);
            var startup = new StartupEx();
            startup.startup.cb = new DWORD(startup.size());
            startup.startup.dwFlags = WinBase.STARTF_USESTDHANDLES;
            startup.startup.hStdInput = input;
            startup.startup.hStdOutput = stdout.write();
            startup.startup.hStdError = stderr.write();
            try (var size = new Memory(Native.SIZE_T_SIZE)) {
                size.clear();
                api.InitializeProcThreadAttributeList(null, 2, 0, size);
                long bytes = Native.SIZE_T_SIZE == 8 ? size.getLong(0) : Integer.toUnsignedLong(size.getInt(0));
                require(bytes > 0 && bytes < 65536, "InitializeProcThreadAttributeList size");
                try (var attrs = new Memory(bytes);
                        var jobs = handles(job);
                        var stdio = handles(input, stdout.write(), stderr.write())) {
                    require(api.InitializeProcThreadAttributeList(attrs, 2, 0, size), "InitializeProcThreadAttributeList");
                    try {
                        attribute(attrs, JOB_LIST, jobs);
                        attribute(attrs, HANDLE_LIST, stdio);
                        startup.attributes = attrs;
                        if (cancelled.getAsBoolean()) {
                            throw new IOException("Media process cancelled before creation");
                        }
                        require(api.CreateProcessW(new WString(binary.toString()), commandLine(binary, arguments),
                                null, null, true, CREATE_FLAGS, null, new WString(cwd.toString()),
                                startup, process), "CreateProcessW");
                        created = true;
                    } finally {
                        api.DeleteProcThreadAttributeList(attrs);
                    }
                }
            }
            var member = new IntByReference();
            require(api.IsProcessInJob(process.hProcess, job, member) && member.getValue() != 0, "IsProcessInJob");
            assigned = true;
            require(api.ResumeThread(process.hThread) == 1, "ResumeThread");
            stdout.closeWriter();
            stderr.closeWriter();
            return waitFor(process.hProcess, job, stdout.read(), stderr.read(), timeout, cancelled);
        } finally {
            try {
                if (created && !assigned) {
                    kernel.TerminateProcess(process.hProcess, 1);
                    require(kernel.WaitForSingleObject(process.hProcess, 5000) == 0, "Wait for rejected process");
                }
                if (active(job) != 0) {
                    require(api.TerminateJobObject(job, 1), "TerminateJobObject");
                    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (active(job) != 0 && System.nanoTime() < end) {
                        java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
                    }
                    require(active(job) == 0, "Wait for empty Job");
                }
            } finally {
                close(process.hThread);
                close(process.hProcess);
                close(input);
                if (stdout != null) {
                    stdout.close();
                }
                if (stderr != null) {
                    stderr.close();
                }
                close(job);
            }
        }
    }

    private Result waitFor(HANDLE process, HANDLE job, HANDLE output, HANDLE errors, Duration timeout,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        var stdout = new ByteArrayOutputStream();
        byte[] tail = new byte[STDERR_LIMIT];
        int tailStart = 0;
        int tailCount = 0;
        byte[] buffer = new byte[8192];
        long start = System.nanoTime();
        boolean emptyAfterExit = false;
        for (;;) {
            if (cancelled.getAsBoolean() || System.nanoTime() - start >= timeout.toNanos()) {
                throw new IOException("Media process cancelled or timed out");
            }
            int out = read(output, buffer);
            if (out > STDOUT_LIMIT - stdout.size()) {
                throw new IOException("Media process output limit exceeded");
            }
            stdout.write(buffer, 0, out);
            int err = read(errors, buffer);
            for (int i = 0; i < err; i++) {
                tail[(tailStart + tailCount) % tail.length] = buffer[i];
                if (tailCount == tail.length) {
                    tailStart = (tailStart + 1) % tail.length;
                } else {
                    tailCount++;
                }
            }
            int wait = kernel.WaitForSingleObject(process, out + err == 0 ? 10 : 0);
            boolean empty = wait == 0 && out + err == 0 && active(job) == 0;
            if (empty && emptyAfterExit) {
                var code = new IntByReference();
                require(kernel.GetExitCodeProcess(process, code), "GetExitCodeProcess");
                byte[] diagnostic = new byte[tailCount];
                for (int i = 0; i < tailCount; i++) {
                    diagnostic[i] = tail[(tailStart + i) % tail.length];
                }
                return new Result(code.getValue(), stdout.toString(StandardCharsets.UTF_8),
                        new String(diagnostic, StandardCharsets.UTF_8));
            }
            emptyAfterExit = empty;
            require(wait == 0 || wait == 258, "WaitForSingleObject");
            if (Thread.interrupted()) {
                throw new InterruptedException("Media process interrupted");
            }
        }
    }

    private int read(HANDLE handle, byte[] buffer) throws IOException {
        var available = new IntByReference();
        if (!kernel.PeekNamedPipe(handle, null, 0, null, available, null)) {
            require(kernel.GetLastError() == BROKEN_PIPE, "PeekNamedPipe");
            return 0;
        }
        if (available.getValue() == 0) {
            return 0;
        }
        var read = new IntByReference();
        require(kernel.ReadFile(handle, buffer, Math.min(buffer.length, available.getValue()), read, null), "ReadFile");
        return read.getValue();
    }

    private int active(HANDLE job) throws IOException {
        try (var info = new Memory(48)) {
            require(api.QueryInformationJobObject(job, 1, info, (int) info.size(), null), "QueryInformationJobObject");
            return info.getInt(40); // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION.ActiveProcesses
        }
    }

    private void attribute(Memory list, int key, Memory handles) throws IOException {
        require(api.UpdateProcThreadAttribute(list, 0, new SIZE_T(key), handles, new SIZE_T(handles.size()), null, null),
                "UpdateProcThreadAttribute");
    }

    private Pipe pipe(SECURITY_ATTRIBUTES inheritable) throws IOException {
        var read = new HANDLEByReference();
        var write = new HANDLEByReference();
        require(kernel.CreatePipe(read, write, inheritable, 0), "CreatePipe");
        var pipe = new Pipe(read.getValue(), write.getValue());
        if (!kernel.SetHandleInformation(pipe.read(), 1, 0)) {
            pipe.close();
            throw new IOException("Cannot isolate media pipe handles");
        }
        return pipe;
    }

    private final class Pipe implements AutoCloseable {
        private final HANDLE read;
        private HANDLE write;

        Pipe(HANDLE read, HANDLE write) {
            this.read = read;
            this.write = write;
        }

        HANDLE read() { return read; }
        HANDLE write() { return write; }

        void closeWriter() {
            WindowsJobProcess.this.close(write);
            write = null;
        }

        @Override
        public void close() {
            closeWriter();
            WindowsJobProcess.this.close(read);
        }
    }

    private static Memory handles(HANDLE... values) {
        var pointer = new Memory((long) values.length * Native.POINTER_SIZE);
        for (int i = 0; i < values.length; i++) {
            pointer.setPointer((long) i * Native.POINTER_SIZE, values[i].getPointer());
        }
        return pointer;
    }

    private static boolean valid(HANDLE handle) {
        return handle != null && handle.getPointer() != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle);
    }

    private void close(HANDLE handle) {
        if (valid(handle)) {
            kernel.CloseHandle(handle);
        }
    }

    private void require(boolean ok, String function) throws IOException {
        if (!ok) {
            throw new IOException(function + " failed (" + kernel.GetLastError() + ")");
        }
    }

    static char[] commandLine(Path binary, List<String> arguments) {
        StringBuilder line = new StringBuilder();
        quote(line, binary.toString());
        for (String value : arguments) {
            line.append(' ');
            quote(line, value);
        }
        if (line.length() >= 32767) {
            throw new IllegalArgumentException("Windows media command line exceeds limit");
        }
        return (line.toString() + '\0').toCharArray();
    }

    private static void quote(StringBuilder line, String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("NUL in media argument");
        }
        line.append('"');
        int slashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (next == '\\') {
                slashes++;
            } else {
                line.append("\\".repeat(next == '"' ? 2 * slashes + 1 : slashes));
                line.append(next);
                slashes = 0;
            }
        }
        line.append("\\".repeat(2 * slashes)).append('"');
    }
}
