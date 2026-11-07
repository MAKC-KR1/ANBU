package kr.anbu.config;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String password;

    public DemoData(JdbcTemplate jdbc, PasswordEncoder passwords,
                    @Value("${ANBU_DEMO_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("The demo profile requires ANBU_DEMO_PASSWORD of at least 12 characters and at most 72 UTF-8 bytes.");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM organizations", Long.class) > 0) {
            return;
        }
        long health = insert("organizations", Map.of("name", "시연 보건소", "type", "HEALTH_CENTER"));
        long welfare = insert("organizations", Map.of("name", "시연 복지관", "type", "WELFARE_CENTER"));
        long dong = insert("dongs", Map.of("name", "시연동", "gu", "시연구"));
        jdbc.update("INSERT INTO organization_dongs(organization_id, dong_id) VALUES (?, ?), (?, ?)",
                health, dong, welfare, dong);
        insert("places", Map.of("name", "시연 경로당", "dong_id", dong,
                "address", "시연용 주소 (실제 시설 아님)", "latitude", 37.3219, "longitude", 126.8309));
        long testType = insert("test_types", Map.of("name", "시연 건강검사", "service_code", "B"));
        jdbc.update("INSERT INTO test_type_organizations(test_type_id, organization_id) VALUES (?, ?)",
                testType, health);
        String hash = passwords.encode(password);
        createUser("health-demo", "시연 보건소 담당자", "STAFF", health, hash);
        createUser("welfare-demo", "시연 복지관 담당자", "STAFF", welfare, hash);
        createUser("manager-demo", "시연 기관 관리자", "ORG_MANAGER", health, hash);
        createUser("admin-demo", "시연 시스템 관리자", "ADMIN", health, hash);
    }

    private void createUser(String loginId, String name, String role, long organizationId, String hash) {
        insert("users", Map.of("login_id", loginId, "password_hash", hash, "name", name,
                "role", role, "organization_id", organizationId));
    }

    private long insert(String table, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id")
                .executeAndReturnKey(values).longValue();
    }
}
