package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.budget.BudgetReservationService;
import com.aistudio.budget.BudgetReservationService.AdjustBasis;
import com.aistudio.budget.BudgetReservationService.EventCommand;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.budget.ExchangeRateService;
import com.aistudio.channel.ChannelService;
import com.aistudio.channel.ChannelSettings;

/**
 * 운영 앱 계정 최소 권한 (ADR-025, 026, 058). migration 계정(관리자)으로 Flyway를 적용한 뒤
 * infra/mariadb/prod-app-grants.sql을 그대로 적용한 계정으로 애플리케이션을 띄워 (1) T02 전체 기능이 이 권한만으로
 * 동작하고 (2) DELETE·불변 테이블 UPDATE·DDL이 권한 단계에서 거부되는지 확인한다. prod와 같이 Flyway는 별도 계정.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionGrantsIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static final String APP_USER = "it_app_" + ProcessHandle.current().pid();
    static final String APP_PASSWORD = UUID.randomUUID().toString();
    static final Path GRANTS = Path.of("..", "infra", "mariadb", "prod-app-grants.sql");
    /** prod-app-grants.sql 기준 SELECT/INSERT만 허용되는 불변·추가 전용 테이블. */
    static final Set<String> IMMUTABLE = Set.of("admin_bootstrap", "audit_event", "channel_config", "exchange_rate",
            "budget_policy_revision", "budget_reservation_bucket", "cost_log", "flyway_schema_history");

    static {
        Flyway.configure().dataSource(DB.dataSource()).locations("classpath:db/migration").load().migrate();
        try (Connection connection = DB.dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
            for (String grant : grantStatements()) {
                statement.execute(grant);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("운영 권한 템플릿 적용 실패", e);
        }
    }

    static List<String> grantStatements() {
        try {
            String sql = Files.readString(GRANTS, StandardCharsets.UTF_8);
            List<String> statements = new ArrayList<>();
            StringBuilder body = new StringBuilder();
            for (String line : sql.split("\\R")) {
                if (!line.strip().startsWith("--")) {
                    body.append(line).append('\n');
                }
            }
            for (String statement : body.toString().split(";")) {
                if (!statement.isBlank()) {
                    statements.add(statement.strip().replace("@DB@", DB.name()).replace("@APP_USER@", APP_USER)
                            .replace("@APP_HOST@", "%"));
                }
            }
            return statements;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
        // 앱은 운영 권한 계정, Flyway는 migration 계정(prod와 동일한 분리)
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> DB.server().user());
        registry.add("spring.flyway.password", () -> DB.server().password());
    }

    @Autowired
    private MockMvc mockMvc;

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

    /** 앱 계정으로 접속된 JdbcTemplate(이 컨텍스트의 DataSource). */
    @Autowired
    private JdbcTemplate appJdbc;

    @Test
    void applicationRunsWithProductionGrantsOnly() throws Exception {
        assertThat(appJdbc.queryForObject("SELECT CURRENT_USER()", String.class)).startsWith(APP_USER + "@");
        AdminPrincipal principal = ApiTestSupport.admin(accounts, appJdbc);
        Actor admin = Actor.admin(principal.id());
        accounts.resetPassword(principal.login(), "another-horse-battery-2".toCharArray());

        long channelId = channels.create("grants", "en", "UTC", null, admin).channel().id();
        channels.createConfigRevision(channelId, 1, ChannelSettings.defaults(), "rev", admin);
        channels.pause(channelId, channels.get(channelId).channel().version(), admin);
        channels.resume(channelId, channels.get(channelId).channel().version(), admin);
        long archived = channels.create("grants-archived", "en", "UTC", null, admin).channel().id();
        channels.archive(archived, channels.get(archived).channel().version(), admin);

        var day = policies.create("SYSTEM", null, null, "DAY", new BigDecimal("100000"), "UTC", null, admin);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000000"), "UTC", null, admin);
        var channelPolicy = policies.create("CHANNEL", channelId, null, "DAY", new BigDecimal("50000"), "UTC", null,
                admin);
        policies.setStatus(channelPolicy.policy().id(), false, channelPolicy.policy().version(), admin);
        policies.setStatus(channelPolicy.policy().id(), true, policies.get(channelPolicy.policy().id()).policy().version(),
                admin);
        rates.create("USD", new BigDecimal("1400"), null, "grants", admin);
        control.setSpending(true, "grants", control.get().version(), admin);

        long usd = reservations.reserve(new ReserveCommand("grants-usd-1", channelId, "img", "USD", BigDecimal.ONE))
                .reservation().id();
        policies.revise(day.policy().id(), 1, new BigDecimal("200000"), "UTC", "raise", admin); // 열린 bucket 갱신
        reservations.recordProvisional(new EventCommand(usd, "grants-evt-1", new BigDecimal("0.5"), null, null, null,
                null, null, Actor.system()));
        reservations.confirm(new EventCommand(usd, "grants-evt-2", new BigDecimal("1.5"), null, null, null, null, null,
                Actor.system()));
        reservations.adjust(new EventCommand(usd, "grants-evt-3", new BigDecimal("-10"), "x", null, "FX_DIFFERENCE", "n",
                null, admin), "ADJUSTMENT", AdjustBasis.KRW);
        reservations.adjust(new EventCommand(usd, "grants-evt-4", new BigDecimal("0.1"), "x", null, "REFUND_X", "n", null,
                admin), "REFUND", AdjustBasis.ORIGINAL);
        long krw = reservations.reserve(new ReserveCommand("grants-krw-1", null, "tts", "KRW", BigDecimal.TEN))
                .reservation().id();
        reservations.markReconciliationRequired(krw, "timeout", Actor.system());
        reservations.release(new EventCommand(krw, "grants-evt-5", null, "x", null, "NOT_CHARGED", "checked", null,
                admin));
        assertThat(control.runConsistencyCheck(admin).issues()).isEmpty();
        // 비밀번호 재설정 전 인증 주체는 무효(credential_version, ADR-060), 새 주체로 조회
        mockMvc.perform(get("/api/v1/dashboard/summary").with(ApiTestSupport.as(principal)))
                .andExpect(status().isUnauthorized());
        AdminPrincipal current = ApiTestSupport.admin(accounts, appJdbc);
        mockMvc.perform(get("/api/v1/dashboard/summary").with(ApiTestSupport.as(current))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cost-ledger").with(ApiTestSupport.as(current))).andExpect(status().isOk());
    }

    @Test
    void forbiddenOperationsAreDeniedByPrivileges() {
        List<String> tables = appJdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name""",
                String.class);
        assertThat(tables).containsAll(IMMUTABLE).contains("budget_control", "budget_reservation", "channel");
        for (String table : tables) {
            String column = appJdbc.queryForObject("""
                    SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?
                     ORDER BY ordinal_position LIMIT 1""", String.class, table);
            assertDenied("DELETE FROM " + table + " WHERE 1 = 0", "DELETE command denied");
            if (IMMUTABLE.contains(table)) {
                assertDenied("UPDATE " + table + " SET " + column + " = " + column + " WHERE 1 = 0",
                        "UPDATE command denied");
            }
            assertDenied("TRUNCATE TABLE " + table, "denied");
            assertDenied("DROP TABLE " + table, "DROP command denied");
            assertDenied("ALTER TABLE " + table + " ADD COLUMN it_x INT", "ALTER command denied");
        }
        assertDenied("CREATE TABLE it_new (id INT)", "CREATE command denied");
        assertDenied("CREATE TRIGGER it_t BEFORE INSERT ON cost_log FOR EACH ROW SET @x = 1", "denied");
        assertDenied("SELECT * FROM mysql.user", "denied");
        assertDenied("GRANT SELECT ON " + DB.name() + ".cost_log TO CURRENT_USER()", "denied");
        String grants = String.join("\n", appJdbc.queryForList("SHOW GRANTS", String.class));
        assertThat(grants).doesNotContain("ALL PRIVILEGES", "DELETE", "DROP", "ALTER", "CREATE", "SUPER",
                "WITH GRANT OPTION");
    }

    private void assertDenied(String sql, String message) {
        assertThatThrownBy(() -> appJdbc.execute(sql)).as(sql).hasStackTraceContaining(message);
    }
}
