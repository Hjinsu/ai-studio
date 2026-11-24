package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

/** ADR-031: 숨김 입력만, 콘솔 없으면 거부, 비밀번호는 출력·로그 금지, 사용 후 지움. */
class AdminCommandRunnerTest {

    private final AdminAccountService accounts = mock(AdminAccountService.class);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final List<char[]> handedOut = new ArrayList<>();

    private AdminCommandRunner runner(String... answers) {
        Deque<String> queue = new ArrayDeque<>(List.of(answers));
        AdminCommandRunner.PasswordPrompt prompt = text -> {
            if (queue.isEmpty()) {
                return null;
            }
            char[] value = queue.pop().toCharArray();
            handedOut.add(value);
            return value;
        };
        return new AdminCommandRunner(accounts, prompt, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void refusesWithoutConsole() {
        int code = runner().execute(List.of("admin", "create"), List.of("admin"));
        assertThat(code).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(out()).contains("콘솔이 없습니다");
        verifyNoInteractions(accounts);
    }

    @Test
    void refusesMismatchedConfirmation() {
        int code = runner("correct-horse-battery-1", "different-password-2").execute(List.of("admin", "create"),
                List.of("admin"));
        assertThat(code).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        verify(accounts, never()).createInitialAdmin(any(), any());
    }

    @Test
    void createsAdminAndWipesPasswordBuffers() {
        String password = "correct-horse-battery-1";
        int code = runner(password, password).execute(List.of("admin", "create"), List.of("admin"));
        assertThat(code).isEqualTo(AdminCommandRunner.EXIT_OK);
        verify(accounts).createInitialAdmin(eq("admin"), any());
        assertThat(out()).doesNotContain(password);
        assertThat(handedOut).allSatisfy(buffer -> assertThat(new String(buffer)).isEqualTo("\0".repeat(password.length())));
    }

    @Test
    void reportsRejectionReasonWithoutPassword() {
        String password = "correct-horse-battery-1";
        doAnswer(invocation -> {
            throw new AdminAccountService.AdminCommandRejected("관리자가 이미 존재합니다.");
        }).when(accounts).createInitialAdmin(any(), any());
        int code = runner(password, password).execute(List.of("admin", "create"), List.of("admin"));
        assertThat(code).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(out()).contains("이미 존재").doesNotContain(password);
    }

    @Test
    void rejectsUnknownCommandOrMissingLogin() {
        assertThat(runner().execute(List.of("admin", "drop"), List.of("admin")))
                .isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(runner().execute(List.of("admin", "create"), null)).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
    }
}
