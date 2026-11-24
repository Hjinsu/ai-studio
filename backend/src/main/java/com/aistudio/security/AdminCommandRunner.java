package com.aistudio.security;

import java.io.Console;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 관리자 CLI (ADR-031). `java -jar backend.jar admin create --login <id>` 또는 `admin reset-password --login <id>`.
 * 비밀번호는 콘솔 숨김 입력만 허용하고, 콘솔이 없으면 실행을 거부한다(stdin 평문 입력 대체 금지).
 */
@Component
@ConditionalOnProperty(name = "ai-studio.cli.admin", havingValue = "true")
public class AdminCommandRunner implements ApplicationRunner, ExitCodeGenerator {

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_REJECTED = 2;

    /** 테스트에서 대체할 수 있는 숨김 입력 장치. 콘솔이 없으면 null을 반환한다. */
    public interface PasswordPrompt {
        char[] read(String prompt);

        static PasswordPrompt systemConsole() {
            return prompt -> {
                Console console = System.console();
                return console == null ? null : console.readPassword("%s", prompt);
            };
        }
    }

    private final AdminAccountService accounts;
    private final PasswordPrompt prompt;
    private final PrintStream out;
    private int exitCode = EXIT_ERROR;

    public AdminCommandRunner(AdminAccountService accounts) {
        this(accounts, PasswordPrompt.systemConsole(), System.out);
    }

    AdminCommandRunner(AdminAccountService accounts, PasswordPrompt prompt, PrintStream out) {
        this.accounts = accounts;
        this.prompt = prompt;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute(args.getNonOptionArgs(), args.getOptionValues("login"));
    }

    int execute(List<String> commands, List<String> logins) {
        if (commands.size() != 2 || !"admin".equals(commands.get(0)) || logins == null || logins.size() != 1) {
            out.println("사용법: admin create --login <id> | admin reset-password --login <id>");
            return EXIT_REJECTED;
        }
        String command = commands.get(1);
        if (!command.equals("create") && !command.equals("reset-password")) {
            out.println("알 수 없는 명령: " + command);
            return EXIT_REJECTED;
        }
        char[] password = null;
        char[] confirm = null;
        try {
            password = prompt.read("새 비밀번호: ");
            if (password == null) {
                out.println("콘솔이 없습니다. 비밀번호 숨김 입력이 가능한 터미널(cmd, PowerShell, winpty 등)에서 java -jar로 실행하세요.");
                return EXIT_REJECTED;
            }
            confirm = prompt.read("비밀번호 확인: ");
            if (confirm == null || !Arrays.equals(password, confirm)) {
                out.println("두 비밀번호가 일치하지 않습니다.");
                return EXIT_REJECTED;
            }
            if (command.equals("create")) {
                long id = accounts.createInitialAdmin(logins.get(0), password);
                out.println("관리자를 생성했습니다. id=" + id);
            } else {
                accounts.resetPassword(logins.get(0), password);
                out.println("비밀번호를 재설정했습니다. 기존 세션은 무효화됩니다.");
            }
            return EXIT_OK;
        } catch (AdminAccountService.AdminCommandRejected e) {
            out.println("거부: " + e.getMessage());
            return EXIT_REJECTED;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (confirm != null) {
                Arrays.fill(confirm, '\0');
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
