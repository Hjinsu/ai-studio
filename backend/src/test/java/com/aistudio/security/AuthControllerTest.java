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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.aistudio.audit.AuditService;

class AuthControllerTest {

    @Test
    void sessionIsInvalidatedIfRepositoryThrowsAfterSavingAuthentication() {
        var manager = mock(AuthenticationManager.class);
        var strategy = mock(SessionAuthenticationStrategy.class);
        var repository = mock(SecurityContextRepository.class);
        var accounts = mock(AdminAccountService.class);
        var rateLimiter = new LoginRateLimiter(Clock.systemUTC(), 100);
        var controller = new AuthController(manager, strategy, repository, rateLimiter, mock(AdminUserMapper.class),
                mock(AuditService.class), accounts);
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
