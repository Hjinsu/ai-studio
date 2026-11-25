package com.aistudio.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.aistudio.audit.AuditService.Actor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/** 현재 요청의 인증된 관리자. */
public final class CurrentActor {

    private CurrentActor() {
    }

    public static AdminPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal;
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }

    public static Actor actor() {
        return Actor.admin(principal().id());
    }
}
