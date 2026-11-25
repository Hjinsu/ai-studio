package com.aistudio.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.topic.TopicNormalizer;
import com.aistudio.topic.TopicService;

/**
 * FV-03 동시성: 두 DB 커넥션 + barrier/latch로 B 범위 UNIQUE·복합 FK·channel/content 잠금 경합을 검증한다.
 * 한쪽은 원시 JDBC 트랜잭션으로 잠금을 쥔 채 멈추고, 다른 쪽(서비스)이 실제로 잠금 대기(innodb_trx LOCK WAIT)에
 * 들어간 것을 확인한 뒤 커밋해 결과를 판정한다(수면 시간 추정 대신 DB 상태로 판정).
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class ContentConcurrencyIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private ChannelService channels;

    @Autowired
    private TopicService topics;

    @Autowired
    private ContentService contents;

    @Autowired
    private JdbcTemplate jdbc;

    private Actor admin;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- helpers
    private static Connection tx() throws SQLException {
        Connection connection = DB.dataSource().getConnection();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (Statement s = connection.createStatement()) {
            s.execute("SET time_zone = '+00:00'");
            s.execute("SET SESSION innodb_lock_wait_timeout = 20");
        }
        return connection;
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static long insertKey(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** 다른 세션이 잠금 대기에 들어갈 때까지 기다린다(최대 10초). 대기하지 않으면 실패한다. */
    private void awaitLockWait(Future<?> waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'", Integer.class);
            if (waiting != null && waiting > 0) {
                assertThat(waiter.isDone()).isFalse();
                return;
            }
            if (waiter.isDone()) {
                break;
            }
            // innodb_trx는 마지막 조회 후 100ms 이상 지나야 갱신되는 캐시이므로 그보다 길게 기다린다
            Thread.sleep(250);
        }
        String trx = String.valueOf(jdbc.queryForList("SELECT trx_state, trx_query FROM information_schema.innodb_trx"));
        String processes = String.valueOf(jdbc.queryForList(
                "SELECT state, info FROM information_schema.processlist WHERE command <> 'Sleep'"));
        throw new AssertionError("expected the second session to wait for a lock (done=" + waiter.isDone() + ") trx="
                + trx + " processes=" + processes);
    }

    private static ErrorCode errorOf(Future<?> future) throws InterruptedException {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException api) {
                return api.errorCode();
            }
            throw new AssertionError("unexpected failure", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("timed out", e);
        }
    }

    private long channel() {
        return channels.create("cc-" + UUID.randomUUID(), "en", "UTC", null, admin).channel().id();
    }

    private ContentService.ContentView newContent(long channelId) {
        long topicId = topics.create(new TopicService.CreateTopic(ApiTestSupport.uniqueKey("t"), channelId,
                "Topic " + UUID.randomUUID(), null), admin).topic().id();
        return contents.create(new ContentService.CreateContent(ApiTestSupport.uniqueKey("c"), channelId, topicId,
                "SHORTS", "Title", "Brief", null, 50, null, null, null), admin).view();
    }

    private ContentService.CreateRevision revision(long contentId, int base, String title, String key) {
        return new ContentService.CreateRevision(key, contentId, base, title, "Brief", "en", 50, null, null, null);
    }

    // ---------------------------------------------------------------- 동일 Topic 동시 등록
    @Test
    void sameTopicConcurrentRegistrationWaitsOnTheUniqueKeyAndReturnsDuplicate() throws Exception {
        long channelId = channel();
        String hash = TopicNormalizer.hash(TopicNormalizer.VERSION, TopicNormalizer.normalize("Race Topic"));
        try (Connection a = tx()) {
            exec(a, "SELECT id FROM channel WHERE id = ? LOCK IN SHARE MODE", channelId);
            long rawId = insertKey(a, """
                    INSERT INTO topic (channel_id, title, normalized_title, normalized_hash, normalization_version,
                           created_by, created_at) VALUES (?, 'Race Topic', 'race topic', ?, 1, ?, UTC_TIMESTAMP(6))""",
                    channelId, hash, admin.userId());
            Future<?> b = executor.submit(() -> topics.create(new TopicService.CreateTopic(
                    ApiTestSupport.uniqueKey("race"), channelId, "RACE  topic", null), admin));
            awaitLockWait(b); // B는 비잠금 조회로 A의 미커밋 행을 못 보고 UNIQUE 검사에서 대기한다
            a.commit();
            assertThat(errorOf(b)).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
            try {
                b.get();
            } catch (ExecutionException e) {
                assertThat(((ApiException) e.getCause()).details().get(0).reason())
                        .isEqualTo("DUPLICATE_TOPIC:" + rawId);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class, channelId))
                .isEqualTo(1);
    }

    @Test
    void concurrentTopicRequestsWithBarrierCreateExactlyOne() throws Exception {
        for (int round = 0; round < 5; round++) {
            long channelId = channel();
            String sharedKey = ApiTestSupport.uniqueKey("same");
            CyclicBarrier barrier = new CyclicBarrier(4);
            List<Future<TopicService.TopicResult>> results = new ArrayList<>();
            // 2개는 다른 키(중복 소재), 2개는 같은 키·같은 본문(재생)
            for (int i = 0; i < 4; i++) {
                String requestKey = i < 2 ? ApiTestSupport.uniqueKey("diff") : sharedKey;
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return topics.create(new TopicService.CreateTopic(requestKey, channelId, "Barrier Topic", null),
                            admin);
                }));
            }
            int created = 0;
            int replayed = 0;
            int duplicates = 0;
            for (Future<TopicService.TopicResult> result : results) {
                try {
                    TopicService.TopicResult r = result.get(30, TimeUnit.SECONDS);
                    if (r.replayed()) {
                        replayed++;
                    } else {
                        created++;
                    }
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) e.getCause()).errorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
                    duplicates++;
                }
            }
            assertThat(created).as("round %d", round).isEqualTo(1);
            assertThat(created + replayed + duplicates).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class,
                    channelId)).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- Revision 동시 변경
    @Test
    void revisionWaitsOnContentLockAndRejectsStaleBaseRevision() throws Exception {
        long channelId = channel();
        ContentService.ContentView view = newContent(channelId);
        long contentId = view.content().id();
        long configId = view.currentRevision().channelConfigId();
        try (Connection a = tx()) {
            // Q0 순서: channel S → content X
            exec(a, "SELECT id FROM channel WHERE id = ? LOCK IN SHARE MODE", channelId);
            exec(a, "SELECT id FROM content WHERE id = ? FOR UPDATE", contentId);
            long rev2 = insertKey(a, """
                    INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                           brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                    VALUES (?, ?, 2, ?, 'Raw', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""", contentId, channelId,
                    configId, ContentFixtures.hash("raw-" + UUID.randomUUID()), admin.userId());
            update(a, "UPDATE content SET current_revision_id = ?, title = 'Raw', version = version + 1 WHERE id = ?",
                    rev2, contentId);
            Future<?> b = executor.submit(() -> contents.createRevision(
                    revision(contentId, 1, "Service", ApiTestSupport.uniqueKey("r")), admin));
            awaitLockWait(b);
            a.commit();
            assertThat(errorOf(b)).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        }
        assertThat(jdbc.queryForObject("SELECT MAX(revision_no) FROM content_revision WHERE content_id = ?",
                Integer.class, contentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT title FROM content WHERE id = ?", String.class, contentId))
                .isEqualTo("Raw");
    }

    @Test
    void concurrentRevisionsWithTheSameBaseProduceOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            long contentId = newContent(channel()).content().id();
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<ContentService.RevisionResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String title = "Rev " + i;
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return contents.createRevision(revision(contentId, 1, title, ApiTestSupport.uniqueKey("r")),
                            admin);
                }));
            }
            int ok = 0;
            int conflicts = 0;
            for (Future<ContentService.RevisionResult> result : results) {
                ErrorCode error = errorOf(result);
                if (error == null) {
                    ok++;
                } else {
                    assertThat(error).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    conflicts++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            // revision_no 연속, 제목 복제본 = 현재 revision
            assertThat(jdbc.queryForList("SELECT revision_no FROM content_revision WHERE content_id = ? ORDER BY 1",
                    Integer.class, contentId)).containsExactly(1, 2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM content c JOIN content_revision r ON r.id = c.current_revision_id
                     WHERE c.id = ? AND BINARY c.title = BINARY r.working_title AND r.revision_no = 2""",
                    Integer.class, contentId)).isEqualTo(1);
        }
    }

    @Test
    void sameKeyRevisionRaceReplaysInsteadOfConflict() throws Exception {
        long contentId = newContent(channel()).content().id();
        String key = ApiTestSupport.uniqueKey("same-rev");
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<ContentService.RevisionResult>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return contents.createRevision(revision(contentId, 1, "Same", key), admin);
            }));
        }
        ContentService.RevisionResult first = results.get(0).get(30, TimeUnit.SECONDS);
        ContentService.RevisionResult second = results.get(1).get(30, TimeUnit.SECONDS);
        assertThat(first.revision().id()).isEqualTo(second.revision().id());
        assertThat(List.of(first.replayed(), second.replayed())).containsExactlyInAnyOrder(true, false);
    }

    // ---------------------------------------------------------------- 활성 Run 포인터 경쟁
    @Test
    void activeRunPointerRaceLeavesExactlyOneActiveRun() throws Exception {
        for (int round = 0; round < 5; round++) {
            ContentService.ContentView view = newContent(channel());
            long contentId = view.content().id();
            long revisionId = view.currentRevision().id();
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int runNo = i + 1;
                results.add(executor.submit((Callable<Boolean>) () -> {
                    try (Connection c = tx()) {
                        barrier.await(10, TimeUnit.SECONDS);
                        long runId = insertKey(c, """
                                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version,
                                       execution_mode, status, requested_by, created_at)
                                VALUES (?, ?, ?, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))""", contentId,
                                revisionId, runNo, admin.userId());
                        int changed = update(c, """
                                UPDATE content SET status = 'PRODUCING', active_run_id = ?, version = version + 1
                                 WHERE id = ? AND status = 'DRAFT' AND active_run_id IS NULL""", runId, contentId);
                        if (changed == 1) {
                            c.commit();
                            return true;
                        }
                        c.rollback();
                        return false;
                    }
                }));
            }
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_run WHERE content_id = ?", Integer.class,
                    contentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM content c JOIN content_run r ON r.id = c.active_run_id AND r.content_id = c.id
                     WHERE c.id = ? AND c.status = 'PRODUCING' AND r.status = 'QUEUED'""", Integer.class, contentId))
                    .isEqualTo(1);
        }
    }

    @Test
    void sameRunNumberRaceIsSerializedByTheUniqueKey() throws Exception {
        ContentService.ContentView view = newContent(channel());
        long contentId = view.content().id();
        long revisionId = view.currentRevision().id();
        String insert = """
                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode,
                       status, requested_by, created_at)
                VALUES (?, ?, 1, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))""";
        try (Connection a = tx(); Connection b = tx()) {
            insertKey(a, insert, contentId, revisionId, admin.userId());
            Future<?> second = executor.submit(() -> {
                insertKey(b, insert, contentId, revisionId, admin.userId());
                return null;
            });
            awaitLockWait(second);
            a.commit();
            assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS)).hasStackTraceContaining("uk_content_run_no");
            b.rollback();
        }
    }

    // ---------------------------------------------------------------- 채널 보관 ↔ 콘텐츠 상태 변경(FD-16)
    @Test
    void channelArchiveWaitsForRunActivationAndThenRefuses() throws Exception {
        long channelId = channel();
        ContentService.ContentView view = newContent(channelId);
        long contentId = view.content().id();
        long version = channels.get(channelId).channel().version();
        try (Connection a = tx()) {
            // run 생성 경로(Q0)의 channel S → content X를 쥔 채 활성 포인터 설정
            exec(a, "SELECT id FROM channel WHERE id = ? LOCK IN SHARE MODE", channelId);
            exec(a, "SELECT id FROM content WHERE id = ? FOR UPDATE", contentId);
            long runId = insertKey(a, """
                    INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode,
                           status, requested_by, created_at)
                    VALUES (?, ?, 1, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))""", contentId,
                    view.currentRevision().id(), admin.userId());
            update(a, "UPDATE content SET status = 'PRODUCING', active_run_id = ?, version = version + 1 WHERE id = ?",
                    runId, contentId);
            Future<?> archive = executor.submit(() -> channels.archive(channelId, version, admin));
            awaitLockWait(archive); // budget_control X는 얻고 channel X에서 대기
            a.commit();
            assertThat(errorOf(archive)).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }
        assertThat(channels.get(channelId).channel().status()).isEqualTo("ACTIVE");
    }

    @Test
    void contentWritesWaitForChannelArchiveAndThenRefuse() throws Exception {
        long channelId = channel();
        ContentService.ContentView view = newContent(channelId);
        long topicId = view.content().topicId();
        long contentId = view.content().id();
        try (Connection b = tx()) {
            // 채널 보관 경로(CTRL): budget_control X → channel X
            exec(b, "SELECT id FROM budget_control WHERE id = 1 FOR UPDATE");
            exec(b, "SELECT id FROM channel WHERE id = ? FOR UPDATE", channelId);
            update(b, "UPDATE channel SET status = 'ARCHIVED', archived_at = UTC_TIMESTAMP(6), version = version + 1"
                    + " WHERE id = ?", channelId);
            Future<?> create = executor.submit(() -> contents.create(new ContentService.CreateContent(
                    ApiTestSupport.uniqueKey("c"), channelId, topicId, "SHORTS", "T", "B", null, 50, null, null, null),
                    admin));
            Future<?> revise = executor.submit(() -> contents.createRevision(
                    revision(contentId, 1, "Blocked", ApiTestSupport.uniqueKey("r")), admin));
            awaitLockWait(create);
            awaitLockWait(revise);
            b.commit();
            assertThat(errorOf(create)).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET);
            assertThat(errorOf(revise)).isEqualTo(ErrorCode.PREREQUISITE_NOT_MET);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channelId))
                .isEqualTo(1);
    }

    @Test
    void channelPauseAndBudgetControlSerializeWithExclusiveControlLock() throws Exception {
        long channelId = channel();
        long version = channels.get(channelId).channel().version();
        try (Connection reservation = tx()) {
            // 예약·정산 경로의 budget_control S를 쥔 상태
            exec(reservation, "SELECT id FROM budget_control WHERE id = 1 LOCK IN SHARE MODE");
            Future<?> pause = executor.submit(() -> channels.pause(channelId, version, admin));
            awaitLockWait(pause); // 채널 상태 변경은 budget_control X(FD-02 CTRL)
            reservation.commit();
            assertThat(errorOf(pause)).isNull();
        }
        assertThat(channels.get(channelId).channel().status()).isEqualTo("PAUSED");
    }

    // ---------------------------------------------------------------- 잠금 순서 혼합 부하
    @Test
    void mixedWritersFollowingTheLockOrderDoNotDeadlock() throws Exception {
        long channelId = channel();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(newContent(channelId).content().id());
        }
        CyclicBarrier barrier = new CyclicBarrier(4);
        List<Future<Integer>> workers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int worker = w;
            workers.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                int failures = 0;
                for (int i = 0; i < 10; i++) {
                    long contentId = ids.get((worker + i) % ids.size());
                    int base = jdbc.queryForObject("""
                            SELECT r.revision_no FROM content c JOIN content_revision r ON r.id = c.current_revision_id
                             WHERE c.id = ?""", Integer.class, contentId);
                    try {
                        contents.createRevision(revision(contentId, base, "W" + worker + "-" + i,
                                ApiTestSupport.uniqueKey("m")), admin);
                    } catch (ApiException e) {
                        if (e.errorCode() != ErrorCode.RESOURCE_CONFLICT) {
                            throw e;
                        }
                        failures++;
                    }
                    if (worker == 0) {
                        topics.create(new TopicService.CreateTopic(ApiTestSupport.uniqueKey("mt"), channelId,
                                "Mixed " + i, null), admin);
                    }
                }
                return failures;
            }));
        }
        for (Future<Integer> worker : workers) {
            worker.get(60, TimeUnit.SECONDS); // 교착·잠금 시간초과가 남으면 여기서 예외
        }
        for (long contentId : ids) {
            List<Integer> numbers = jdbc.queryForList(
                    "SELECT revision_no FROM content_revision WHERE content_id = ? ORDER BY revision_no", Integer.class,
                    contentId);
            for (int i = 0; i < numbers.size(); i++) {
                assertThat(numbers.get(i)).isEqualTo(i + 1);
            }
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM content c JOIN content_revision r ON r.id = c.current_revision_id
                     WHERE c.id = ? AND r.revision_no = ? AND BINARY c.title = BINARY r.working_title""",
                    Integer.class, contentId, numbers.size())).isEqualTo(1);
        }
    }
}
