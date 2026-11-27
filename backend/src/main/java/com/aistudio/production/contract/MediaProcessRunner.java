package com.aistudio.production.contract;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface MediaProcessRunner {
    enum Tool { FFMPEG, FFPROBE }

    enum Purpose { RENDER, PROBE, DECODE_QC, LOUDNESS }

    record Scratch(long requestId, long itemId, int attempt) {
        public Scratch {
            if (requestId < 1 || itemId < 1 || attempt < 1) {
                throw new IllegalArgumentException("Invalid scratch owner");
            }
        }
    }

    record Command(Tool tool, Purpose purpose, List<String> arguments, Scratch scratch) {
        public Command {
            arguments = List.copyOf(arguments);
        }
    }

    record Result(int exitCode, String output, String diagnosticTail) {}

    boolean ready();

    java.nio.file.Path prepare(Scratch scratch) throws IOException;

    /** Ends the calling worker's use of scratch after synchronous stage work and publication have returned. */
    default void finishStage(long requestId) {}

    Result run(Command command, BooleanSupplier cancelled) throws IOException, InterruptedException;
}
