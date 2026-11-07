package kr.anbu.visits;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.requests.RequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jpa-domain-regression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class JpaDomainRegressionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService visits;
    @Autowired RequestService requests;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM request_fulfillments");
        jdbc.update("DELETE FROM test_requests");
        jdbc.update("DELETE FROM visit_tests");
        jdbc.update("DELETE FROM visit_services");
        jdbc.update("DELETE FROM visits");
        jdbc.update("DELETE FROM auth_tokens");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM test_type_organizations");
        jdbc.update("DELETE FROM test_types");
        jdbc.update("DELETE FROM visit_cycles");
        jdbc.update("DELETE FROM organization_dongs");
        jdbc.update("DELETE FROM places");
        jdbc.update("DELETE FROM dongs");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("INSERT INTO organizations(id,name,type) VALUES(1,'시연 기관','BRANCH')");
        jdbc.update("INSERT INTO users(id,login_id,password_hash,name,role,organization_id) VALUES(1,'test','hash','담당자','STAFF',1)");
        jdbc.update("INSERT INTO dongs(id,name) VALUES(1,'시연동')");
        jdbc.update("INSERT INTO places(id,name,dong_id,address,latitude,longitude) VALUES(1,'공공장소',1,'시연 주소',37,126)");
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES(1,'검사1','A'),(2,'검사2','B'),(3,'검사3','C')");
        jdbc.update("INSERT INTO test_type_organizations(test_type_id,organization_id) VALUES(1,1),(2,1),(3,1)");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new Actor(1L, "담당자", "STAFF", 1L), null, List.of()));
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replacingVisitChildrenReusesCompositeKeysAndDeletesRemovedRows() {
        VisitInput input = new VisitInput(requests.today(), 1L, List.of("A", "B"),
                List.of(new VisitInput.TestCount(1L, 3), new VisitInput.TestCount(2L, 2)), null, null, "보존할 메모");
        long visitId = (Long) visits.create(input).get("visitId");
        visits.update(visitId, mapper.valueToTree(Map.of(
                "serviceCodes", List.of("A", "C"),
                "performedTests", List.of(Map.of("testTypeId", 1, "count", 7), Map.of("testTypeId", 3, "count", 4)))));

        Map<String, Object> updated = visits.view(visitId);
        assertThat(updated).containsEntry("serviceCodes", List.of("A", "C"))
                .containsEntry("memo", "보존할 메모");
        assertThat(items(updated, "performedTests")).containsExactly(
                Map.of("testTypeId", 1L, "name", "검사1", "serviceCode", "A", "count", 7),
                Map.of("testTypeId", 3L, "name", "검사3", "serviceCode", "C", "count", 4));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_tests WHERE visit_id=? AND test_type_id=2", Long.class, visitId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_services WHERE visit_id=? AND service_code='B'", Long.class, visitId)).isZero();

        visits.delete(visitId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visits WHERE id=?", Long.class, visitId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_tests WHERE visit_id=?", Long.class, visitId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_services WHERE visit_id=?", Long.class, visitId)).isZero();
        assertThatThrownBy(() -> visits.view(visitId)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void countPaginationOrdersByRemainingQuantityAndRetainsFullTotals() {
        long oldest = createRequest(requests.today().minusDays(2), 10);
        long middle = createRequest(requests.today().minusDays(1), 8);
        long newest = createRequest(requests.today(), 5);
        long visitId = (Long) visits.create(new VisitInput(requests.today(), 1L, List.of("A"),
                List.of(new VisitInput.TestCount(1L, 7)), null, null, null)).get("visitId");
        requests.complete(oldest, visitId, 7);

        Map<String, Object> firstPage = requestPage(0);
        Map<String, Object> secondPage = requestPage(1);
        Map<String, Object> thirdPage = requestPage(2);
        assertThat(firstPage).containsEntry("totalElements", 3L).containsEntry("totalPages", 3L);
        assertThat(secondPage).containsEntry("page", 1).containsEntry("totalElements", 3L).containsEntry("totalPages", 3L);
        assertThat(thirdPage).containsEntry("page", 2).containsEntry("totalElements", 3L).containsEntry("totalPages", 3L);
        assertThat(items(firstPage, "content").getFirst()).containsEntry("requestId", middle).containsEntry("remainingCount", 8L);
        assertThat(items(secondPage, "content").getFirst()).containsEntry("requestId", newest).containsEntry("remainingCount", 5L);
        assertThat(items(thirdPage, "content").getFirst()).containsEntry("requestId", oldest).containsEntry("remainingCount", 3L);
    }

    private long createRequest(LocalDate date, int count) {
        Map<String, Object> result = visits.create(new VisitInput(date, 1L, List.of("A"), null,
                List.of(new VisitInput.TestCount(1L, count)), null, null));
        return ((Number) ((List<?>) result.get("createdRequestIds")).getFirst()).longValue();
    }

    private Map<String, Object> requestPage(int page) {
        return requests.list(true, "PENDING,PLANNED", null, null, null, "count", page, 1);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> result, String field) {
        return (List<Map<String, Object>>) result.get(field);
    }
}
