package com.aistudio.production.media.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaStore.Attempt;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.shared.db.LockRetryingTransactions;

@Component
public class MediaCleanup {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanup.class);
    private static final Pattern SCRATCH = Pattern.compile("r([1-9][0-9]{0,18})-i[1-9][0-9]{0,18}-a[1-9][0-9]{0,9}");
    private static final Duration RETENTION = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final LockRetryingTransactions transactions;

    public MediaCleanup(JdbcTemplate jdbc, LockRetryingTransactions transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Called with the exclusive process-runner lock held and preparation still healthy. */
    public void reap(Path scratch, Path storage) {
        Instant cutoff = Instant.now().minus(RETENTION);
        try {
            if (!safe(scratch) || !safe(storage)) {
                return;
            }
            try (var entries = Files.list(scratch)) {
                for (Path directory : entries.filter(path -> SCRATCH.matcher(path.getFileName().toString()).matches())
                        .limit(32).toList()) {
                    var name = SCRATCH.matcher(directory.getFileName().toString());
                    if (name.matches() && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                            && older(directory, cutoff) && safe(directory)) {
                        reapScratch(Long.parseLong(name.group(1)), directory, cutoff);
                    }
                }
            }
            Path workspace = storage.resolve("workspace");
            if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || !safe(workspace)) {
                return;
            }
            try (var entries = Files.find(workspace, 7, (path, attributes) -> attributes.isRegularFile()
                    && attributes.lastModifiedTime().toInstant().isBefore(cutoff))) {
                for (Path path : entries.limit(128).toList()) {
                    reapWorkspace(storage, path, cutoff);
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("Media cleanup left files unchanged where verification failed: {}", ex.getClass().getSimpleName());
        }
    }

    private void reapScratch(long requestId, Path directory, Instant cutoff) {
        transactions.run(() -> {
            if (leased(requestId)) {
                return;
            }
            try (var tree = Files.walk(directory)) {
                List<Path> paths = tree.limit(2049).toList();
                if (paths.size() > 2048) {
                    return;
                }
                for (Path path : paths) {
                    if (!safe(path) || !older(path, cutoff) || !(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
                        return;
                    }
                }
                for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private void reapWorkspace(Path storage, Path file, Instant cutoff) {
        Path relative = storage.relativize(file);
        if (relative.getNameCount() != 8 || !"attempts".equals(relative.getName(4).toString())) {
            return;
        }
        String name = relative.getFileName().toString();
        String role = name.endsWith(".partial") ? name.substring(0, name.length() - 8) : name;
        if (!java.util.Arrays.stream(Role.values()).anyMatch(value -> value.name().equals(role))) {
            return;
        }
        long itemId;
        int attemptNo;
        try {
            itemId = Long.parseLong(relative.getName(5).toString());
            attemptNo = Integer.parseInt(relative.getName(6).toString());
        } catch (NumberFormatException ex) {
            return;
        }
        if (itemId < 1 || attemptNo < 1) {
            return;
        }
        var owners = jdbc.query("""
                SELECT r.id, r.channel_id, r.content_id, r.run_id FROM production_media_item i
                JOIN production_request r ON r.id = i.production_request_id
                WHERE i.id = ? AND r.run_id IS NOT NULL
                """, (row, index) -> new Owner(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)), itemId);
        if (owners.isEmpty()) {
            return; // Unknown path ownership requires operator inspection.
        }
        Owner owner = owners.getFirst();
        String target = new Attempt(owner.channel(), owner.content(), owner.run(), itemId, attemptNo)
                .relativePath(Role.valueOf(role));
        if (!file.equals(storage.resolve(target + (name.endsWith(".partial") ? ".partial" : "")))) {
            return;
        }
        transactions.run(() -> {
            if (leased(owner.request())) {
                return;
            }
            Boolean referenced = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM production_media_asset
                        WHERE relative_path = ? AND state IN ('STAGED', 'FINAL'))
                    """, Boolean.class, target);
            try {
                if (!Boolean.TRUE.equals(referenced) && safe(file) && older(file, cutoff)) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private record Owner(long request, long channel, long content, long run) {}

    private boolean leased(long requestId) {
        return jdbc.queryForList("""
                SELECT lease_owner IS NOT NULL AND lease_until > UTC_TIMESTAMP(6)
                FROM production_request WHERE id = ? FOR UPDATE
                """, Boolean.class, requestId).contains(Boolean.TRUE);
    }

    private static boolean safe(Path path) throws IOException {
        return !Files.isSymbolicLink(path) && path.equals(path.toRealPath());
    }

    private static boolean older(Path path, Instant cutoff) throws IOException {
        return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
    }
}
