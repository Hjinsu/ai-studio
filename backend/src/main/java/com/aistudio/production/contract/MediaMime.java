package com.aistudio.production.contract;

import java.util.Set;

public final class MediaMime {
    private static final Set<String> IMAGE = Set.of("image/png", "image/jpeg");
    private static final Set<String> WAV = Set.of("audio/wav", "audio/wave");
    private static final Set<String> SUBRIP = Set.of("application/x-subrip", "text/plain");

    private MediaMime() {}

    public static boolean accepts(MediaStore.Role role, String mime) {
        if (mime == null) {
            return false;
        }
        return switch (role) {
            case SOURCE_IMAGE, NORMALIZED_IMAGE -> IMAGE.contains(mime);
            case SOURCE_AUDIO, PCM_AUDIO -> WAV.contains(mime);
            case SRT -> SUBRIP.contains(mime);
            case ASS -> "text/plain".equals(mime) || "text/x-ssa".equals(mime);
            case MP4 -> "video/mp4".equals(mime);
            case ALIGNMENT, MEDIA_METADATA, QC_REPORT, MANIFEST -> "application/json".equals(mime);
        };
    }
}
