package com.aistudio.security;

import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;

/**
 * 관리자 계정 생성·비밀번호 재설정 (ADR-031, ADR-060). 웹 가입 API는 없고 CLI에서만 호출한다.
 */
@Service
public class AdminAccountService {

    /** CLI 사용자에게 그대로 보여 줄 수 있는 거부 사유. */
    public static class AdminCommandRejected extends RuntimeException {
        public AdminCommandRejected(String message) {
            super(message);
        }
    }

    private final AdminUserMapper mapper;
    private final BCryptPasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    AdminAccountService(AdminUserMapper mapper, BCryptPasswordEncoder encoder, AuditService audit, Clock clock) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 최초 관리자 생성. 이미 관리자 또는 bootstrap 행이 있으면 거부. 동시 실행은 admin_bootstrap PK(id=1)로 하나만 성공한다.
     */
    @Transactional
    public long createInitialAdmin(String rawLogin, char[] password) {
        String login = validLogin(rawLogin);
        requireValidPassword(password);
        if (mapper.countAll() > 0 || mapper.countBootstrap() > 0) {
            throw new AdminCommandRejected("관리자가 이미 존재합니다. 최초 생성 명령은 한 번만 실행할 수 있습니다.");
        }
        Instant now = clock.instant();
        AdminUserMapper.NewAdminUser user = new AdminUserMapper.NewAdminUser(login, hash(password), now);
        try {
            mapper.insert(user);
            mapper.insertBootstrap(user.id, now);
        } catch (DuplicateKeyException e) {
            throw new AdminCommandRejected("관리자가 이미 존재합니다(동시 생성 경쟁에서 거부됨).");
        } catch (org.springframework.dao.DataAccessException e) {
            if (com.aistudio.shared.db.DatabaseErrors.isSnapshotConflict(e)) {
                throw new AdminCommandRejected("관리자가 이미 존재합니다(동시 생성 경쟁에서 거부됨).");
            }
            throw e;
        }
        audit.record(Actor.cli(), "AUTH_ADMIN_CREATE", "ADMIN_USER", user.id, Map.of("login", login));
        return user.id;
    }

    @Transactional
    public void resetPassword(String rawLogin, char[] password) {
        String login = PasswordPolicy.normalizeLogin(rawLogin);
        requireValidPassword(password);
        AdminUser user = mapper.findByLogin(login);
        if (user == null) {
            throw new AdminCommandRejected("해당 로그인의 관리자가 없습니다.");
        }
        mapper.updatePassword(user.id(), hash(password), clock.instant());
        audit.record(Actor.cli(), "AUTH_PASSWORD_RESET", "ADMIN_USER", user.id(), Map.of("login", login));
    }

    @Transactional
    public Instant recordSuccessfulLogin(long adminId, String ip) {
        Instant now = clock.instant();
        mapper.updateLastLogin(adminId, now);
        audit.record(Actor.admin(adminId), "AUTH_LOGIN", AuditService.Outcome.SUCCESS, "ADMIN_USER", adminId, ip, null);
        return now;
    }

    private static String validLogin(String rawLogin) {
        String login = PasswordPolicy.normalizeLogin(rawLogin);
        if (!PasswordPolicy.isValidLogin(login)) {
            throw new AdminCommandRejected("로그인은 3~64자의 소문자, 숫자, '.', '_', '-'만 사용할 수 있습니다.");
        }
        return login;
    }

    private static void requireValidPassword(char[] password) {
        String violation = PasswordPolicy.violation(password);
        if (violation != null) {
            throw new AdminCommandRejected(violation);
        }
    }

    private String hash(char[] password) {
        return encoder.encode(CharBuffer.wrap(password));
    }
}
