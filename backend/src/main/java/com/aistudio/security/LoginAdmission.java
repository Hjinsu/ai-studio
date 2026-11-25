package com.aistudio.security;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.aistudio.shared.api.ApiException;

/** Immediate admission check for authentication work on this application instance. */
@Component
final class LoginAdmission {

    private static final int MAX_PER_IP = 2;
    private static final int MAX_TOTAL = 4;

    private final Set<String> activeLogins = new HashSet<>();
    private final Map<String, Integer> activeIps = new HashMap<>();

    <T> T execute(String login, String ip, Supplier<T> work) {
        acquire(login, ip);
        try {
            return work.get();
        } finally {
            release(login, ip);
        }
    }

    private synchronized void acquire(String login, String ip) {
        int ipCount = activeIps.getOrDefault(ip, 0);
        if (activeLogins.size() >= MAX_TOTAL || activeLogins.contains(login) || ipCount >= MAX_PER_IP) {
            throw ApiException.rateLimited(1);
        }
        activeLogins.add(login);
        activeIps.put(ip, ipCount + 1);
    }

    private synchronized void release(String login, String ip) {
        activeLogins.remove(login);
        activeIps.computeIfPresent(ip, (key, count) -> count == 1 ? null : count - 1);
    }
}
