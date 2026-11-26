package com.aistudio.production.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;

public interface MediaStore {
    enum Role {
        SOURCE_IMAGE, NORMALIZED_IMAGE, SOURCE_AUDIO, PCM_AUDIO, ALIGNMENT, ASS, SRT,
        MP4, MEDIA_METADATA, QC_REPORT, MANIFEST
    }

    record Attempt(long channelId, long contentId, long runId, long itemId, int number) {
        public Attempt {
            if (channelId < 1 || contentId < 1 || runId < 1 || itemId < 1 || number < 1) {
                throw new IllegalArgumentException("Invalid media attempt");
            }
        }

        public String relativePath(Role role) {
            return "workspace/%d/%d/%d/attempts/%d/%d/%s".formatted(
                    channelId, contentId, runId, itemId, number, role.name());
        }
    }

    record FileIdentity(long bytes, String sha256) {
        public FileIdentity {
            if (bytes < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid file identity");
            }
        }
    }

    FileIdentity stage(Attempt attempt, Role role, InputStream source, long byteLimit) throws IOException;

    void commit(Attempt attempt, Role role, FileIdentity expected) throws IOException;

    FileChannel openVerified(Attempt attempt, Role role, FileIdentity expected) throws IOException;
}
