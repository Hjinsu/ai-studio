package com.aistudio.content;

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
import com.aistudio.budget.BudgetReservationService.EventCommand;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.budget.ExchangeRateService;
import com.aistudio.channel.ChannelService;
import com.aistudio.channel.ChannelSettings;
import com.aistudio.security.AdminAccountService;
import com.aistudio.topic.TopicService;

/**
 * 기존 V5 DB → V6 업그레이드(DB-01, FV-02). 앱을 V5까지만 migrate해 T02 서비스로 실제 데이터(채널·설정·정책·환율·예약·
 * 원장·감사)를 만든 뒤 V6을 적용하고, (1) V1~V5 이력·체크섬, (2) 기존 테이블 데이터(CHECKSUM TABLE), (3) 기존 제약·
 * 트리거가 보존되며 추가된 것은 channel_config UNIQUE(channel_id,id) 하나뿐인지, (4) 업그레이드된 기존 채널·설정으로
 * 콘텐츠를 만들 수 있는지 확인한다. 격리 테스트 DB 전용.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class ContentUpgradeIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static final List<String> T02_TABLES = List.of("admin_user", "admin_bootstrap", "audit_event", "channel",
            "channel_config", "budget_control", "exchange_rate", "budget_policy", "budget_policy_revision",
            "budget_bucket", "budget_reservation", "budget_reservation_bucket", "cost_log");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
        registry.add("spring.flyway.target", () -> "5");
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
        jdbc.query("CHECKSUM TABLE " + String.join(", ", T02_TABLES) + " EXTENDED",
                rs -> {
                    String table = rs.getString(1);
                    result.put(table.substring(table.indexOf('.') + 1), rs.getLong(2));
                });
        return result;
    }

    private TreeSet<String> structure() {
        String in = "'" + String.join("','", T02_TABLES) + "'";
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
        return items;
    }

    private List<String> history() {
        return jdbc.queryForList("SELECT CONCAT(version, ':', checksum) FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank", String.class);
    }

    @Test
    void populatedV5DatabaseUpgradesThroughV6AndV9WithoutChangingExistingData() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = 'content'", Integer.class)).isZero();

        // 1) V5 상태에서 T02 서비스로 실제 데이터 생성
        Actor admin = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        long channelId = channels.create("upgrade", "ko", "Asia/Seoul", null, admin).channel().id();
        channels.createConfigRevision(channelId, 1, ChannelSettings.defaults(), "rev2", admin);
        channels.pause(channelId, channels.get(channelId).channel().version(), admin);
        channels.resume(channelId, channels.get(channelId).channel().version(), admin);
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("100000"), "UTC", null, admin);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000000"), "UTC", null, admin);
        policies.create("CHANNEL", channelId, null, "MONTH", new BigDecimal("500000"), "UTC", null, admin);
        rates.create("USD", new BigDecimal("1400"), null, "upgrade", admin);
        control.setSpending(true, "upgrade", control.get().version(), admin);
        long usd = reservations.reserve(new ReserveCommand("upgrade-usd-1", channelId, "img", "USD", BigDecimal.ONE))
                .reservation().id();
        reservations.confirm(new EventCommand(usd, "upgrade-evt-1", new BigDecimal("0.75"), null, null, null, null,
                null, Actor.system()));
        reservations.reserve(new ReserveCommand("upgrade-krw-1", null, "tts", "KRW", BigDecimal.TEN));
        control.setSpending(false, "upgrade done", control.get().version(), admin);

        Map<String, Long> dataBefore = checksums();
        TreeSet<String> structureBefore = structure();
        List<String> historyBefore = history();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log", Integer.class)).isPositive();

        // 2) migration 계정으로 V6 적용
        Flyway.configure().dataSource(DB.dataSource()).locations("classpath:db/migration").target("6").cleanDisabled(true).load()
                .migrate();

        // 3) 이력·데이터·기존 제약 보존
        List<String> historyAfter = history();
        assertThat(historyAfter).hasSize(6).startsWith(historyBefore.toArray(String[]::new));
        assertThat(historyAfter.get(5)).startsWith("6:");
        assertThat(checksums()).isEqualTo(dataBefore);
        TreeSet<String> structureAfter = structure();
        assertThat(structureAfter).containsAll(structureBefore);
        List<String> added = new ArrayList<>(structureAfter);
        added.removeAll(structureBefore);
        assertThat(added).containsExactlyInAnyOrder("C channel_config.uk_channel_config_owner UNIQUE",
                "K channel_config.uk_channel_config_owner.channel_id#1->.",
                "K channel_config.uk_channel_config_owner.id#2->.");
        assertThat(control.runConsistencyCheck(admin).issues()).isEmpty();

        // 4) 업그레이드된 기존 채널·설정(revision 2)으로 콘텐츠 작성
        long topicId = topics.create(new TopicService.CreateTopic("upgrade-topic-1", channelId, "Upgrade", null), admin)
                .topic().id();
        ContentService.ContentView view = contents.create(new ContentService.CreateContent("upgrade-content-1",
                channelId, topicId, "SHORTS", "Upgraded", "Brief", null, 50, null, null, null), admin).view();
        assertThat(view.currentRevision().language()).isEqualTo("ko");
        assertThat(jdbc.queryForObject("SELECT revision_no FROM channel_config WHERE id = ?", Integer.class,
                view.currentRevision().channelConfigId())).isEqualTo(2);
        // 기존 채널 보관(FD-16 경로)도 동작
        channels.archive(channelId, channels.get(channelId).channel().version(), admin);

        Map<String, Long> beforeV9 = checksums();
        Flyway.configure().dataSource(DB.dataSource()).locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate();
        List<String> v9History = history();
        assertThat(v9History).hasSize(8).startsWith(historyAfter.toArray(String[]::new));
        assertThat(v9History.get(6)).startsWith("9:");
        assertThat(v9History.getLast()).startsWith("10:");
        assertThat(checksums()).isEqualTo(beforeV9);
        assertThat(contents.get(view.content().id()).currentRevision()).isEqualTo(view.currentRevision());
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name IN ('production_request', 'production_step', 'provider_operation')
                """, String.class)).containsExactlyInAnyOrder("production_request", "production_step", "provider_operation");
    }
}
