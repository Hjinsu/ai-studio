package com.aistudio.production.media.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaStore.Attempt;
import com.aistudio.production.contract.MediaStore.FileIdentity;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.MediaProcessRunner.Scratch;
import com.aistudio.shared.db.LockRetryingTransactions;

@Component
public class MediaCleanup {
    private static final Logger log = LoggerFactory.getLogger(MediaCleanup.class);
    private static final Pattern SCRATCH = Pattern.compile("r([1-9][0-9]{0,18})-i([1-9][0-9]{0,18})-a([1-9][0-9]{0,9})");
    private static final Duration RETENTION = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final LockRetryingTransactions transactions;

    public MediaCleanup(JdbcTemplate jdbc, LockRetryingTransactions transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Called under execution and admission locks, with healthy process protection. */
    public void reap(Path scratch, Path storage, Predicate<Path> reserved) {
        Instant cutoff = Instant.now().minus(RETENTION);
        try {
            if (!safe(scratch) || !safe(storage)) {
                return;
            }
            try (var entries = Files.list(scratch)) {
                for (Path directory : entries.filter(path -> SCRATCH.matcher(path.getFileName().toString()).matches())
                        .filter(path -> !reserved.test(path)).toList()) {
                    var name = SCRATCH.matcher(directory.getFileName().toString());
                    if (name.matches() && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                            && older(directory, cutoff) && safe(directory)) {
                        try {
                            var handle = new Scratch(Long.parseLong(name.group(1)), Long.parseLong(name.group(2)),
                                    Integer.parseInt(name.group(3)));
                            reapAbandoned(handle, directory, storage, cutoff);
                        } catch (RuntimeException ex) {
                            log.warn("Retained abandoned media scratch: {}", ex.getClass().getSimpleName());
                        }
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

    private void reapAbandoned(Scratch handle, Path directory, Path storage, Instant cutoff) {
        Request before = request(handle.requestId(), false);
        if (before == null || before.leased()) {
            return;
        }
        Item prior = item(handle);
        Applied verified = verifyApplied(handle, before, prior, storage);
        transactions.run(() -> {
            Request request = request(handle.requestId(), true);
            if (request == null || request.leased()) {
                return;
            }
            Item item = item(handle);
            if (item != null && item.state().equals("APPLIED")) {
                if ((!item.effect() && handle.attempt() > item.attempt())
                        || !stillApplied(handle, request, item, verified)) {
                    return;
                }
            } else if (item != null && (!request.terminal()
                    || !(Set.of("PENDING", "FAILED", "CANCELLED").contains(item.state())
                            || (!item.effect() && item.state().equals("ACTIVE"))))) {
                return;
            }
            deleteTree(directory, cutoff);
        });
    }

    /** The caller has finished using this directory and has excluded concurrent writers. */
    public boolean retireApplied(Scratch handle, Path directory, Path storage) {
        try {
            Request before = request(handle.requestId(), false);
            Item prior = item(handle);
            if (before == null || prior == null || prior.epoch() != before.epoch()
                    || (!prior.effect() && prior.attempt() != handle.attempt())) {
                return false;
            }
            Applied verified = verifyApplied(handle, before, prior, storage);
            if (verified == null) {
                return false;
            }
            return transactions.execute(() -> {
                Request request = request(handle.requestId(), true);
                Item item = item(handle);
                if (!stillApplied(handle, request, item, verified)) {
                    return false;
                }
                deleteTree(directory, null);
                return true;
            });
        } catch (RuntimeException ex) {
            log.warn("Retained media scratch after verification failure: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private Request request(long id, boolean locked) {
        return jdbc.query("""
                SELECT channel_id, content_id, run_id, lease_epoch,
                    lease_owner IS NOT NULL AND lease_until > UTC_TIMESTAMP(6),
                    status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                FROM production_request WHERE id = ?
                """ + (locked ? "FOR UPDATE" : ""), (row, index) -> new Request(row.getLong(1), row.getLong(2), row.getLong(3),
                        row.getLong(4), row.getBoolean(5), row.getBoolean(6)), id).stream().findFirst().orElse(null);
    }

    private Item item(Scratch handle) {
        return jdbc.query("""
                SELECT kind, state, attempt_no, lease_epoch, execution_proof_sha256 IS NOT NULL
                FROM production_media_item WHERE id = ? AND production_request_id = ?
                """, (row, index) -> new Item(row.getString(1), row.getString(2), row.getInt(3),
                        row.getLong(4), row.getBoolean(5)), handle.itemId(), handle.requestId())
                .stream().findFirst().orElse(null);
    }

    private Applied verifyApplied(Scratch handle, Request request, Item item, Path storage) {
        if (item == null || !item.state().equals("APPLIED")) {
            return null;
        }
        Set<Role> required = switch (item.kind()) {
            case "IMAGE" -> Set.of(Role.SOURCE_IMAGE, Role.NORMALIZED_IMAGE);
            case "TTS" -> Set.of(Role.SOURCE_AUDIO, Role.PCM_AUDIO, Role.ALIGNMENT);
            case "SUBTITLE" -> Set.of(Role.ASS, Role.SRT);
            case "RENDER" -> Set.of(Role.MP4);
            case "METADATA" -> Set.of(Role.MEDIA_METADATA);
            case "QC" -> Set.of(Role.QC_REPORT, Role.MANIFEST);
            default -> Set.of();
        };
        if (required.isEmpty() || (item.effect() && !item.proven())) {
            return null;
        }
        var assets = assets(handle, item);
        var attempt = new Attempt(request.channel(), request.content(), request.run(), handle.itemId(), item.attempt());
        Set<Role> verified = new HashSet<>();
        try {
            var store = new WorkspaceMediaStore(storage);
            for (Asset asset : assets) {
                if (!required.contains(asset.role()) || !attempt.relativePath(asset.role()).equals(asset.path())) {
                    return null;
                }
                try (var file = store.openVerified(attempt, asset.role(), asset.identity())) {
                    verified.add(asset.role());
                }
            }
            return verified.equals(required) ? new Applied(request, item, assets) : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private boolean stillApplied(Scratch handle, Request request, Item item, Applied verified) {
        return verified != null && verified.request().equals(request) && verified.item().equals(item)
                && verified.assets().equals(assets(handle, item));
    }

    private List<Asset> assets(Scratch handle, Item item) {
        return jdbc.query("""
                SELECT role, relative_path, byte_length, sha256 FROM production_media_asset
                WHERE item_id = ? AND attempt_no = ? AND (state = 'FINAL' OR (? AND state = 'STAGED'))
                ORDER BY role
                """, (row, index) -> new Asset(Role.valueOf(row.getString(1)), row.getString(2),
                        new FileIdentity(row.getLong(3), row.getString(4))), handle.itemId(), item.attempt(),
                !item.effect());
    }

    private static void deleteTree(Path directory, Instant cutoff) {
        try (var tree = Files.walk(directory)) {
            List<Path> paths = tree.limit(2049).toList();
            if (paths.size() > 2048) {
                return;
            }
            for (Path path : paths) {
                if (!safe(path) || (cutoff != null && !older(path, cutoff))
                        || !(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
                    return;
                }
            }
            for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not remove media scratch: {}", ex.getClass().getSimpleName());
        }
    }

    private record Request(long channel, long content, long run, long epoch, boolean leased, boolean terminal) {}
    private record Item(String kind, String state, int attempt, long epoch, boolean proven) {
        boolean effect() {
            return kind.equals("TTS") || kind.equals("IMAGE");
        }
    }
    private record Asset(Role role, String path, FileIdentity identity) {}
    private record Applied(Request request, Item item, List<Asset> assets) {}

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
