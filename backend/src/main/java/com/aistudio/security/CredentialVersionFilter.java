package com.aistudio.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 세션 인증의 유효성 재확인(ADR-060): 계정이 비활성화됐거나 credential_version이 바뀌었으면(비밀번호 재설정)
 * 세션을 폐기하고 인증을 지운다. 시각 비교는 사용하지 않는다.
 */
class CredentialVersionFilter extends OncePerRequestFilter {

    private final AdminUserMapper users;

    CredentialVersionFilter(AdminUserMapper users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            AdminUser user = users.findById(principal.id());
            if (user == null || !user.enabled() || user.credentialVersion() != principal.credentialVersion()) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
