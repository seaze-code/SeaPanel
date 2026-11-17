package xyz.seaze.seapanel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    private volatile String password;
    private volatile long sessionTtlMillis;

    public AuthManager(String password, int sessionHours) {
        this.password = password;
        this.sessionTtlMillis = Math.max(1, sessionHours) * 3600_000L;
    }

    public void updateConfig(String password, int sessionHours) {
        this.password = password;
        this.sessionTtlMillis = Math.max(1, sessionHours) * 3600_000L;
    }

    public String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    public boolean verifyPassword(String candidate) {
        if (candidate == null || password == null || password.isEmpty()) {
            return false;
        }
        byte[] a = candidate.getBytes(StandardCharsets.UTF_8);
        byte[] b = password.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    public String createSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, System.currentTimeMillis() + sessionTtlMillis);
        return token;
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiresAt = sessions.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiresAt) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public void revokeSession(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public void clearAllSessions() {
        sessions.clear();
    }
}
