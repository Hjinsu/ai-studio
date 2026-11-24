package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aistudio.TestDatabases;

/** ADR-031, ADR-058: 최초 관리자 동시 생성은 하나만 성공, bootstrap 행 수정·삭제 금지, 비밀번호 저장 형식. */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class AdminBootstrapIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentInitialAdminCreationAllowsExactlyOne() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String login = "admin" + i;
            Callable<String> task = () -> {
                start.await();
                try {
                    accounts.createInitialAdmin(login, "correct-horse-battery-1".toCharArray());
                    return "created";
                } catch (AdminAccountService.AdminCommandRejected e) {
                    return "rejected";
                }
            };
            results.add(executor.submit(task));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }
        executor.shutdown();

        assertThat(outcomes).filteredOn("created"::equals).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_bootstrap", Integer.class)).isEqualTo(1);

        // 이후 최초 생성 명령은 항상 거부
        assertThatThrownBy(() -> accounts.createInitialAdmin("late", "correct-horse-battery-1".toCharArray()))
                .isInstanceOf(AdminAccountService.AdminCommandRejected.class);

        // bootstrap 행은 수정·삭제할 수 없다(트리거)
        assertThatThrownBy(() -> jdbc.update("UPDATE admin_bootstrap SET admin_user_id = admin_user_id"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM admin_bootstrap")).hasMessageContaining("immutable");

        // BCrypt cost 12, 평문 미저장
        String hash = jdbc.queryForObject("SELECT password_hash FROM admin_user", String.class);
        assertThat(hash).startsWith("$2b$12$").doesNotContain("correct-horse");
        Integer createAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'AUTH_ADMIN_CREATE'", Integer.class);
        assertThat(createAudits).isEqualTo(1);
    }

    @Test
    void rejectsWeakOrTruncatablePasswords() {
        assertThatThrownBy(() -> accounts.resetPassword("admin0", "short".toCharArray()))
                .isInstanceOf(AdminAccountService.AdminCommandRejected.class).hasMessageContaining("12");
        assertThatThrownBy(() -> accounts.resetPassword("admin0", "가".repeat(25).toCharArray()))
                .isInstanceOf(AdminAccountService.AdminCommandRejected.class).hasMessageContaining("72");
    }
}
