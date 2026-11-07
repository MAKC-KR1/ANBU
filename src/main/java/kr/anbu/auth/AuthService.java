package kr.anbu.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.entity.AuthToken;
import kr.anbu.persistence.entity.UserAccount;
import kr.anbu.persistence.repository.AuthTokenRepository;
import kr.anbu.persistence.repository.OrganizationRepository;
import kr.anbu.persistence.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final long TOKEN_SECONDS = 3600;
    private final UserAccountRepository accounts;
    private final OrganizationRepository organizations;
    private final AuthTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(UserAccountRepository accounts, OrganizationRepository organizations,
                       AuthTokenRepository tokens, PasswordEncoder passwords, Clock clock) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.tokens = tokens;
        this.passwords = passwords;
        this.clock = clock;
        this.dummyHash = passwords.encode("unmatched-login-timing-placeholder");
    }

    @Transactional
    public LoginResult login(String loginId, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        Optional<UserAccount> account = accounts.findByLoginId(loginId);
        String hash = account.map(UserAccount::getPasswordHash).orElse(dummyHash);
        boolean matches = passwords.matches(password, hash);
        if (account.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "아이디 또는 비밀번호를 확인해 주세요.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UserAccount authenticated = account.orElseThrow();
        var organization = organizations.findById(authenticated.getOrganizationId()).orElseThrow();
        User user = new User(authenticated.getId(), authenticated.getName(), authenticated.getRole(),
                new Organization(organization.getId(), organization.getName(), organization.getType()));
        tokens.deleteExpired(clock.instant());
        tokens.save(new AuthToken(hashToken(token), user.id(), clock.instant().plusSeconds(TOKEN_SECONDS)));
        return new LoginResult(token, TOKEN_SECONDS, user);
    }

    @Transactional(readOnly = true)
    public Optional<Actor> authenticate(String token) {
        if (token.length() != 43) {
            return Optional.empty();
        }
        return tokens.findByTokenHashAndExpiresAtAfter(hashToken(token), clock.instant())
                .flatMap(stored -> accounts.findById(stored.getUserId()))
                .map(user -> new Actor(user.getId(), user.getName(), user.getRole(), user.getOrganizationId()));
    }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Organization(Long id, String name, String type) {}
    public record User(Long id, String name, String role, Organization organization) {}
    public record LoginResult(String accessToken, long expiresIn, User user) {}
}
