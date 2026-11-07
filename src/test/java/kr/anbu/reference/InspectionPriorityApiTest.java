package kr.anbu.reference;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kr.anbu.auth.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:inspection-priorities;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class InspectionPriorityApiTest {
    private static final String DONG_PRIORITIES = "/api/v1/dongs/priorities";
    private static final String PLACE_PRIORITIES = "/api/v1/dongs/{dongId}/places/priorities";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock inspectionClock() {
            return Clock.fixed(Instant.parse("2026-02-27T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO organizations(id,name,type) VALUES (1,'기관 A','BRANCH'),(2,'기관 B','HEALTH_CENTER')
                """);
        jdbc.update("""
                INSERT INTO users(id,login_id,password_hash,name,role,organization_id)
                VALUES (1,'staff-a','unused','담당자 A','STAFF',1),(2,'staff-b','unused','담당자 B','STAFF',2)
                """);
        jdbc.update("INSERT INTO dongs(id,name) VALUES (10,'가동'),(20,'나동'),(30,'다동'),(40,'라동')");
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES (1,'건강검사','A')");
    }

    @Test
    void ranksEachDongByItsHighestPriorityFacilityAndRanksFacilitiesByActualTests() throws Exception {
        jdbc.update("""
                INSERT INTO places(id,name,dong_id,address,latitude,longitude,place_type) VALUES
                (101,'가경로당',10,'주소',37,126,NULL),
                (102,'가동 시설',10,'주소',37,126,'VILLAGE_HALL'),
                (104,'건강 쉼터',10,'주소',37,126,'SENIOR_CENTER'),
                (105,'새마을 회관',10,'주소',37,126,NULL),
                (106,'나경로당',10,'주소',37,126,NULL),
                (91,'나마을회관',20,'주소',37,126,NULL),
                (301,'다경로당',30,'주소',37,126,NULL),
                (302,'다마을회관',30,'주소',37,126,NULL),
                (401,'경로당 부속 공원',40,'주소',37,126,'OTHER')
                """);
        performedTest(1, 104, 1, "2025-11-30");
        performedTest(2, 104, 2, "2025-12-31");
        visit(3, 104, 1, "2026-02-28");
        performedTest(4, 104, 1, "2026-03-01");
        performedTest(5, 105, 1, "2025-12-31");
        performedTest(6, 102, 1, "2026-01-01");
        performedTest(7, 101, 1, "2026-01-31");
        performedTest(8, 106, 1, "2026-02-01");
        performedTest(9, 91, 1, "2025-12-31");
        visit(10, 301, 1, "2026-02-28");
        jdbc.update("""
                INSERT INTO test_requests(source_visit_id,place_id,test_type_id,requested_count,
                    requested_date,requested_by_organization_id,status,planned_organization_id,planned_date)
                VALUES (10,301,1,3,'2026-02-28',1,'PLANNED',2,'2026-03-02')
                """);

        mvc.perform(get(DONG_PRIORITIES).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-02-28"))
                .andExpect(jsonPath("$.data.items[*].rank", contains(1, 2, 3)))
                .andExpect(jsonPath("$.data.items[*].dongId", contains(30, 10, 20)))
                .andExpect(jsonPath("$.data.items[0].priorityPlace.placeId").value(301))
                .andExpect(jsonPath("$.data.items[0].priorityPlace.lastTestDate").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].priorityPlace.elapsedMonths").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].dongName").value("가동"))
                .andExpect(jsonPath("$.data.items[1].priorityPlace.placeId").value(104))
                .andExpect(jsonPath("$.data.items[1].priorityPlace.placeName").value("건강 쉼터"))
                .andExpect(jsonPath("$.data.items[1].priorityPlace.lastTestDate").value("2025-12-31"))
                .andExpect(jsonPath("$.data.items[1].priorityPlace.elapsedMonths").value(2));

        mvc.perform(get(PLACE_PRIORITIES, 10).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dongId").value(10))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-02-28"))
                .andExpect(jsonPath("$.data.items[*].rank", contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.data.items[*].placeId", contains(104, 105, 102, 101, 106)))
                .andExpect(jsonPath("$.data.items[*].elapsedMonths", contains(2, 2, 1, 1, 0)));
    }

    @Test
    void distinguishesAnEmptyDongFromAMissingDongAndRequiresAuthentication() throws Exception {
        mvc.perform(get(DONG_PRIORITIES).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mvc.perform(get(PLACE_PRIORITIES, 40).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mvc.perform(get(PLACE_PRIORITIES, 999).with(staff()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get(PLACE_PRIORITIES, "invalid").with(staff()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(get(DONG_PRIORITIES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get(PLACE_PRIORITIES, 10).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private void performedTest(long visitId, long placeId, long organizationId, String date) {
        visit(visitId, placeId, organizationId, date);
        jdbc.update("INSERT INTO visit_services(visit_id,service_code) VALUES (?,'A')", visitId);
        jdbc.update("INSERT INTO visit_tests(visit_id,test_type_id,test_count) VALUES (?,1,1)", visitId);
    }

    private void visit(long visitId, long placeId, long organizationId, String date) {
        jdbc.update("""
                INSERT INTO visits(id,visit_date,place_id,organization_id,created_by) VALUES (?,?,?,?,?)
                """, visitId, LocalDate.parse(date), placeId, organizationId, organizationId);
    }

    private RequestPostProcessor staff() {
        return authentication(new UsernamePasswordAuthenticationToken(new Actor(1L, "담당자 A", "STAFF", 1L),
                null, List.of()));
    }
}
