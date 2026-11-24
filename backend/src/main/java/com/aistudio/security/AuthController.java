package com.aistudio.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.audit.AuditService.Outcome;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/**
 * 세션 로그인 API (ADR-037, ADR-060). 성공 시 세션 ID 교체·CSRF 토큰 무효화·SecurityContext 저장을 직접 수행한다.
 * 실패 사유(미존재·비밀번호 오류·비활성)는 구분하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication
public class AuthController {

    public record CsrfTokenResponse(String headerName, String token) {
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String login, @NotBlank @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginRequest[login=" + login + "]";
        }
    }

    public record CurrentUserResponse(String id, String login, String role, Instant lastLoginAt) {
    }

    private static final String INVALID_CREDENTIALS = "Invalid login or password.";

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LoginRateLimiter rateLimiter;
    private final AdminUserMapper users;
    private final AuditService audit;
    private final Clock clock;

    AuthController(AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, LoginRateLimiter rateLimiter, AdminUserMapper users,
            AuditService audit, Clock clock) {
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.rateLimiter = rateLimiter;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/csrf")
    CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(SecurityConfiguration.CSRF_HEADER, token.getToken());
    }

    @PostMapping("/login")
    CurrentUserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String login = PasswordPolicy.normalizeLogin(body.login());
        String ip = request.getRemoteAddr();
        Optional<java.time.Duration> blocked = rateLimiter.blockedFor(login, ip);
        if (blocked.isPresent()) {
            throw ApiException.rateLimited(blocked.get().toSeconds());
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login, body.password()));
        } catch (AuthenticationException e) {
            LoginRateLimiter.FailureResult result = rateLimiter.recordFailure(login, ip);
            audit.record(Actor.anonymous(), "AUTH_LOGIN", Outcome.FAILURE, "ADMIN_LOGIN", null, ip,
                    Map.of("login", login));
            if (!result.newlyBlockedKeys().isEmpty()) {
                audit.record(Actor.anonymous(), "AUTH_RATE_LIMITED", Outcome.DENIED, "ADMIN_LOGIN", null, ip,
                        Map.of("login", login, "limits", result.newlyBlockedKeys()));
            }
            throw new ApiException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        AdminPrincipal principal = (AdminPrincipal) authentication.getPrincipal();
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        Instant now = clock.instant();
        users.updateLastLogin(principal.id(), now);
        rateLimiter.recordSuccess(login, ip);
        audit.record(Actor.admin(principal.id()), "AUTH_LOGIN", Outcome.SUCCESS, "ADMIN_USER", principal.id(), ip,
                null);
        return new CurrentUserResponse(String.valueOf(principal.id()), principal.login(), "ADMIN", now);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        AdminPrincipal principal = CurrentActor.principal();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        audit.record(Actor.admin(principal.id()), "AUTH_LOGOUT", Outcome.SUCCESS, "ADMIN_USER", principal.id(),
                request.getRemoteAddr(), null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    CurrentUserResponse me() {
        AdminPrincipal principal = CurrentActor.principal();
        AdminUser user = users.findById(principal.id());
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return new CurrentUserResponse(String.valueOf(user.id()), user.login(), user.role(), user.lastLoginAt());
    }
}
