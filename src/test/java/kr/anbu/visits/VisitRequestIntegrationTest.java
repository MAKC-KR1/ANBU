package kr.anbu.visits;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.requests.RequestService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:visit-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class VisitRequestIntegrationTest {
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
        jdbc.update("INSERT INTO organizations(id,name,type) VALUES(1,'기관1','BRANCH'),(2,'기관2','BRANCH'),(3,'기관3','BRANCH')");
        jdbc.update("INSERT INTO users(id,login_id,password_hash,name,role,organization_id) VALUES(1,'one','hash','담당1','STAFF',1),(2,'two','hash','담당2','STAFF',2),(3,'three','hash','담당3','STAFF',3)");
        jdbc.update("INSERT INTO dongs(id,name) VALUES(1,'시연동')");
        jdbc.update("INSERT INTO places(id,name,dong_id,address,latitude,longitude) VALUES(1,'시연장소',1,'공공장소',37,126),(2,'다른장소',1,'공공장소2',37,126)");
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES(1,'검사1','A'),(2,'검사2','B')");
        jdbc.update("INSERT INTO test_type_organizations(test_type_id,organization_id) VALUES(1,1),(1,2),(2,1)");
        actor(1);
    }

    @AfterEach
    void clearActor() { SecurityContextHolder.clearContext(); }

    private static void actor(long org) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new Actor(org, "담당", "STAFF", org), null, List.of()));
    }
    private VisitInput input(int performed, int requested) {
        return new VisitInput(requests.today(), 1L, List.of("A"),
                performed == 0 ? List.of() : List.of(new VisitInput.TestCount(1L, performed)),
                requested == 0 ? List.of() : List.of(new VisitInput.TestCount(1L, requested)), List.of(), "");
    }
    private long id(Map<String, Object> created) { return (Long) created.get("visitId"); }
    private long request(int count) {
        Map<String, Object> created = visits.create(input(0, count));
        return ((List<Long>) created.get("createdRequestIds")).getFirst();
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test
    void partialCompletionPreservesPlanAndAggregatesUntilDone() {
        long request = request(10);
        requests.plan(request, requests.today().plusDays(1));
        long first = id(visits.create(input(6, 0)));
        Map<String, Object> partial = requests.complete(request, first, 6);
        assertThat(partial).containsEntry("status", "PLANNED").containsEntry("remainingCount", 4L)
                .containsEntry("isPartiallyFulfilled", true).containsEntry("completedDate", null);
        assertThatThrownBy(() -> requests.complete(request, first, 1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("REQUEST_CONFLICT"));
        requests.unplan(request);
        assertThat(requests.view(request)).containsEntry("status", "PENDING").containsEntry("completedCount", 6L);
        long last = id(visits.create(input(4, 0)));
        assertThat(requests.complete(request, last, 4)).containsEntry("status", "DONE")
                .containsEntry("remainingCount", 0L).containsEntry("completedDate", requests.today())
                .containsEntry("daysToComplete", 0L);
    }

    @Test
    void integratedFulfillmentAndBatchFailuresRollbackEveryWrite() {
        long request = request(5);
        long before = count("visits");
        VisitInput invalid = new VisitInput(requests.today(), 1L, List.of("A"),
                List.of(new VisitInput.TestCount(1L, 2)), List.of(new VisitInput.TestCount(1L, 1)),
                List.of(new VisitInput.Fulfillment(request, 3)), "");
        assertThatThrownBy(() -> visits.create(invalid)).isInstanceOf(ApiException.class);
        assertThat(count("visits")).isEqualTo(before);
        assertThat(count("test_requests")).isEqualTo(1);
        assertThat(count("request_fulfillments")).isZero();
        VisitInput future = new VisitInput(requests.today().plusDays(1), 1L, List.of("A"), null, null, null, null);
        VisitInput.Batch batch = new VisitInput.Batch(List.of(mapper.valueToTree(input(1, 0)), mapper.valueToTree(future)));
        assertThatThrownBy(() -> visits.batch(batch)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.details()).containsEntry("itemIndex", 1));
        assertThat(count("visits")).isEqualTo(before);
    }

    @Test
    void verifiesOrganizationOwnershipCapabilityAndLinkedVisitProtection() {
        long request = request(3);
        long visit = id(visits.create(input(3, 0)));
        actor(2);
        assertThatThrownBy(() -> requests.complete(request, visit, 1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        requests.plan(request, requests.today());
        actor(1);
        assertThatThrownBy(() -> requests.plan(request, requests.today())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("ALREADY_PLANNED"));
        assertThatThrownBy(() -> requests.complete(request, visit, 1)).isInstanceOf(ApiException.class);
        actor(2);
        requests.unplan(request);
        actor(3);
        assertThatThrownBy(() -> requests.plan(request, requests.today())).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        actor(1);
        requests.complete(request, visit, 1);
        assertThatThrownBy(() -> visits.delete(visit)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("LINKED_VISIT_CONFLICT"));
        assertThatThrownBy(() -> visits.update(visit, mapper.createObjectNode().put("placeId", 2)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LINKED_VISIT_CONFLICT"));
        jdbc.update("DELETE FROM test_type_organizations WHERE organization_id=1 AND test_type_id=1");
        visits.update(visit, mapper.createObjectNode().put("memo", "집계 확인"));
        assertThat(visits.view(visit)).containsEntry("memo", "집계 확인");
    }

    @Test
    void concurrentRequestsCannotConsumeMoreThanOneVisitsTestCapacity() throws Exception {
        long first = request(5);
        long second = request(5);
        long visit = id(visits.create(input(5, 0)));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (long request : List.of(first, second)) futures.add(executor.submit(() -> {
                actor(1);
                try {
                    start.await();
                    requests.complete(request, visit, 4);
                    return "OK";
                } catch (ApiException ex) { return ex.code(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "REQUEST_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT SUM(completed_count) FROM request_fulfillments WHERE visit_id=?", Long.class, visit)).isEqualTo(4);
    }

    @Test
    void concurrentPlanningHasExactlyOneOwner() throws Exception {
        long request = request(5);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (long organization : List.of(1L, 2L)) futures.add(executor.submit(() -> {
                actor(organization);
                try {
                    start.await();
                    requests.plan(request, requests.today());
                    return "OK";
                } catch (ApiException ex) { return ex.code(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "ALREADY_PLANNED");
        }
    }

    @Test
    void visitFilteringDoesNotDuplicateMultiServiceVisitsAndPatchPreservesOmittedFields() {
        VisitInput multiple = new VisitInput(requests.today(), 1L, List.of("A", "B"),
                List.of(new VisitInput.TestCount(1L, 3), new VisitInput.TestCount(2L, 2)), null, null, "기존");
        long visit = id(visits.create(multiple));
        assertThat(visits.list(null, null, null, null, null, null, null, 0, 20)).containsEntry("totalElements", 1L);
        assertThat(visits.list(null, null, null, "A", 1L, requests.today(), requests.today(), 0, 20)).containsEntry("totalElements", 1L);
        visits.update(visit, mapper.createObjectNode().put("visitDate", requests.today().minusDays(1).toString()));
        assertThat(visits.view(visit)).containsEntry("memo", "기존").containsEntry("serviceCodes", List.of("A", "B"));
        visits.delete(visit);
        assertThat(count("visits")).isZero();
        assertThat(count("visit_services")).isZero();
        assertThat(count("visit_tests")).isZero();
    }
}
