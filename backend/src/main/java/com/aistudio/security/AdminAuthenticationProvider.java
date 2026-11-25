package com.aistudio.security;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 관리자 로그인 인증. 미존재·비활성·비밀번호 오류를 구분하지 않고(같은 예외), 미존재 계정도 더미 해시와 비교해
 * 처리 시간 차이를 줄인다(ADR-037).
 */
@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {

    static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final AdminUserMapper users;
    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    AdminAuthenticationProvider(AdminUserMapper users, BCryptPasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("dummy-password-for-timing-equalization");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String login = PasswordPolicy.normalizeLogin(authentication.getName());
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? "" : credentials.toString();
        AdminUser user = PasswordPolicy.isValidLogin(login) ? users.findByLogin(login) : null;
        if (user == null) {
            encoder.matches(password, dummyHash);
            throw new BadCredentialsException("Invalid credentials");
        }
        boolean matches = encoder.matches(password, user.passwordHash());
        if (!matches || !user.enabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        AdminPrincipal principal = new AdminPrincipal(user.id(), user.login(), user.credentialVersion());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority(ROLE_ADMIN)));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
