package com.aistudio.security;

import java.io.Console;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * Spring Bean 생성 경로(유일하게 주입에 쓰이는 생성자). 생성자가 둘이라 명시하지 않으면 Spring이 기본 생성자를 찾다
     * 실패한다(BeanInstantiationException, 2026-11-25 CLI 결함). 입력 장치는 기본적으로 시스템 콘솔이며, 콘솔이 없으면
     * 거부한다. PasswordPrompt Bean은 운영 코드에 정의하지 않는다(테스트 컨텍스트에서만 숨김 입력 대체용).
     */
    @Autowired
    public AdminCommandRunner(AdminAccountService accounts, ObjectProvider<PasswordPrompt> prompt) {
        this(accounts, prompt.getIfAvailable(PasswordPrompt::systemConsole), System.out);
    }

    AdminCommandRunner(AdminAccountService accounts, PasswordPrompt prompt, PrintStream out) {
        this.accounts = accounts;
        this.prompt = prompt;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        Parsed parsed = parse(args.getSourceArgs());
        exitCode = execute(parsed.commands(), parsed.logins());
    }

    /** 원본 인자에서 명령과 로그인을 분리한 결과. 형식 오류면 logins가 null이라 execute가 사용법으로 거부한다. */
    record Parsed(List<String> commands, List<String> logins) {
    }

    /**
     * 원본 인자를 엄격하게 해석한다. 문서화된 `--login <id>`(공백)와 `--login=<id>`를 모두 받는다.
     * Spring의 ApplicationArguments는 `--name=value`만 옵션 값으로 인식해 `--login admin`을 비옵션 인자로 나누므로
     * 직접 해석한다(2026-11-25 CLI 결함). `--login` 이외의 `--key=value`는 Spring 설정 재정의로 보고 무시한다.
     * 그 밖의 형태(값 없는 --login, 중복 --login, 알 수 없는 옵션, 남는 인자)는 거부한다.
     */
    static Parsed parse(String[] source) {
        List<String> commands = new ArrayList<>();
        List<String> logins = new ArrayList<>();
        boolean invalid = false;
        for (int i = 0; i < source.length; i++) {
            String arg = source[i];
            if (arg.equals("--login")) {
                if (i + 1 >= source.length || source[i + 1].startsWith("--")) {
                    invalid = true;
                } else {
                    logins.add(source[++i]);
                }
            } else if (arg.startsWith("--login=")) {
                logins.add(arg.substring("--login=".length()));
            } else if (arg.startsWith("--")) {
                if (!arg.contains("=")) {
                    invalid = true;
                }
            } else {
                commands.add(arg);
            }
        }
        if (invalid || logins.size() != 1 || logins.getFirst().isBlank()) {
            return new Parsed(commands, null);
        }
        return new Parsed(commands, logins);
    }

    int execute(List<String> commands, List<String> logins) {
        if (commands.size() != 2 || !"admin".equals(commands.get(0)) || logins == null || logins.size() != 1) {
            out.println("사용법: admin create --login <id> | admin reset-password --login <id> (--login=<id>도 허용)");
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
