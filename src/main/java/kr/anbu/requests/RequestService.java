package kr.anbu.requests;

import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RequestService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RequestService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_CONFLICT", message);
    }
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 작업을 수행할 기관 권한이 없습니다.");
    }
    public static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    }

    public boolean capable(long testTypeId, long organizationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM test_type_organizations WHERE test_type_id=? AND organization_id=?",
                Long.class, testTypeId, organizationId) > 0;
    }

    private record RequestRow(long id, long placeId, long testTypeId, int requestedCount,
                              LocalDate requestedDate, String status, Long plannedOrganizationId,
                              LocalDate plannedDate) {}

    private RequestRow row(long id, boolean lock) {
        List<RequestRow> found = jdbc.query("SELECT * FROM test_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new RequestRow(rs.getLong("id"), rs.getLong("place_id"), rs.getLong("test_type_id"),
                        rs.getInt("requested_count"), rs.getObject("requested_date", LocalDate.class), rs.getString("status"),
                        rs.getObject("planned_organization_id", Long.class), rs.getObject("planned_date", LocalDate.class)), id);
        if (found.isEmpty()) {
            throw missing();
        }
        return found.getFirst();
    }

    @Transactional
    public Map<String, Object> plan(long id, LocalDate plannedDate) {
        if (plannedDate == null || plannedDate.isBefore(today())) {
            throw invalid("방문 예정일은 오늘 이후여야 합니다.");
        }
        RequestRow request = row(id, true);
        long organizationId = Actor.current().organizationId();
        if (!capable(request.testTypeId(), organizationId)) {
            throw forbidden();
        }
        if (request.status().equals("DONE") || (request.plannedOrganizationId() != null
                && request.plannedOrganizationId() != organizationId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PLANNED", "이미 완료되었거나 다른 기관이 맡은 요청입니다.");
        }
        jdbc.update("UPDATE test_requests SET status='PLANNED',planned_organization_id=?,planned_date=? WHERE id=?",
                organizationId, plannedDate, id);
        return planView(id);
    }

    @Transactional
    public Map<String, Object> unplan(long id) {
        RequestRow request = row(id, true);
        if (!request.status().equals("PLANNED")) {
            throw conflict("방문 예정 상태에서만 취소할 수 있습니다.");
        }
        if (!Objects.equals(request.plannedOrganizationId(), Actor.current().organizationId())) {
            throw forbidden();
        }
        jdbc.update("UPDATE test_requests SET status='PENDING',planned_organization_id=NULL,planned_date=NULL WHERE id=?", id);
        return planView(id);
    }

    private Map<String, Object> planView(long id) {
        Map<String, Object> full = view(id);
        return fields("requestId", id, "status", full.get("status"), "plannedOrganization", full.get("plannedOrganization"),
                "plannedDate", full.get("plannedDate"), "requestedCount", full.get("requestedCount"),
                "completedCount", full.get("completedCount"), "remainingCount", full.get("remainingCount"),
                "isPartiallyFulfilled", full.get("isPartiallyFulfilled"));
    }

    @Transactional
    public Map<String, Object> complete(long requestId, long visitId, int count) {
        if (count < 1) {
            throw invalid("처리 건수는 1 이상이어야 합니다.");
        }
        // Serialize every allocation from a visit before locking requests. This also protects
        // a visit's test capacity when different requests are completed concurrently.
        List<Map<String, Object>> visits = jdbc.query("SELECT * FROM visits WHERE id=? FOR UPDATE",
                (rs, n) -> fields("placeId", rs.getLong("place_id"), "organizationId", rs.getLong("organization_id"),
                        "visitDate", rs.getObject("visit_date", LocalDate.class)), visitId);
        if (visits.isEmpty()) {
            throw missing();
        }
        Map<String, Object> visit = visits.getFirst();
        RequestRow request = row(requestId, true);
        long organizationId = Actor.current().organizationId();
        if (!Objects.equals(visit.get("organizationId"), organizationId) || !capable(request.testTypeId(), organizationId)) {
            throw forbidden();
        }
        if (request.status().equals("PLANNED") && !Objects.equals(request.plannedOrganizationId(), organizationId)) {
            throw forbidden();
        }
        if (request.status().equals("DONE")) {
            throw conflict("이미 완료된 요청입니다.");
        }
        if (!Objects.equals(visit.get("placeId"), request.placeId())
                || ((LocalDate) visit.get("visitDate")).isBefore(request.requestedDate())) {
            throw invalid("요청과 방문 장소가 같고 방문일이 요청일 이후여야 합니다.");
        }
        List<Integer> capacity = jdbc.query("SELECT test_count FROM visit_tests WHERE visit_id=? AND test_type_id=?",
                (rs, n) -> rs.getInt(1), visitId, request.testTypeId());
        if (capacity.isEmpty()) {
            throw invalid("방문에 실제 제공한 검사 기록이 없습니다.");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM request_fulfillments WHERE request_id=? AND visit_id=?", Long.class,
                requestId, visitId) > 0) throw conflict("같은 요청과 방문은 한 번만 연결할 수 있습니다.");
        long completed = completedCount(requestId);
        long allocated = jdbc.queryForObject("""
                SELECT COALESCE(SUM(f.completed_count),0) FROM request_fulfillments f
                JOIN test_requests r ON r.id=f.request_id WHERE f.visit_id=? AND r.test_type_id=?
                """, Long.class, visitId, request.testTypeId());
        if (count > request.requestedCount() - completed || count > capacity.getFirst() - allocated) {
            throw conflict("요청 잔여 건수 또는 실제 제공 검사 건수를 초과합니다.");
        }
        jdbc.update("INSERT INTO request_fulfillments(request_id,visit_id,completed_count) VALUES(?,?,?)", requestId, visitId, count);
        if (completed + count == request.requestedCount()) {
            jdbc.update("UPDATE test_requests SET status='DONE' WHERE id=?", requestId);
        }
        Map<String, Object> full = view(requestId);
        return fields("requestId", requestId, "visitId", visitId, "appliedCount", count,
                "requestedCount", full.get("requestedCount"), "completedCount", full.get("completedCount"),
                "remainingCount", full.get("remainingCount"), "status", full.get("status"),
                "isPartiallyFulfilled", full.get("isPartiallyFulfilled"), "completedDate", full.get("completedDate"),
                "daysToComplete", full.get("daysToComplete"));
    }

    private long completedCount(long id) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(completed_count),0) FROM request_fulfillments WHERE request_id=?", Long.class, id);
    }

    private static final String VIEW_SQL = """
            SELECT r.*,p.name AS place_name,p.dong_id,d.name AS dong_name,t.name AS test_name,t.service_code,
            o.name AS requested_org_name,po.name AS planned_org_name,
            COALESCE(f.completed_count,0) AS completed_count,f.completed_date
            FROM test_requests r JOIN places p ON p.id=r.place_id JOIN dongs d ON d.id=p.dong_id
            JOIN test_types t ON t.id=r.test_type_id JOIN organizations o ON o.id=r.requested_by_organization_id
            LEFT JOIN organizations po ON po.id=r.planned_organization_id
            LEFT JOIN (SELECT f.request_id,SUM(f.completed_count) AS completed_count,MAX(v.visit_date) AS completed_date
                       FROM request_fulfillments f JOIN visits v ON v.id=f.visit_id GROUP BY f.request_id) f ON f.request_id=r.id
            """;

    private Map<String, Object> mapView(ResultSet rs, int index) throws SQLException {
        long completed = rs.getLong("completed_count");
        long requested = rs.getLong("requested_count");
        LocalDate requestedDate = rs.getObject("requested_date", LocalDate.class);
        boolean done = rs.getString("status").equals("DONE");
        LocalDate completedDate = done ? rs.getObject("completed_date", LocalDate.class) : null;
        Long plannedOrg = rs.getObject("planned_organization_id", Long.class);
        return fields("requestId", rs.getLong("id"),
                "place", fields("id", rs.getLong("place_id"), "name", rs.getString("place_name"),
                        "dongId", rs.getLong("dong_id"), "dongName", rs.getString("dong_name")),
                "testType", fields("id", rs.getLong("test_type_id"), "name", rs.getString("test_name"), "serviceCode", rs.getString("service_code")),
                "requestedCount", requested, "completedCount", completed, "remainingCount", requested - completed,
                "isPartiallyFulfilled", completed > 0 && completed < requested, "requestedDate", requestedDate,
                "daysPending", done ? null : ChronoUnit.DAYS.between(requestedDate, today()),
                "requestedByOrganization", fields("id", rs.getLong("requested_by_organization_id"), "name", rs.getString("requested_org_name")),
                "status", rs.getString("status"), "plannedOrganization", plannedOrg == null ? null : fields("id", plannedOrg, "name", rs.getString("planned_org_name")),
                "plannedDate", rs.getObject("planned_date", LocalDate.class), "completedDate", completedDate,
                "daysToComplete", completedDate == null ? null : ChronoUnit.DAYS.between(requestedDate, completedDate));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> view(long id) {
        List<Map<String, Object>> found = jdbc.query(VIEW_SQL + " WHERE r.id=?", this::mapView, id);
        if (found.isEmpty()) {
            throw missing();
        }
        return found.getFirst();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> placeRequests(long placeId) {
        List<Map<String, Object>> rows = jdbc.query(VIEW_SQL + " WHERE r.place_id=? AND (r.status<>'DONE' OR f.completed_date>=?) ORDER BY r.requested_date,r.id",
                this::mapView, placeId, today().minusDays(30));
        long organizationId = Actor.current().organizationId();
        for (Map<String, Object> item : rows) {
            long id = (Long) item.get("requestId");
            RequestRow request = row(id, false);
            item.put("fulfillments", jdbc.query("""
                    SELECT f.*,v.visit_date,v.organization_id FROM request_fulfillments f
                    JOIN visits v ON v.id=f.visit_id WHERE f.request_id=? ORDER BY v.visit_date,v.id
                    """, (rs, n) -> fields("visitId", rs.getLong("visit_id"), "visitDate", rs.getObject("visit_date", LocalDate.class),
                    "organizationId", rs.getLong("organization_id"), "completedCount", rs.getInt("completed_count")), id));
            item.put("assignableToMe", !request.status().equals("DONE") && capable(request.testTypeId(), organizationId)
                    && (request.plannedOrganizationId() == null || request.plannedOrganizationId() == organizationId));
        }
        return rows;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(boolean assignable, String statuses, Long dongId, Long placeId,
                                    Long testTypeId, String sort, int page, int size) {
        validatePage(page, size);
        List<String> statusList = Arrays.asList(statuses.split(",", -1));
        if (statusList.isEmpty() || !Set.of("PENDING", "PLANNED", "DONE").containsAll(statusList)) {
            throw invalid("요청 상태가 올바르지 않습니다.");
        }
        if (!Set.of("oldest", "count").contains(sort)) {
            throw invalid("정렬 값이 올바르지 않습니다.");
        }
        StringBuilder where = new StringBuilder(" WHERE r.status IN (" + String.join(",", Collections.nCopies(statusList.size(), "?")) + ")");
        List<Object> args = new ArrayList<>(statusList);
        if (assignable) {
            where.append(" AND EXISTS(SELECT 1 FROM test_type_organizations tto WHERE tto.test_type_id=r.test_type_id AND tto.organization_id=?)");
            args.add(Actor.current().organizationId());
        }
        addFilter(where, args, "p.dong_id", dongId);
        addFilter(where, args, "r.place_id", placeId);
        addFilter(where, args, "r.test_type_id", testTypeId);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + VIEW_SQL + where + ") q", Long.class, args.toArray());
        String order = sort.equals("count") ? " ORDER BY (r.requested_count-COALESCE(f.completed_count,0)) DESC,r.id" : " ORDER BY r.requested_date,r.id";
        args.add(size);
        args.add((long) page * size);
        List<Map<String, Object>> content = jdbc.query(VIEW_SQL + where + order + " LIMIT ? OFFSET ?", this::mapView, args.toArray());
        return fields("content", content, "page", page, "size", size, "totalElements", total, "totalPages", (total + size - 1) / size);
    }

    public static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("page는 0 이상, size는 1~100이어야 합니다.");
        }
    }
    public static void addFilter(StringBuilder sql, List<Object> args, String column, Long id) {
        if (id != null) {
            if (id < 1) {
                throw invalid("ID는 양수여야 합니다.");
            }
            sql.append(" AND ").append(column).append("=?");
            args.add(id);
        }
    }
}
