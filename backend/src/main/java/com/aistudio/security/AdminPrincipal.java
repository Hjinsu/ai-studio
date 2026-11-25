package com.aistudio.security;

import java.io.Serializable;

/**
 * 세션 SecurityContext에 저장되는 인증 주체. credentialVersion이 DB 값과 다르면 세션을 무효화한다(ADR-060).
 */
public record AdminPrincipal(long id, String login, int credentialVersion) implements Serializable {
}
