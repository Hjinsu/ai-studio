package com.aistudio.production.media.product;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned FAKE/local fixture catalog (D01/D02). Each entry pins a file by SHA-256 and byte length and carries its
 * rights record. Images map to a scene image prompt by the prompt's SHA-256; narrations map to the exact scene
 * narration text and voice, so a script without a recorded narration is a missing-fixture precondition failure.
 * Files are resolved only below the catalog directory and are verified on every read.
 */
record FixtureCatalog(int schemaVersion, String catalogId, List<ImageFixture> images,
        List<NarrationFixture> narrations, List<AudioFixture> audio, List<FontFixture> fonts) {
    static final int SCHEMA = 1;
    static final long MAX_FILE = 16L * 1024 * 1024;

    record Rights(String source, String provider, String license, String evidenceRef, String verifiedBy,
            String verifiedAt, boolean commercialUse, boolean publish, boolean redistribution, String conditions) {
        boolean complete() {
            for (String value : List.of(nz(source), nz(provider), nz(license), nz(evidenceRef), nz(verifiedBy),
                    nz(verifiedAt))) {
                if (value.isBlank() || value.length() > 512) {
                    return false;
                }
            }
            try {
                Instant.parse(verifiedAt);
            } catch (DateTimeParseException ex) {
                return false;
            }
            return commercialUse && publish && conditions != null && conditions.length() <= 512;
        }

        private static String nz(String value) {
            return value == null ? "" : value;
        }
    }

    record ImageFixture(String id, String promptSha256, String file, String mimeType, String sha256, long bytes,
            Rights rights) {}

    record Cue(String text, long startSample, long endSample) {}

    record SegmentTiming(String segmentId, List<Cue> cues) {}

    record NarrationFixture(String id, String voiceId, String language, String text, String file, String sha256,
            long bytes, int sampleRate, String timingSource, List<SegmentTiming> segments, List<String> emphasis,
            Rights rights) {}

    /** kind: MUSIC, SFX_HOOK, SFX_TRANSITION or SFX_PUNCH. */
    record AudioFixture(String id, String kind, String file, String sha256, long bytes, double gainDb,
            Rights rights) {}

    record FontFixture(String id, String family, String sha256, Rights rights) {}

    FixtureCatalog {
        images = List.copyOf(images == null ? List.of() : images);
        narrations = List.copyOf(narrations == null ? List.of() : narrations);
        audio = List.copyOf(audio == null ? List.of() : audio);
        fonts = List.copyOf(fonts == null ? List.of() : fonts);
    }

    Optional<ImageFixture> image(String promptSha256) {
        return images.stream().filter(image -> image.promptSha256().equals(promptSha256)).findFirst();
    }

    Optional<NarrationFixture> narration(String voiceId, String language, String text) {
        return narrations.stream().filter(n -> n.voiceId().equals(voiceId) && n.language().equals(language)
                && n.text().equals(text)).findFirst();
    }

    Optional<AudioFixture> audio(String kind) {
        return audio.stream().filter(a -> a.kind().equals(kind)).findFirst();
    }

    Optional<FontFixture> font(String sha256) {
        return fonts.stream().filter(font -> font.sha256().equals(sha256)).findFirst();
    }

    /** Loads {@code <root>/<catalogId>/catalog.json} and validates its structure. */
    static Loaded load(String root, String catalogId, JsonMapper json) {
        if (root == null || root.isBlank()) {
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Fixture root is not configured");
        }
        if (catalogId == null || !catalogId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Invalid fixture catalog id");
        }
        try {
            Path base = Path.of(root).toRealPath();
            Path dir = base.resolve(catalogId);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().equals(dir)) {
                throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Fixture catalog directory is missing");
            }
            Path file = dir.resolve("catalog.json");
            byte[] bytes = readBounded(file, 1024 * 1024);
            FixtureCatalog catalog = json.readValue(bytes, FixtureCatalog.class);
            catalog.validate(catalogId);
            return new Loaded(catalog, dir, sha256(bytes));
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof MediaFailure failure) {
                throw failure;
            }
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Fixture catalog is unreadable");
        }
    }

    private void validate(String expectedId) {
        if (schemaVersion != SCHEMA || !expectedId.equals(catalogId)) {
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Fixture catalog version mismatch");
        }
        for (ImageFixture image : images) {
            requireFile(image.file(), image.sha256(), image.bytes(), image.rights());
            if (!image.promptSha256().matches("[0-9a-f]{64}")
                    || !("image/png".equals(image.mimeType()) || "image/jpeg".equals(image.mimeType()))) {
                throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Invalid image fixture " + image.id());
            }
        }
        for (NarrationFixture narration : narrations) {
            requireFile(narration.file(), narration.sha256(), narration.bytes(), narration.rights());
            if (narration.text() == null || narration.text().isBlank() || narration.segments() == null
                    || narration.segments().isEmpty() || narration.voiceId() == null || narration.language() == null) {
                throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Invalid narration fixture " + narration.id());
            }
        }
        for (AudioFixture item : audio) {
            requireFile(item.file(), item.sha256(), item.bytes(), item.rights());
            if (!List.of("MUSIC", "SFX_HOOK", "SFX_TRANSITION", "SFX_PUNCH").contains(item.kind())
                    || item.gainDb() < -40 || item.gainDb() > 0) {
                throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Invalid audio fixture " + item.id());
            }
        }
        for (FontFixture font : fonts) {
            if (font.sha256() == null || !font.sha256().matches("[0-9a-f]{64}") || font.rights() == null
                    || !font.rights().complete()) {
                throw MediaFailure.blocked("MEDIA_RIGHTS_INCOMPLETE", "Invalid font fixture " + font.id());
            }
        }
    }

    private static void requireFile(String file, String sha256, long bytes, Rights rights) {
        if (file == null || !file.matches("[a-z0-9_-]{1,32}(/[a-z0-9_.-]{1,64}){0,2}") || file.contains("..")
                || sha256 == null || !sha256.matches("[0-9a-f]{64}") || bytes < 1 || bytes > MAX_FILE) {
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Invalid fixture file reference");
        }
        if (rights == null || !rights.complete()) {
            throw MediaFailure.blocked("MEDIA_RIGHTS_INCOMPLETE", "Fixture rights are incomplete: " + file);
        }
    }

    record Loaded(FixtureCatalog catalog, Path directory, String catalogSha256) {
        /** Reads a pinned fixture file and verifies its length and SHA-256. */
        byte[] read(String file, String sha256, long bytes) {
            try {
                Path path = directory.resolve(file).normalize();
                if (!path.startsWith(directory) || !path.toRealPath().equals(path)) {
                    throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Fixture path escapes the catalog");
                }
                byte[] content = readBounded(path, MAX_FILE);
                if (content.length != bytes || !sha256(content).equals(sha256)) {
                    throw MediaFailure.blocked("MEDIA_FIXTURE_INTEGRITY", "Fixture hash mismatch: " + file);
                }
                return content;
            } catch (IOException ex) {
                throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Fixture file is missing: " + file);
            }
        }
    }

    static byte[] readBounded(Path path, long limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) {
            throw new IOException("Fixture file is missing or too large");
        }
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = in.readNBytes((int) limit + 1);
            if (content.length > limit) {
                throw new IOException("Fixture file is too large");
            }
            return content;
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Rights JSON for a stored payload: the catalog rights plus source hash and derivation parents. */
    static Map<String, Object> rightsRecord(Rights rights, String sourceSha256, List<Map<String, Object>> parents) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", rights.source());
        out.put("provider", rights.provider());
        out.put("license", rights.license());
        out.put("evidenceRef", rights.evidenceRef());
        out.put("verifiedBy", rights.verifiedBy());
        out.put("verifiedAt", rights.verifiedAt());
        out.put("commercialUse", rights.commercialUse());
        out.put("publish", rights.publish());
        out.put("redistribution", rights.redistribution());
        out.put("conditions", rights.conditions());
        out.put("sourceSha256", sourceSha256);
        out.put("parents", parents);
        return out;
    }

    /** Rights of a derived local artifact (subtitle, render, report) made by this application from rights-checked inputs. */
    static Rights derived(String verifiedAt) {
        return new Rights("Generated by AI Studio from rights-checked inputs listed in lineage", "AI_STUDIO_LOCAL",
                "Derived work; inherits the most restrictive input conditions", "lineage", "ai-studio-media-product",
                verifiedAt, true, true, false, "See input rights in the media manifest");
    }
}
