package kr.anbu.map;

import java.time.Clock;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:map-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class MapApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);
        jdbc.update("INSERT INTO organizations(id,name,type) VALUES (1,'기관 A','BRANCH'),(2,'기관 B','HEALTH_CENTER')");
        jdbc.update("INSERT INTO dongs(id,name) VALUES (10,'테스트 동')");
        jdbc.update("""
                INSERT INTO places(id,name,dong_id,address,latitude,longitude)
                VALUES (100,'테스트 경로당',10,'테스트 공공장소',37.25,126.59)
                """);
        jdbc.update("""
                INSERT INTO users(id,login_id,password_hash,name,role,organization_id)
                VALUES (1,'map-user','unused','담당자','STAFF',1)
                """);
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES (1,'검사 A','A'),(2,'검사 B','B')");
        jdbc.update("INSERT INTO test_type_organizations(test_type_id,organization_id) VALUES (1,1),(2,1),(1,2)");
    }

    @Test
    void mapUsesServiceSpecificDatesAndExactGapBoundaries() throws Exception {
        visit(1, 30, "A");
        visit(2, 31, "B");
        visit(3, 90, "C");
        visit(4, 91, "D");

        mvc.perform(get("/api/v1/map/places").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].daysSinceLastVisit").value(30))
                .andExpect(jsonPath("$.data[0].gapLevel").value("RECENT"));
        assertGap("A", "RECENT", 30);
        assertGap("B", "WARNING", 31);
        assertGap("C", "WARNING", 90);
        assertGap("D", "LONG_GAP", 91);

        jdbc.update("INSERT INTO visit_cycles(organization_id,service_code,cycle_days) VALUES (1,'A',29)");
        mvc.perform(get("/api/v1/places/100/summary").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyByService", hasSize(4)))
                .andExpect(jsonPath("$.data.historyByService[0].isOverdue").value(true))
                .andExpect(jsonPath("$.data.historyByService[1].isOverdue").value(false))
                .andExpect(jsonPath("$.data.historyByService[2].isOverdue").value(true))
                .andExpect(jsonPath("$.data.historyByService[3].cycleDays").isEmpty())
                .andExpect(jsonPath("$.data.historyByService[3].isOverdue").value(false));
    }

    @Test
    void mapReturnsEveryRegisteredPlaceAndNoRecordServices() throws Exception {
        for (long id = 101; id < 126; id++) {
            jdbc.update("INSERT INTO places(id,name,dong_id,address,latitude,longitude) VALUES (?,?,10,'공공장소',37,126)",
                    id, "테스트 장소 " + id);
        }
        mvc.perform(get("/api/v1/map/places").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(26)))
                .andExpect(jsonPath("$.data[0].gapLevel").value("NO_RECORD"))
                .andExpect(jsonPath("$.data[0].lastVisitDate").isEmpty())
                .andExpect(jsonPath("$.data[0].daysSinceLastVisit").isEmpty())
                .andExpect(jsonPath("$.data[0].overdueServiceCodes", hasSize(0)))
                .andExpect(jsonPath("$.data[0].noRecordServiceCodes", hasSize(4)));
        mvc.perform(get("/api/v1/map/places").param("hasPendingRequest", "false").with(staff()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(26)));
        mvc.perform(get("/api/v1/map/places").param("hasPendingRequest", "true").with(staff()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void summaryLimitsEachServiceHistoryAndIncludesOnlyItsPerformedTests() throws Exception {
        visit(1, 10, "A");
        visit(2, 2, "A", "B");
        jdbc.update("INSERT INTO visit_tests(visit_id,test_type_id,test_count) VALUES (2,1,6),(2,2,4)");

        mvc.perform(get("/api/v1/places/100/summary").param("historyLimit", "1").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyByService[0].visits", hasSize(1)))
                .andExpect(jsonPath("$.data.historyByService[0].visits[0].visitId").value(2))
                .andExpect(jsonPath("$.data.historyByService[0].visits[0].performedTests", hasSize(1)))
                .andExpect(jsonPath("$.data.historyByService[0].visits[0].performedTests[0].testTypeId").value(1))
                .andExpect(jsonPath("$.data.historyByService[1].visits[0].performedTests[0].testTypeId").value(2))
                .andExpect(jsonPath("$.data.historyByService[2].visits", hasSize(0)))
                .andExpect(jsonPath("$.data.historyByService[2].gapLevel").value("NO_RECORD"));
    }

    @Test
    void pendingAndOutstandingCountsUseRemainingQuantityAndPlansDoNotResetVisits() throws Exception {
        visit(1, 50, "A");
        visit(2, 40, "A");
        jdbc.update("INSERT INTO visit_tests(visit_id,test_type_id,test_count) VALUES (2,1,6)");
        jdbc.update("""
                INSERT INTO test_requests(id,source_visit_id,place_id,test_type_id,requested_count,
                                          requested_date,requested_by_organization_id,status,
                                          planned_organization_id,planned_date)
                VALUES (1,1,100,1,10,?,1,'PLANNED',2,?)
                """, today.minusDays(50), today.plusDays(10));
        jdbc.update("""
                INSERT INTO test_requests(id,source_visit_id,place_id,test_type_id,requested_count,
                                          requested_date,requested_by_organization_id,status)
                VALUES (2,1,100,2,2,?,1,'PENDING')
                """, today.minusDays(50));
        jdbc.update("INSERT INTO request_fulfillments(request_id,visit_id,completed_count) VALUES (1,2,6)");

        mvc.perform(get("/api/v1/map/places").param("hasPendingRequest", "true").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].daysSinceLastVisit").value(40))
                .andExpect(jsonPath("$.data[0].pendingRequestCount").value(1))
                .andExpect(jsonPath("$.data[0].pendingRequestedCount").value(2))
                .andExpect(jsonPath("$.data[0].outstandingRequestCount").value(2))
                .andExpect(jsonPath("$.data[0].outstandingRequestedCount").value(6));
        mvc.perform(get("/api/v1/places/100/summary").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requests[0].remainingCount").value(4))
                .andExpect(jsonPath("$.data.requests[0].isPartiallyFulfilled").value(true))
                .andExpect(jsonPath("$.data.requests[0].assignableToMe").value(false))
                .andExpect(jsonPath("$.data.requests[0].fulfillments[0].completedCount").value(6))
                .andExpect(jsonPath("$.data.outstandingRequestedCount").value(6));
    }

    @Test
    void invalidFiltersMissingPlaceAndUnauthenticatedCallsUseCommonErrors() throws Exception {
        mvc.perform(get("/api/v1/map/places").param("serviceCode", "E").with(staff()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/v1/map/places").param("gapLevel", "UNKNOWN").with(staff()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/100/summary").param("historyLimit", "51").with(staff()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/999/summary").with(staff()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/map/places"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private void visit(long id, int daysAgo, String... services) {
        jdbc.update("INSERT INTO visits(id,visit_date,place_id,organization_id,created_by) VALUES (?,?,100,1,1)",
                id, today.minusDays(daysAgo));
        for (String service : services) {
            jdbc.update("INSERT INTO visit_services(visit_id,service_code) VALUES (?,?)", id, service);
        }
    }

    private void assertGap(String serviceCode, String expectedGap, int expectedDays) throws Exception {
        mvc.perform(get("/api/v1/map/places").param("serviceCode", serviceCode).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].daysSinceLastVisit").value(expectedDays))
                .andExpect(jsonPath("$.data[0].gapLevel").value(expectedGap));
    }

    private RequestPostProcessor staff() {
        return authentication(new UsernamePasswordAuthenticationToken(new Actor(1L, "담당자", "STAFF", 1L),
                null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }
}
