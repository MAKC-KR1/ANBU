package kr.anbu.map;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.requests.RequestService;
import kr.anbu.reference.VisitCycleService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MapService {
    private static final List<String> SERVICES = List.of("A", "B", "C", "D");
    private static final Set<String> GAP_LEVELS = Set.of("RECENT", "WARNING", "LONG_GAP", "NO_RECORD");
    private final JdbcTemplate jdbc;
    private final RequestService requests;
    private final VisitCycleService visitCycles;
    private final Clock clock;

    public MapService(JdbcTemplate jdbc, RequestService requests, VisitCycleService visitCycles, Clock clock) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.visitCycles = visitCycles;
        this.clock = clock;
    }

    public List<Marker> places(Long dongId, String serviceCode, String gapLevel, Boolean hasPendingRequest) {
        validateService(serviceCode);
        if (dongId != null && dongId <= 0) {
            throw invalid("dongId는 양수여야 합니다.");
        }
        if (gapLevel != null && !GAP_LEVELS.contains(gapLevel)) {
            throw invalid("지원하지 않는 gapLevel입니다.");
        }
        LocalDate today = LocalDate.now(clock);
        Map<String, Integer> cycles = visitCycles.cycles(Actor.current().organizationId());
        Map<Long, Map<String, LocalDate>> dates = serviceDates(today);
        Map<Long, LocalDate> latest = latestDates(today);
        Map<Long, RequestTotals> totals = requestTotals();
        return registeredPlaces().stream()
                .filter(place -> dongId == null || place.dongId() == dongId)
                .map(place -> marker(place, serviceCode, dates, latest, totals, cycles, today))
                .filter(marker -> gapLevel == null || marker.gapLevel().equals(gapLevel))
                .filter(marker -> !Boolean.TRUE.equals(hasPendingRequest) || marker.pendingRequestCount() > 0)
                .toList();
    }

    public Summary summary(long placeId, int historyLimit) {
        if (historyLimit < 1 || historyLimit > 50) {
            throw invalid("historyLimit는 1~50이어야 합니다.");
        }
        Place place = registeredPlaces().stream().filter(item -> item.id() == placeId).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "장소를 찾을 수 없습니다."));
        LocalDate today = LocalDate.now(clock);
        Map<String, Integer> cycles = visitCycles.cycles(Actor.current().organizationId());
        Map<String, LocalDate> lastByService = serviceDates(today).getOrDefault(placeId, Map.of());
        LocalDate latest = latestDates(today).get(placeId);
        Map<String, List<HistoryVisit>> histories = histories(placeId, historyLimit, today);
        List<ServiceHistory> historyByService = SERVICES.stream().map(code -> {
            LocalDate lastDate = lastByService.get(code);
            Integer cycleDays = cycles.get(code);
            return new ServiceHistory(code, lastDate, gapLevel(lastDate, today), cycleDays,
                    isOverdue(lastDate, cycleDays, today), histories.get(code));
        }).toList();
        RequestTotals totals = requestTotals().getOrDefault(placeId, RequestTotals.EMPTY);
        return new Summary(place, latest, gapLevel(latest, today), historyByService,
                requests.placeRequests(placeId), totals.outstandingRequestCount(), totals.outstandingRequestedCount());
    }

    private Marker marker(Place place, String serviceCode, Map<Long, Map<String, LocalDate>> dates,
            Map<Long, LocalDate> latest, Map<Long, RequestTotals> totals,
            Map<String, Integer> cycles, LocalDate today) {
        Map<String, LocalDate> serviceDates = dates.getOrDefault(place.id(), Map.of());
        LocalDate lastDate = serviceCode == null ? latest.get(place.id()) : serviceDates.get(serviceCode);
        List<String> overdue = SERVICES.stream()
                .filter(code -> isOverdue(serviceDates.get(code), cycles.get(code), today)).toList();
        List<String> noRecord = SERVICES.stream().filter(code -> !serviceDates.containsKey(code)).toList();
        RequestTotals counts = totals.getOrDefault(place.id(), RequestTotals.EMPTY);
        return new Marker(place.id(), place.name(), place.dongId(), place.dongName(), place.lat(), place.lng(),
                lastDate, daysSince(lastDate, today), gapLevel(lastDate, today), overdue, noRecord,
                counts.pendingRequestCount(), counts.pendingRequestedCount(),
                counts.outstandingRequestCount(), counts.outstandingRequestedCount());
    }

    private List<Place> registeredPlaces() {
        return jdbc.query("""
                SELECT p.id, p.name, p.dong_id, d.name AS dong_name, p.address,
                       p.latitude, p.longitude, p.nearest_clinic_km
                FROM places p JOIN dongs d ON d.id = p.dong_id ORDER BY p.id
                """, (rs, row) -> new Place(rs.getLong("id"), rs.getString("name"), rs.getLong("dong_id"),
                rs.getString("dong_name"), rs.getString("address"), rs.getObject("latitude", Double.class),
                rs.getObject("longitude", Double.class), rs.getObject("nearest_clinic_km", Double.class)));
    }

    private Map<Long, Map<String, LocalDate>> serviceDates(LocalDate today) {
        Map<Long, Map<String, LocalDate>> dates = new HashMap<>();
        jdbc.query("""
                SELECT v.place_id, s.service_code, MAX(v.visit_date) AS last_date
                FROM visits v JOIN visit_services s ON s.visit_id = v.id
                WHERE v.visit_date <= ? GROUP BY v.place_id, s.service_code
                """, rs -> {
            dates.computeIfAbsent(rs.getLong("place_id"), ignored -> new HashMap<>())
                    .put(rs.getString("service_code"), rs.getDate("last_date").toLocalDate());
        }, Date.valueOf(today));
        return dates;
    }

    private Map<Long, LocalDate> latestDates(LocalDate today) {
        Map<Long, LocalDate> dates = new HashMap<>();
        jdbc.query("""
                SELECT place_id, MAX(visit_date) AS last_date FROM visits
                WHERE visit_date <= ? GROUP BY place_id
                """, rs -> {
            dates.put(rs.getLong("place_id"), rs.getDate("last_date").toLocalDate());
        }, Date.valueOf(today));
        return dates;
    }

    private Map<Long, RequestTotals> requestTotals() {
        Map<Long, RequestTotals> totals = new HashMap<>();
        jdbc.query("""
                SELECT r.place_id,
                       SUM(CASE WHEN r.status = 'PENDING' THEN 1 ELSE 0 END) AS pending_requests,
                       SUM(CASE WHEN r.status = 'PENDING'
                           THEN r.requested_count - COALESCE(f.completed, 0) ELSE 0 END) AS pending_count,
                       COUNT(*) AS outstanding_requests,
                       SUM(r.requested_count - COALESCE(f.completed, 0)) AS outstanding_count
                FROM test_requests r
                LEFT JOIN (SELECT request_id, SUM(completed_count) AS completed
                           FROM request_fulfillments GROUP BY request_id) f ON f.request_id = r.id
                WHERE r.status IN ('PENDING', 'PLANNED') GROUP BY r.place_id
                """, rs -> {
            totals.put(rs.getLong("place_id"), new RequestTotals(rs.getLong("pending_requests"),
                    rs.getLong("pending_count"), rs.getLong("outstanding_requests"), rs.getLong("outstanding_count")));
        });
        return totals;
    }

    private Map<String, List<HistoryVisit>> histories(long placeId, int limit, LocalDate today) {
        Map<String, List<VisitRow>> visits = new LinkedHashMap<>();
        for (String code : SERVICES) {
            visits.put(code, jdbc.query("""
                    SELECT v.id, v.visit_date, o.id AS organization_id, o.name AS organization_name
                    FROM visits v JOIN visit_services s ON s.visit_id = v.id
                    JOIN organizations o ON o.id = v.organization_id
                    WHERE v.place_id = ? AND s.service_code = ? AND v.visit_date <= ?
                    ORDER BY v.visit_date DESC, v.id DESC LIMIT ?
                    """, (rs, row) -> new VisitRow(rs.getLong("id"), rs.getDate("visit_date").toLocalDate(),
                    new Organization(rs.getLong("organization_id"), rs.getString("organization_name"))),
                    placeId, code, Date.valueOf(today), limit));
        }
        List<Long> visitIds = visits.values().stream().flatMap(List::stream).map(VisitRow::id).distinct().toList();
        Map<Long, Map<String, List<PerformedTest>>> tests = performedTests(visitIds);
        Map<String, List<HistoryVisit>> result = new LinkedHashMap<>();
        visits.forEach((code, rows) -> result.put(code, rows.stream().map(visit -> new HistoryVisit(
                visit.id(), visit.date(), visit.organization(),
                tests.getOrDefault(visit.id(), Map.of()).getOrDefault(code, List.of()))).toList()));
        return result;
    }

    private Map<Long, Map<String, List<PerformedTest>>> performedTests(List<Long> visitIds) {
        Map<Long, Map<String, List<PerformedTest>>> result = new HashMap<>();
        if (visitIds.isEmpty()) {
            return result;
        }
        new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT v.visit_id, t.id, t.name, t.service_code, v.test_count
                FROM visit_tests v JOIN test_types t ON t.id = v.test_type_id
                WHERE v.visit_id IN (:visitIds) ORDER BY t.id
                """, Map.of("visitIds", visitIds), rs -> {
            result.computeIfAbsent(rs.getLong("visit_id"), ignored -> new HashMap<>())
                    .computeIfAbsent(rs.getString("service_code"), ignored -> new ArrayList<>())
                    .add(new PerformedTest(rs.getLong("id"), rs.getString("name"), rs.getInt("test_count")));
        });
        return result;
    }

    static String gapLevel(LocalDate lastDate, LocalDate today) {
        if (lastDate == null) {
            return "NO_RECORD";
        }
        long elapsed = ChronoUnit.DAYS.between(lastDate, today);
        if (elapsed <= 30) {
            return "RECENT";
        }
        return elapsed <= 90 ? "WARNING" : "LONG_GAP";
    }

    private static Long daysSince(LocalDate lastDate, LocalDate today) {
        return lastDate == null ? null : ChronoUnit.DAYS.between(lastDate, today);
    }

    private static boolean isOverdue(LocalDate lastDate, Integer cycleDays, LocalDate today) {
        return lastDate != null && cycleDays != null && ChronoUnit.DAYS.between(lastDate, today) > cycleDays;
    }

    private static void validateService(String serviceCode) {
        if (serviceCode != null && !SERVICES.contains(serviceCode)) {
            throw invalid("serviceCode는 A~D 중 하나여야 합니다.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public record Place(long id, String name, long dongId, String dongName, String address,
            Double lat, Double lng, Double nearestClinicKm) {}

    public record Marker(long placeId, String name, long dongId, String dongName, Double lat, Double lng,
            LocalDate lastVisitDate, Long daysSinceLastVisit, String gapLevel, List<String> overdueServiceCodes,
            List<String> noRecordServiceCodes, long pendingRequestCount, long pendingRequestedCount,
            long outstandingRequestCount, long outstandingRequestedCount) {}

    public record Summary(Place place, LocalDate lastVisitDate, String gapLevel,
            List<ServiceHistory> historyByService, List<Map<String, Object>> requests,
            long outstandingRequestCount, long outstandingRequestedCount) {}

    public record ServiceHistory(String serviceCode, LocalDate lastVisitDate, String gapLevel,
            Integer cycleDays, boolean isOverdue, List<HistoryVisit> visits) {}

    public record HistoryVisit(long visitId, LocalDate visitDate, Organization organization,
            List<PerformedTest> performedTests) {}

    public record Organization(long id, String name) {}

    public record PerformedTest(long testTypeId, String name, int count) {}

    private record VisitRow(long id, LocalDate date, Organization organization) {}

    private record RequestTotals(long pendingRequestCount, long pendingRequestedCount,
            long outstandingRequestCount, long outstandingRequestedCount) {
        private static final RequestTotals EMPTY = new RequestTotals(0, 0, 0, 0);
    }
}
