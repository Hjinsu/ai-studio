package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.aistudio.audit.AuditService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

class AuthControllerTest {

    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "IP", "GLOBAL"})
    void overloadIsRejectedBeforeAuthenticationAndCapacityRecovers(String dimension) throws Exception {
        int held = switch (dimension) {
            case "LOGIN" -> 1;
            case "IP" -> 2;
            default -> 4;
        };
        var entered = new CountDownLatch(held);
        var release = new CountDownLatch(1);
        var manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any())).thenAnswer(call -> {
            var input = (Authentication) call.getArgument(0);
            if ("hold".equals(input.getCredentials())) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            if ("success".equals(input.getCredentials())) {
                return UsernamePasswordAuthenticationToken.authenticated(
                        new AdminPrincipal(1, "unknown0", 1), null, List.of());
            }
            throw new BadCredentialsException("fixture");
        });
        var accounts = mock(AdminAccountService.class);
        when(accounts.recordSuccessfulLogin(eq(1L), any())).thenReturn(Instant.EPOCH);
        var controller = controllerFor(manager, accounts);
        String deniedLogin = dimension.equals("LOGIN") ? " UNKNOWN0 " : "other";
        String deniedIp = dimension.equals("IP") ? "192.0.2.1" : "192.0.2.100";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < held; i++) {
                    String ip = dimension.equals("IP") ? "192.0.2.1" : "192.0.2." + (i + 1);
                    String login = "unknown" + i;
                    requests.add(executor.submit(() -> assertRejected(
                            () -> attempt(controller, login, ip, "hold"),
                            ErrorCode.UNAUTHORIZED)));
                }
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 6; i++) {
                    assertThatThrownBy(() -> attempt(controller, deniedLogin, deniedIp, "test"))
                            .isInstanceOfSatisfying(ApiException.class, failure -> {
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
                                assertThat(failure.retryAfterSeconds()).isEqualTo(1);
                            });
                }
            } finally {
                release.countDown();
            }
            for (var request : requests) {
                request.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(attempt(controller, deniedLogin, deniedIp, "success").id()).isEqualTo("1");
    }

    @Test
    void providerDatabaseErrorReleasesCapacity() {
        var manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any()))
                .thenThrow(new DataAccessResourceFailureException("fixture"))
                .thenThrow(new BadCredentialsException("fixture"));
        var controller = controllerFor(manager, mock(AdminAccountService.class));
        assertThatThrownBy(() -> attempt(controller, "admin", "192.0.2.1", "test"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertRejected(() -> attempt(controller, "admin", "192.0.2.1", "test"),
                ErrorCode.UNAUTHORIZED);
    }

    private static AuthController controllerFor(AuthenticationManager manager, AdminAccountService accounts) {
        return new AuthController(manager, mock(SessionAuthenticationStrategy.class),
                mock(SecurityContextRepository.class), new LoginRateLimiter(Clock.systemUTC(), 100),
                mock(AdminUserMapper.class), mock(AuditService.class), accounts, new LoginAdmission());
    }

    private static AuthController.CurrentUserResponse attempt(AuthController controller, String login, String ip,
            String password) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        try {
            return controller.login(new AuthController.LoginRequest(login, password), request,
                    new MockHttpServletResponse());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void assertRejected(ThrowingCallable run,
            ErrorCode code) {
        assertThatThrownBy(run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }

    @Test
    void sessionIsInvalidatedIfRepositoryThrowsAfterSavingAuthentication() {
        var manager = mock(AuthenticationManager.class);
        var strategy = mock(SessionAuthenticationStrategy.class);
        var repository = mock(SecurityContextRepository.class);
        var accounts = mock(AdminAccountService.class);
        var rateLimiter = new LoginRateLimiter(Clock.systemUTC(), 100);
        var controller = new AuthController(manager, strategy, repository, rateLimiter, mock(AdminUserMapper.class),
                mock(AuditService.class), accounts, new LoginAdmission());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var session = new MockHttpSession();
        request.setSession(session);
        var principal = new AdminPrincipal(1L, "admin", 1);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        when(manager.authenticate(any())).thenReturn(authentication);
        when(accounts.recordSuccessfulLogin(principal.id(), request.getRemoteAddr())).thenReturn(Instant.EPOCH);
        var failure = new IllegalStateException("test session storage failure");
        doAnswer(call -> {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, call.getArgument(0));
            throw failure;
        }).when(repository).saveContext(any(), eq(request), eq(response));

        try {
            assertThatThrownBy(() -> controller.login(new AuthController.LoginRequest("admin", "fixture"),
                    request, response)).isSameAs(failure);
            assertThat(session.isInvalid()).isTrue();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            var order = inOrder(accounts, strategy, repository);
            order.verify(accounts).recordSuccessfulLogin(principal.id(), request.getRemoteAddr());
            order.verify(strategy).onAuthentication(authentication, request, response);
            order.verify(repository).saveContext(any(), eq(request), eq(response));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
