package kr.anbu.visits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.requests.RequestService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static kr.anbu.requests.RequestService.*;

@Service
public class VisitService {
    private final JdbcTemplate jdbc;
    private final RequestService requests;
    private final ObjectMapper mapper;
    private final Validator validator;

    public VisitService(JdbcTemplate jdbc, RequestService requests, ObjectMapper mapper, Validator validator) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Transactional
    public Map<String, Object> create(VisitInput input) {
        validate(input);
        long organizationId = Actor.current().organizationId();
        validatePlace(input.placeId());
        Map<Long, Integer> performed = testCounts(input.performedTests(), input.serviceCodes(), true);
        Map<Long, Integer> requested = testCounts(input.requestedTests(), input.serviceCodes(), false);
        long visitId = insertId("INSERT INTO visits(visit_date,place_id,organization_id,created_by,memo) VALUES(?,?,?,?,?)",
                input.visitDate(), input.placeId(), organizationId, Actor.current().id(), input.memo());
        saveServicesAndTests(visitId, input.serviceCodes(), performed);
        List<Long> requestIds = new ArrayList<>();
        for (var test : requested.entrySet()) {
            requestIds.add(insertId("""
                    INSERT INTO test_requests(source_visit_id,place_id,test_type_id,requested_count,requested_date,requested_by_organization_id)
                    VALUES(?,?,?,?,?,?)
                    """, visitId, input.placeId(), test.getKey(), test.getValue(), input.visitDate(), organizationId));
        }
        List<Map<String, Object>> fulfillments = new ArrayList<>();
        // Stable request lock ordering prevents two multi-request visits from deadlocking.
        List<VisitInput.Fulfillment> ordered = input.fulfilledRequests().stream()
                .sorted(Comparator.comparing(VisitInput.Fulfillment::requestId)).toList();
        for (var fulfillment : ordered) {
            Map<String, Object> result = requests.complete(fulfillment.requestId(), visitId, fulfillment.completedCount());
            result.remove("visitId");
            result.remove("completedDate");
            result.remove("daysToComplete");
            fulfillments.add(result);
        }
        return fields("visitId", visitId, "createdRequestIds", requestIds, "fulfillments", fulfillments);
    }

    @Transactional
    public Map<String, Object> batch(VisitInput.Batch batch) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < batch.items().size(); i++) {
            try {
                results.add(create(mapper.convertValue(batch.items().get(i), VisitInput.class)));
            } catch (ApiException ex) {
                throw new ApiException(ex.status(), ex.code(), ex.getMessage(), fields("itemIndex", i));
            } catch (PessimisticLockingFailureException | DataIntegrityViolationException ex) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "동시에 변경된 데이터와 충돌합니다.", fields("itemIndex", i));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "방문 입력 형식이 올바르지 않습니다.", fields("itemIndex", i));
            }
        }
        return fields("items", results);
    }

    private void validate(VisitInput input) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw invalid("필수 값과 검사 건수를 확인해 주세요.");
        }
        if (input.visitDate().isAfter(requests.today())) {
            throw invalid("방문일은 미래 날짜일 수 없습니다.");
        }
        if (new HashSet<>(input.serviceCodes()).size() != input.serviceCodes().size()) {
            throw invalid("서비스 유형은 중복할 수 없습니다.");
        }
        if (input.fulfilledRequests().stream().map(VisitInput.Fulfillment::requestId).distinct().count() != input.fulfilledRequests().size()) {
            throw conflict("같은 요청을 한 방문에 중복 연결할 수 없습니다.");
        }
    }

    private void validatePlace(long id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE id=?", Long.class, id) == 0) {
            throw missing();
        }
    }

    private Map<Long, Integer> testCounts(List<VisitInput.TestCount> tests, List<String> services, boolean performed) {
        Map<Long, Integer> counts = new TreeMap<>();
        for (var test : tests) {
            List<String> codes = jdbc.query("SELECT service_code FROM test_types WHERE id=?", (rs, n) -> rs.getString(1), test.testTypeId());
            if (codes.isEmpty()) {
                throw missing();
            }
            if (performed && !services.contains(codes.getFirst())) {
                throw invalid("제공 검사의 서비스 유형을 선택해 주세요.");
            }
            if (performed && !requests.capable(test.testTypeId(), Actor.current().organizationId())) {
                throw forbidden();
            }
            try {
                counts.merge(test.testTypeId(), test.count(), Math::addExact);
            } catch (ArithmeticException ex) {
                throw invalid("검사 건수가 허용 범위를 초과합니다.");
            }
        }
        return counts;
    }

    private void saveServicesAndTests(long id, List<String> services, Map<Long, Integer> tests) {
        for (String service : services) {
            jdbc.update("INSERT INTO visit_services(visit_id,service_code) VALUES(?,?)", id, service);
        }
        for (var test : tests.entrySet()) {
            jdbc.update("INSERT INTO visit_tests(visit_id,test_type_id,test_count) VALUES(?,?,?)", id, test.getKey(), test.getValue());
        }
    }

    private long insertId(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private Map<String, Object> ownLockedVisit(long id) {
        List<Map<String, Object>> visits = jdbc.query("SELECT organization_id FROM visits WHERE id=? FOR UPDATE",
                (rs, n) -> fields("organizationId", rs.getLong(1)), id);
        if (visits.isEmpty()) {
            throw missing();
        }
        if (!Objects.equals(visits.getFirst().get("organizationId"), Actor.current().organizationId())) {
            throw forbidden();
        }
        return view(id);
    }

    private boolean linked(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM test_requests WHERE source_visit_id=?", Long.class, id) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM request_fulfillments WHERE visit_id=?", Long.class, id) > 0;
    }

    private ApiException linkedConflict() {
        return new ApiException(HttpStatus.CONFLICT, "LINKED_VISIT_CONFLICT", "검사 요청 또는 처리 실적이 연결된 방문은 메모만 수정할 수 있습니다.");
    }

    @Transactional
    public Map<String, Object> update(long id, JsonNode patch) {
        Map<String, Object> current = ownLockedVisit(id);
        Set<String> allowed = Set.of("visitDate", "placeId", "serviceCodes", "performedTests", "memo");
        if (!patch.isObject() || patch.isEmpty()) {
            throw invalid("수정할 필드를 입력해 주세요.");
        }
        Iterator<String> names = patch.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw invalid("지원하지 않는 수정 필드입니다.");
            }
        }
        if (linked(id) && (patch.size() != 1 || !patch.has("memo"))) {
            throw linkedConflict();
        }
        if (patch.size() == 1 && patch.has("memo")) {
            if (!patch.get("memo").isTextual() || patch.get("memo").textValue().length() > 2000) {
                throw invalid("메모는 2000자 이하의 문자열이어야 합니다.");
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update("UPDATE visits SET memo=?,updated_at=? WHERE id=?", patch.get("memo").textValue(), now, id);
            return fields("visitId", id, "updatedAt", now);
        }
        ObjectNode merged = mapper.createObjectNode();
        for (String field : allowed) {
            merged.set(field, mapper.valueToTree(current.get(field)));
        }
        // Response test entries contain read-only name/serviceCode properties.
        merged.set("performedTests", mapper.valueToTree(jdbc.query("SELECT test_type_id,test_count FROM visit_tests WHERE visit_id=?",
                (rs, n) -> fields("testTypeId", rs.getLong(1), "count", rs.getInt(2)), id)));
        patch.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        if (patch.has("memo") && !patch.get("memo").isTextual()) {
            throw invalid("메모는 문자열이어야 합니다.");
        }
        VisitInput updated;
        try {
            updated = mapper.convertValue(merged, VisitInput.class);
        } catch (IllegalArgumentException ex) {
            throw invalid("수정 입력 형식이 올바르지 않습니다.");
        }
        validate(updated);
        validatePlace(updated.placeId());
        Map<Long, Integer> tests = testCounts(updated.performedTests(), updated.serviceCodes(), true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE visits SET visit_date=?,place_id=?,memo=?,updated_at=? WHERE id=?", updated.visitDate(), updated.placeId(), updated.memo(), now, id);
        if (patch.has("serviceCodes") || patch.has("performedTests")) {
            jdbc.update("DELETE FROM visit_services WHERE visit_id=?", id);
            jdbc.update("DELETE FROM visit_tests WHERE visit_id=?", id);
            saveServicesAndTests(id, updated.serviceCodes(), tests);
        }
        return fields("visitId", id, "updatedAt", now);
    }

    @Transactional
    public Map<String, Object> delete(long id) {
        ownLockedVisit(id);
        if (linked(id)) {
            throw linkedConflict();
        }
        jdbc.update("DELETE FROM visit_services WHERE visit_id=?", id);
        jdbc.update("DELETE FROM visit_tests WHERE visit_id=?", id);
        jdbc.update("DELETE FROM visits WHERE id=?", id);
        return fields("visitId", id, "deleted", true);
    }

    public Map<String, Object> view(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT v.*,o.name AS organization_name FROM visits v JOIN organizations o ON o.id=v.organization_id WHERE v.id=?
                """, (rs, n) -> fields("visitId", id, "placeId", rs.getLong("place_id"), "organization", fields("id", rs.getLong("organization_id"), "name", rs.getString("organization_name")),
                "visitDate", rs.getObject("visit_date", LocalDate.class), "memo", rs.getString("memo"),
                "createdAt", rs.getObject("created_at", OffsetDateTime.class), "updatedAt", rs.getObject("updated_at", OffsetDateTime.class)), id);
        if (rows.isEmpty()) {
            throw missing();
        }
        Map<String, Object> visit = rows.getFirst();
        visit.put("serviceCodes", jdbc.query("SELECT service_code FROM visit_services WHERE visit_id=? ORDER BY service_code", (rs, n) -> rs.getString(1), id));
        visit.put("performedTests", jdbc.query("""
                SELECT vt.*,t.name,t.service_code FROM visit_tests vt JOIN test_types t ON t.id=vt.test_type_id WHERE vt.visit_id=? ORDER BY t.id
                """, (rs, n) -> fields("testTypeId", rs.getLong("test_type_id"), "name", rs.getString("name"), "serviceCode", rs.getString("service_code"), "count", rs.getInt("test_count")), id));
        visit.put("createdRequestIds", jdbc.query("SELECT id FROM test_requests WHERE source_visit_id=? ORDER BY id", (rs, n) -> rs.getLong(1), id));
        visit.put("fulfillments", jdbc.query("SELECT request_id,completed_count FROM request_fulfillments WHERE visit_id=? ORDER BY request_id",
                (rs, n) -> fields("requestId", rs.getLong(1), "completedCount", rs.getInt(2)), id));
        return visit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Long placeId, Long dongId, Long organizationId, String serviceCode,
                                    Long testTypeId, LocalDate fromDate, LocalDate toDate, int page, int size) {
        validatePage(page, size);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw invalid("시작일은 종료일 이전이어야 합니다.");
        }
        StringBuilder where = new StringBuilder(" FROM visits v JOIN places p ON p.id=v.place_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addFilter(where, args, "v.place_id", placeId);
        addFilter(where, args, "p.dong_id", dongId);
        addFilter(where, args, "v.organization_id", organizationId);
        if (serviceCode != null) {
            if (!Set.of("A", "B", "C", "D").contains(serviceCode)) {
                throw invalid("서비스 유형이 올바르지 않습니다.");
            }
            where.append(" AND EXISTS(SELECT 1 FROM visit_services s WHERE s.visit_id=v.id AND s.service_code=?)");
            args.add(serviceCode);
        }
        if (testTypeId != null) {
            if (testTypeId < 1) {
                throw invalid("검사 유형 ID는 양수여야 합니다.");
            }
            where.append(" AND EXISTS(SELECT 1 FROM visit_tests t WHERE t.visit_id=v.id AND t.test_type_id=?)");
            args.add(testTypeId);
        }
        if (fromDate != null) {
            where.append(" AND v.visit_date>=?");
            args.add(fromDate);
        }
        if (toDate != null) {
            where.append(" AND v.visit_date<=?");
            args.add(toDate);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<Long> ids = jdbc.query("SELECT v.id" + where + " ORDER BY v.visit_date DESC,v.id DESC LIMIT ? OFFSET ?", (rs, n) -> rs.getLong(1), args.toArray());
        return fields("content", ids.stream().map(this::view).toList(), "page", page, "size", size,
                "totalElements", total, "totalPages", (total + size - 1) / size);
    }
}
