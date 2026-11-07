package kr.anbu.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;

    @BeforeEach
    void prepareAccount() {
        jdbc.update("DELETE FROM auth_tokens");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("INSERT INTO organizations(id, name, type) VALUES (1, '테스트 기관', 'HEALTH_CENTER')");
        jdbc.update("INSERT INTO users(id, login_id, password_hash, name, role, organization_id) VALUES (1, ?, ?, ?, 'STAFF', 1)",
                "test-login", passwords.encode("test-password-123"), "테스트 담당자");
    }

    @Test
    void loginReturnsContractAndStoresOnlyHash() throws Exception {
        String token = login();
        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_tokens", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(token);
        assertThat(auth.authenticate(token)).contains(new Actor(1L, "테스트 담당자", "STAFF", 1L));
        mvc.perform(get("/api/v1/visits").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void expiredAndUnknownTokensCannotAuthenticate() throws Exception {
        String token = login();
        jdbc.update("UPDATE auth_tokens SET expires_at = ?", Timestamp.from(Instant.EPOCH));
        mvc.perform(get("/api/v1/visits").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/visits").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/visits"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failedLoginAndInvalidInputReturnApiEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"test-login\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"unknown\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"test-login\",\"password\":\"test-password-123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_tokens", Long.class)).isZero();
    }

    @Test
    void bcryptByteLimitIsValidatedBeforeEncoding() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthController.LoginInput("test-login", "가".repeat(25)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"test-login\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.user.organization.type").value("HEALTH_CENTER"))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = mapper.readTree(body);
        assertThat(response.get("error").isNull()).isTrue();
        return response.path("data").path("accessToken").asText();
    }

}
