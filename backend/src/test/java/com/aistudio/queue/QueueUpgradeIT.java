package com.aistudio.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
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
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.budget.BudgetReservationService;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.budget.ExchangeRateService;
import com.aistudio.channel.ChannelService;
import com.aistudio.content.ContentFixtures;
import com.aistudio.content.ContentService;
import com.aistudio.security.AdminAccountService;
import com.aistudio.topic.TopicService;

/**
 * 기존 V6 DB → V7 업그레이드(DB-01). 앱을 V6까지만 migrate해 T02·T03-B 서비스로 실제 데이터(채널·예산·예약·원장, topic·
 * content·revision 2, 종료 run 이력과 활성 run)를 만든 뒤 V7을 적용하고, (1) V1~V6 이력·체크섬, (2) 기존 테이블
 * 데이터, (3) 기존 테이블 제약·트리거가 그대로인지(V7은 기존 테이블을 바꾸지 않는다), (4) 기존 활성 run에 job·attempt를
 * 연결할 수 있는지 확인한다. 격리 테스트 DB 전용.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class QueueUpgradeIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static final List<String> EXISTING = List.of("admin_user", "admin_bootstrap", "audit_event", "channel",
            "channel_config", "budget_control", "exchange_rate", "budget_policy", "budget_policy_revision",
            "budget_bucket", "budget_reservation", "budget_reservation_bucket", "cost_log", "topic", "content",
            "content_revision", "content_run", "queue_command_log");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
        registry.add("spring.flyway.target", () -> "6");
    }

    @Autowired
    private AdminAccountService accounts;
    @Autowired
    private ChannelService channels;
    @Autowired
    private BudgetPolicyService policies;
    @Autowired
    private BudgetControlService control;
    @Autowired
    private ExchangeRateService rates;
    @Autowired
    private BudgetReservationService reservations;
    @Autowired
    private TopicService topics;
    @Autowired
    private ContentService contents;
    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Long> checksums() {
        Map<String, Long> result = new TreeMap<>();
        jdbc.query("CHECKSUM TABLE " + String.join(", ", EXISTING) + " EXTENDED", rs -> {
            String table = rs.getString(1);
            result.put(table.substring(table.indexOf('.') + 1), rs.getLong(2));
        });
        return result;
    }

    private TreeSet<String> structure() {
        String in = "'" + String.join("','", EXISTING) + "'";
        TreeSet<String> items = new TreeSet<>();
        items.addAll(jdbc.queryForList("SELECT CONCAT('C ', table_name, '.', constraint_name, ' ', constraint_type)"
                + " FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name IN (" + in
                + ")", String.class));
        items.addAll(jdbc.queryForList("SELECT CONCAT('K ', table_name, '.', constraint_name, '.', column_name, '#',"
                + " ordinal_position, '->', COALESCE(referenced_table_name, ''), '.', COALESCE(referenced_column_name, ''))"
                + " FROM information_schema.key_column_usage WHERE table_schema = DATABASE() AND table_name IN (" + in
                + ")", String.class));
        items.addAll(jdbc.queryForList("SELECT CONCAT('X ', table_name, '.', constraint_name, ' ', check_clause)"
                + " FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND table_name IN ("
                + in + ")", String.class));
        items.addAll(jdbc.queryForList("SELECT CONCAT('T ', event_object_table, '.', trigger_name, ' ', action_timing,"
                + " ' ', event_manipulation, ' ', action_statement) FROM information_schema.triggers"
                + " WHERE trigger_schema = DATABASE() AND event_object_table IN (" + in + ")", String.class));
        items.addAll(jdbc.queryForList("SELECT CONCAT('I ', table_name, '.', index_name, '.', column_name, '#', seq_in_index)"
                + " FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name IN (" + in + ")",
                String.class));
        return items;
    }

    private List<String> history() {
        return jdbc.queryForList("SELECT CONCAT(version, ':', checksum) FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank", String.class);
    }

    @Test
    void v6DatabaseWithT02AndT03bDataUpgradesToV7WithoutChangingExistingTables() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = 'automation_job'", Integer.class)).isZero();

        // 1) V6 상태에서 실제 서비스로 데이터 생성
        Actor admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        long channelId = channels.create("queue-upgrade", "en", "UTC", null, admin).channel().id();
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("100000"), "UTC", null, admin);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000000"), "UTC", null, admin);
        rates.create("USD", new BigDecimal("1400"), null, "upgrade", admin);
        control.setSpending(true, "upgrade", control.get().version(), admin);
        reservations.reserve(new ReserveCommand("queue-upgrade-usd-1", channelId, "img", "USD", BigDecimal.ONE));
        control.setSpending(false, "upgrade done", control.get().version(), admin);
        long topicId = topics.create(new TopicService.CreateTopic("queue-upgrade-topic", channelId, "Upgrade", null),
                admin).topic().id();
        ContentService.ContentView view = contents.create(new ContentService.CreateContent("queue-upgrade-content",
                channelId, topicId, "SHORTS", "Upgrade", "Brief", null, 50, null, null, null), admin).view();
        long contentId = view.content().id();
        contents.createRevision(new ContentService.CreateRevision("queue-upgrade-revision", contentId, 1, "Upgrade 2",
                "Brief", "en", 50, null, null, null), admin);
        long revisionId = contents.get(contentId).currentRevision().id();
        ContentFixtures fixtures = new ContentFixtures(jdbc);
        long done = fixtures.activate(contentId, revisionId, 1);
        fixtures.finish(contentId, done, "SUCCEEDED");
        long active = fixtures.activate(contentId, revisionId, 2);

        Map<String, Long> dataBefore = checksums();
        TreeSet<String> structureBefore = structure();
        List<String> historyBefore = history();

        // 2) migration 계정으로 V7 적용
        Flyway.configure().dataSource(DB.dataSource()).locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate();

        // 3) 이력·데이터·기존 테이블 구조 보존(V7은 기존 테이블에 제약을 추가하지 않는다)
        List<String> historyAfter = history();
        assertThat(historyAfter).hasSize(7).startsWith(historyBefore.toArray(String[]::new));
        assertThat(historyAfter.get(6)).startsWith("7:");
        assertThat(checksums()).isEqualTo(dataBefore);
        TreeSet<String> structureAfter = structure();
        List<String> added = new ArrayList<>(structureAfter);
        added.removeAll(structureBefore);
        List<String> removed = new ArrayList<>(structureBefore);
        removed.removeAll(structureAfter);
        assertThat(added).isEmpty();
        assertThat(removed).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM queue_control", Integer.class)).isEqualTo(1);
        assertThat(control.runConsistencyCheck(admin).issues()).isEmpty();

        // 4) 업그레이드 전부터 있던 활성 run에 job·attempt 연결
        QueueFixtures queue = new QueueFixtures(jdbc);
        long job = queue.job(active, contentId, channelId, "research", false);
        queue.ready(job);
        QueueFixtures.Claim claim = queue.claim(job, queue.instance());
        queue.event(active, job, claim.attemptId(), "CLAIMED");
        assertThat(jdbc.queryForObject("SELECT status FROM automation_job WHERE id = ?", String.class, job))
                .isEqualTo("RUNNING");
    }
}
