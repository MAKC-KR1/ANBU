package kr.anbu.reference;

import java.time.LocalDate;
import java.util.List;
import kr.anbu.auth.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reference-statistics-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class ReferenceStatisticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("""
                INSERT INTO organizations(id,name,type)
                VALUES (1,'기관 A','BRANCH'),(2,'기관 B','HEALTH_CENTER'),(3,'활동 없는 기관','ADMIN_CENTER')
                """);
        jdbc.update("INSERT INTO dongs(id,name) VALUES (10,'통계 테스트 동')");
        jdbc.update("""
                INSERT INTO places(id,name,dong_id,address,latitude,longitude)
                VALUES (100,'경로당 A',10,'공공장소 A',37,126),(101,'경로당 B',10,'공공장소 B',37,126)
                """);
        jdbc.update("""
                INSERT INTO users(id,login_id,password_hash,name,role,organization_id)
                VALUES (1,'statistics-a','unused','담당자 A','STAFF',1),
                       (2,'statistics-b','unused','담당자 B','STAFF',2)
                """);
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES (1,'통계 테스트 검사','A')");
        jdbc.update("INSERT INTO test_type_organizations(test_type_id,organization_id) VALUES (1,1),(1,2)");
    }

    @Test
    void monthlyStatisticsUseVisitAndRequestDatesWithExclusiveNextMonthBoundary() throws Exception {
        visit(1, 1, 100, "2026-01-31");
        visit(2, 1, 100, "2026-02-01");
        visit(3, 1, 100, "2026-02-28");
        visit(4, 1, 101, "2026-02-28");
        visit(5, 1, 100, "2026-03-01");
        visit(6, 2, 101, "2026-02-15");
        request(1, 1, 1, 100, "2026-01-31", 9, "PENDING");
        request(2, 2, 1, 100, "2026-02-01", 2, "PENDING");
        request(3, 3, 1, 100, "2026-02-28", 6, "PENDING");
        request(4, 5, 1, 100, "2026-03-01", 100, "PENDING");
        request(5, 6, 2, 101, "2026-02-15", 4, "PENDING");

        mvc.perform(get("/api/v1/stats/organizations").param("month", "2026-02").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].organization.id").value(1))
                .andExpect(jsonPath("$.data[0].visitCount").value(3))
                .andExpect(jsonPath("$.data[0].placeCount").value(2))
                .andExpect(jsonPath("$.data[0].requestCreatedCount").value(2))
                .andExpect(jsonPath("$.data[0].requestCompletedCount").value(0))
                .andExpect(jsonPath("$.data[0].previousMonthVisitCount").value(1))
                .andExpect(jsonPath("$.data[1].visitCount").value(1))
                .andExpect(jsonPath("$.data[1].placeCount").value(1))
                .andExpect(jsonPath("$.data[1].requestCreatedCount").value(1))
                .andExpect(jsonPath("$.data[1].previousMonthVisitCount").value(0))
                .andExpect(jsonPath("$.data[2].visitCount").value(0))
                .andExpect(jsonPath("$.data[2].placeCount").value(0))
                .andExpect(jsonPath("$.data[2].requestCreatedCount").value(0))
                .andExpect(jsonPath("$.data[2].requestCompletedCount").value(0));
    }

    @Test
    void completedRequestsCountOncePerOrganizationParticipatingInTheCompletionMonth() throws Exception {
        visit(1, 1, 100, "2026-01-01");
        visit(2, 1, 100, "2026-01-31");
        visit(3, 2, 100, "2026-02-15");
        visit(4, 1, 100, "2026-02-01");
        visit(5, 1, 100, "2026-02-28");
        visit(6, 2, 100, "2026-02-28");
        visit(7, 2, 100, "2026-03-01");

        // A participated in January, but B completed this request in February.
        request(1, 1, 1, 100, "2026-01-01", 5, "DONE");
        fulfillment(1, 2, 2);
        fulfillment(1, 3, 3);

        // Two February visits by A still count as one completed request for A.
        request(2, 4, 1, 100, "2026-02-01", 5, "DONE");
        fulfillment(2, 4, 2);
        fulfillment(2, 5, 1);
        fulfillment(2, 6, 2);

        request(3, 4, 1, 100, "2026-02-01", 2, "PENDING");
        fulfillment(3, 5, 1);

        // Completion exactly on March 1 must not contribute to February.
        request(4, 4, 1, 100, "2026-02-01", 4, "DONE");
        fulfillment(4, 5, 2);
        fulfillment(4, 7, 2);

        request(5, 1, 1, 100, "2026-01-01", 7, "DONE");
        fulfillment(5, 2, 7);

        mvc.perform(get("/api/v1/stats/organizations").param("month", "2026-01").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].requestCompletedCount").value(1))
                .andExpect(jsonPath("$.data[1].requestCompletedCount").value(0));
        mvc.perform(get("/api/v1/stats/organizations").param("month", "2026-02").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].requestCompletedCount").value(1))
                .andExpect(jsonPath("$.data[1].requestCompletedCount").value(2))
                .andExpect(jsonPath("$.data[2].requestCompletedCount").value(0));
        mvc.perform(get("/api/v1/stats/organizations").param("month", "2026-03").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].requestCompletedCount").value(0))
                .andExpect(jsonPath("$.data[1].requestCompletedCount").value(1));
    }

    private void visit(long id, long organizationId, long placeId, String date) {
        jdbc.update("""
                INSERT INTO visits(id,visit_date,place_id,organization_id,created_by)
                VALUES (?,?,?,?,?)
                """, id, LocalDate.parse(date), placeId, organizationId, organizationId);
        jdbc.update("INSERT INTO visit_services(visit_id,service_code) VALUES (?,'A')", id);
        jdbc.update("INSERT INTO visit_tests(visit_id,test_type_id,test_count) VALUES (?,1,100)", id);
    }

    private void request(long id, long sourceVisitId, long organizationId, long placeId,
            String date, int count, String status) {
        jdbc.update("""
                INSERT INTO test_requests(id,source_visit_id,place_id,test_type_id,requested_count,
                                          requested_date,requested_by_organization_id,status)
                VALUES (?,?,?,1,?,?,?,?)
                """, id, sourceVisitId, placeId, count, LocalDate.parse(date), organizationId, status);
    }

    private void fulfillment(long requestId, long visitId, int count) {
        jdbc.update("INSERT INTO request_fulfillments(request_id,visit_id,completed_count) VALUES (?,?,?)",
                requestId, visitId, count);
    }

    private RequestPostProcessor staff() {
        return authentication(new UsernamePasswordAuthenticationToken(new Actor(1L, "담당자 A", "STAFF", 1L),
                null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }
}
