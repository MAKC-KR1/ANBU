package kr.anbu.reference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReferenceController {
    private static final Set<String> ORGANIZATION_TYPES = Set.of(
            "HEALTH_CENTER", "BRANCH", "ADMIN_CENTER", "COUNCIL", "WELFARE_CENTER");
    private static final Set<String> SERVICE_CODES = Set.of("A", "B", "C", "D");
    private final JdbcTemplate jdbc;
    private final VisitCycleService cycles;

    public ReferenceController(JdbcTemplate jdbc, VisitCycleService cycles) {
        this.jdbc = jdbc;
        this.cycles = cycles;
    }

    @GetMapping("/organizations")
    public ApiResponse<?> organizations(@RequestParam(required = false) String type) {
        if (type != null && !ORGANIZATION_TYPES.contains(type)) {
            throw invalid("기관 유형이 올바르지 않습니다.");
        }
        String sql = "SELECT id, name, type FROM organizations";
        Object[] args = new Object[0];
        if (type != null) {
            sql += " WHERE type = ?";
            args = new Object[]{type};
        }
        return ApiResponse.ok(jdbc.query(sql + " ORDER BY id", (rs, row) -> {
            long id = rs.getLong("id");
            return Map.of("id", id, "name", rs.getString("name"), "type", rs.getString("type"),
                    "managedDongIds", jdbc.queryForList(
                            "SELECT dong_id FROM organization_dongs WHERE organization_id = ? ORDER BY dong_id",
                            Long.class, id));
        }, args));
    }

    @GetMapping("/organizations/me/visit-cycles")
    public ApiResponse<?> visitCycles() {
        return ApiResponse.ok(cycleViews());
    }

    @PutMapping("/organizations/me/visit-cycles")
    @Transactional
    public ApiResponse<?> updateCycles(@Valid @RequestBody CycleInput input) {
        requireManager();
        if (input.cycles().stream().map(Cycle::serviceCode).distinct().count() != input.cycles().size()) {
            throw invalid("서비스 코드는 중복될 수 없습니다.");
        }
        long organizationId = Actor.current().organizationId();
        jdbc.queryForObject("SELECT id FROM organizations WHERE id = ? FOR UPDATE", Long.class, organizationId);
        for (Cycle cycle : input.cycles()) {
            if (!SERVICE_CODES.contains(cycle.serviceCode())) {
                throw invalid("서비스 코드는 A~D여야 합니다.");
            }
            jdbc.update("DELETE FROM visit_cycles WHERE organization_id = ? AND service_code = ?",
                    organizationId, cycle.serviceCode());
            jdbc.update("INSERT INTO visit_cycles (organization_id, service_code, cycle_days) VALUES (?, ?, ?)",
                    organizationId, cycle.serviceCode(), cycle.cycleDays());
        }
        return ApiResponse.ok(cycleViews());
    }

    private List<Cycle> cycleViews() {
        return cycles.cycles(Actor.current().organizationId()).entrySet().stream()
                .map(entry -> new Cycle(entry.getKey(), entry.getValue())).toList();
    }

    @GetMapping("/test-types")
    public ApiResponse<?> testTypes() {
        return ApiResponse.ok(jdbc.query("SELECT id, name, service_code FROM test_types ORDER BY id", (rs, row) ->
                Map.of("id", rs.getLong("id"), "name", rs.getString("name"),
                        "serviceCode", rs.getString("service_code"), "responsibleOrganizations",
                        jdbc.query("""
                                SELECT o.id, o.name FROM organizations o
                                JOIN test_type_organizations t ON t.organization_id = o.id
                                WHERE t.test_type_id = ? ORDER BY o.id
                                """, (org, index) -> Map.of("id", org.getLong("id"), "name", org.getString("name")),
                                rs.getLong("id")))));
    }

    @PutMapping("/test-types/{testTypeId}/organizations")
    @Transactional
    public ApiResponse<?> assignOrganizations(@PathVariable long testTypeId,
                                               @Valid @RequestBody AssignmentInput input) {
        if (!Actor.current().role().equals("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자 권한이 필요합니다.");
        }
        if (new HashSet<>(input.organizationIds()).size() != input.organizationIds().size()) {
            throw invalid("기관 ID는 중복될 수 없습니다.");
        }
        List<String> names = jdbc.queryForList("SELECT name FROM test_types WHERE id = ? FOR UPDATE",
                String.class, testTypeId);
        if (names.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "검사 종류를 찾을 수 없습니다.");
        }
        for (Long id : input.organizationIds()) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = ?", Long.class, id) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "기관을 찾을 수 없습니다.");
            }
        }
        jdbc.update("DELETE FROM test_type_organizations WHERE test_type_id = ?", testTypeId);
        for (Long id : input.organizationIds()) {
            jdbc.update("INSERT INTO test_type_organizations (test_type_id, organization_id) VALUES (?, ?)",
                    testTypeId, id);
        }
        return ApiResponse.ok(Map.of("id", testTypeId, "name", names.getFirst(),
                "responsibleOrganizationIds", input.organizationIds()));
    }

    @GetMapping("/dongs")
    public ApiResponse<?> dongs() {
        return ApiResponse.ok(jdbc.query("SELECT * FROM dongs ORDER BY id", (rs, row) -> {
            Map<String, Object> dong = new LinkedHashMap<>();
            dong.put("id", rs.getLong("id"));
            dong.put("name", rs.getString("name"));
            dong.put("gu", rs.getString("gu"));
            dong.put("elderlyRate", rs.getBigDecimal("elderly_rate"));
            dong.put("recipientRate", rs.getBigDecimal("recipient_rate"));
            dong.put("singleHouseholdRate", rs.getBigDecimal("single_household_rate"));
            dong.put("baseDate", rs.getObject("indicators_base_date", LocalDate.class));
            dong.put("indicatorMetadata", jdbc.query("""
                    SELECT indicator_key, source, base_date, denominator FROM dong_indicator_metadata
                    WHERE dong_id = ? ORDER BY indicator_key
                    """, this::indicatorMetadata, rs.getLong("id")));
            return dong;
        }));
    }

    private Map<String, Object> indicatorMetadata(ResultSet rs, int row) throws SQLException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", rs.getString("indicator_key"));
        metadata.put("source", rs.getString("source"));
        metadata.put("baseDate", rs.getObject("base_date", LocalDate.class));
        metadata.put("denominator", rs.getString("denominator"));
        return metadata;
    }

    @GetMapping("/stats/organizations")
    public ApiResponse<?> statistics(@RequestParam String month) {
        YearMonth period;
        try {
            if (!month.matches("\\d{4}-\\d{2}")) {
                throw invalid("month는 YYYY-MM 형식이어야 합니다.");
            }
            period = YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw invalid("month는 유효한 YYYY-MM이어야 합니다.");
        }
        LocalDate start = period.atDay(1);
        LocalDate end = period.plusMonths(1).atDay(1);
        LocalDate previousStart = period.minusMonths(1).atDay(1);
        return ApiResponse.ok(jdbc.query("SELECT id, name FROM organizations ORDER BY id", (rs, row) -> {
            long orgId = rs.getLong("id");
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("organization", Map.of("id", orgId, "name", rs.getString("name")));
            stats.put("visitCount", count("SELECT COUNT(*) FROM visits WHERE organization_id = ? AND visit_date >= ? AND visit_date < ?", orgId, start, end));
            stats.put("placeCount", count("SELECT COUNT(DISTINCT place_id) FROM visits WHERE organization_id = ? AND visit_date >= ? AND visit_date < ?", orgId, start, end));
            stats.put("requestCreatedCount", count("SELECT COUNT(*) FROM test_requests WHERE requested_by_organization_id = ? AND requested_date >= ? AND requested_date < ?", orgId, start, end));
            stats.put("requestCompletedCount", count("""
                    SELECT COUNT(DISTINCT r.id) FROM test_requests r
                    JOIN request_fulfillments f ON f.request_id = r.id
                    JOIN visits v ON v.id = f.visit_id
                    WHERE r.status = 'DONE' AND v.organization_id = ?
                      AND v.visit_date >= ? AND v.visit_date < ?
                      AND (SELECT MAX(v2.visit_date) FROM request_fulfillments f2
                           JOIN visits v2 ON v2.id = f2.visit_id WHERE f2.request_id = r.id) < ?
                    """, orgId, start, end, end));
            stats.put("previousMonthVisitCount", count("SELECT COUNT(*) FROM visits WHERE organization_id = ? AND visit_date >= ? AND visit_date < ?", orgId, previousStart, start));
            return stats;
        }));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static void requireManager() {
        if (!Set.of("ORG_MANAGER", "ADMIN").contains(Actor.current().role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "기관 책임자 권한이 필요합니다.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public record Cycle(@NotNull String serviceCode, @Positive Integer cycleDays) {}
    public record CycleInput(@NotNull @Size(min = 1, max = 4) List<@NotNull @Valid Cycle> cycles) {}
    public record AssignmentInput(@NotNull @Size(max = 1000) List<@NotNull @Positive Long> organizationIds) {}
}
