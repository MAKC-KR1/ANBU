package kr.anbu.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import kr.anbu.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final long TOKEN_SECONDS = 3600;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
        this.dummyHash = passwords.encode("unmatched-login-timing-placeholder");
    }

    @Transactional
    public LoginResult login(String loginId, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        List<Account> accounts = jdbc.query("""
                SELECT u.id, u.name, u.role, u.password_hash, o.id AS organization_id,
                       o.name AS organization_name, o.type AS organization_type
                  FROM users u JOIN organizations o ON o.id = u.organization_id
                 WHERE u.login_id = ?
                """, (row, index) -> new Account(
                new User(row.getLong("id"), row.getString("name"), row.getString("role"),
                        new Organization(row.getLong("organization_id"), row.getString("organization_name"),
                                row.getString("organization_type"))), row.getString("password_hash")), loginId);
        String hash = accounts.isEmpty() ? dummyHash : accounts.getFirst().passwordHash();
        boolean matches = passwords.matches(password, hash);
        if (accounts.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "아이디 또는 비밀번호를 확인해 주세요.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        User user = accounts.getFirst().user();
        jdbc.update("DELETE FROM auth_tokens WHERE expires_at <= ?", Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO auth_tokens(token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                hashToken(token), user.id(), Timestamp.from(clock.instant().plusSeconds(TOKEN_SECONDS)));
        return new LoginResult(token, TOKEN_SECONDS, user);
    }

    public Optional<Actor> authenticate(String token) {
        if (token.length() != 43) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT u.id, u.name, u.role, u.organization_id
                  FROM auth_tokens t JOIN users u ON u.id = t.user_id
                 WHERE t.token_hash = ? AND t.expires_at > ?
                """, (row, index) -> new Actor(row.getLong("id"), row.getString("name"),
                row.getString("role"), row.getLong("organization_id")),
                hashToken(token), Timestamp.from(clock.instant())).stream().findFirst();
    }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Account(User user, String passwordHash) {}
    public record Organization(Long id, String name, String type) {}
    public record User(Long id, String name, String role, Organization organization) {}
    public record LoginResult(String accessToken, long expiresIn, User user) {}
}
