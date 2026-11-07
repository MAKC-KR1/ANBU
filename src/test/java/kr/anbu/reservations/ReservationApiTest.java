package kr.anbu.reservations;

import kr.anbu.auth.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reservations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ReservationApiTest {
    private static final String PATH = "/api/v1/visit-reservations";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM visit_reservations");
        jdbc.update("DELETE FROM test_type_organizations");
        jdbc.update("DELETE FROM test_types");
        jdbc.update("DELETE FROM places");
        jdbc.update("DELETE FROM dongs");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("INSERT INTO organizations(id,name,type) VALUES (1,'기관 A','BRANCH'),(2,'기관 B','HEALTH_CENTER')");
        jdbc.update("""
                INSERT INTO users(id,login_id,password_hash,name,role,organization_id)
                VALUES (1,'staff-a','unused','담당자 A','STAFF',1),(2,'staff-b','unused','담당자 B','STAFF',2)
                """);
        jdbc.update("INSERT INTO dongs(id,name) VALUES (10,'가동')");
        jdbc.update("""
                INSERT INTO places(id,name,dong_id,address,latitude,longitude,place_type)
                VALUES (101,'가경로당',10,'주소 A',37,126,'SENIOR_CENTER'),
                       (102,'나경로당',10,'주소 B',37,126,'SENIOR_CENTER')
                """);
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES (1,'건강검사','A')");
        jdbc.update("INSERT INTO test_type_organizations(test_type_id,organization_id) VALUES (1,1)");
    }

    @Test
    void storesAReservationForTheSignedInOrganizationAndRejectsTheSameSlot() throws Exception {
        String input = payload(101, LocalDate.now(clock).plusDays(1));
        mvc.perform(post(PATH).with(staff(1)).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationId").value(1))
                .andExpect(jsonPath("$.data.testTypeName").value("건강검사"));
        mvc.perform(post(PATH).with(staff(1)).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_RESERVED"));
        mvc.perform(get(PATH).with(staff(2)).param("placeId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].visitTime").value("10:30:00"));
        mvc.perform(get(PATH).with(staff(1)).param("placeId", "102"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/visits").with(staff(1)).param("placeId", "101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void enforcesAuthenticationTestCapabilityAndFutureDate() throws Exception {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        mvc.perform(get(PATH).param("placeId", "101")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(staff(2)).contentType(MediaType.APPLICATION_JSON).content(payload(101, tomorrow)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(staff(1)).contentType(MediaType.APPLICATION_JSON)
                        .content(payload(101, tomorrow.minusDays(2))))
                .andExpect(status().isBadRequest());
    }

    private static String payload(long place, LocalDate date) {
        return """
                {"placeId":%d,"visitDate":"%s","visitTime":"10:30","testTypeId":1}
                """.formatted(place, date);
    }

    private static RequestPostProcessor staff(long id) {
        return authentication(new UsernamePasswordAuthenticationToken(new Actor(id, "담당자", "STAFF", id),
                null, List.of()));
    }
}
