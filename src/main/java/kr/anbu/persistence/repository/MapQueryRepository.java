package kr.anbu.persistence.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class MapQueryRepository {
    private static final String PLACE_SELECT = """
            SELECT p.id, p.name, p.dong_id, d.name AS dong_name, p.address,
                   p.latitude, p.longitude, p.nearest_clinic_km
            FROM places p JOIN dongs d ON d.id = p.dong_id
            """;
    private final EntityManager entityManager;

    public MapQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<PlaceRow> findRegisteredPlaces() {
        return rows(entityManager.createNativeQuery(PLACE_SELECT + " ORDER BY p.id"))
                .stream().map(this::place).toList();
    }

    public Optional<PlaceRow> findPlace(long placeId) {
        Query query = entityManager.createNativeQuery(PLACE_SELECT + " WHERE p.id = :placeId")
                .setParameter("placeId", placeId);
        return rows(query).stream().map(this::place).findFirst();
    }

    public Map<Long, Map<String, LocalDate>> findLatestServiceDates(LocalDate asOfDate) {
        Query query = entityManager.createNativeQuery("""
                SELECT v.place_id, s.service_code, MAX(v.visit_date) AS last_date
                FROM visits v JOIN visit_services s ON s.visit_id = v.id
                WHERE v.visit_date <= :asOfDate GROUP BY v.place_id, s.service_code
                """).setParameter("asOfDate", asOfDate);
        Map<Long, Map<String, LocalDate>> dates = new HashMap<>();
        for (Object[] row : rows(query)) {
            dates.computeIfAbsent(longValue(row[0]), ignored -> new HashMap<>())
                    .put(row[1].toString(), date(row[2]));
        }
        return dates;
    }

    public Map<Long, LocalDate> findLatestVisitDates(LocalDate asOfDate) {
        Query query = entityManager.createNativeQuery("""
                SELECT place_id, MAX(visit_date) AS last_date FROM visits
                WHERE visit_date <= :asOfDate GROUP BY place_id
                """).setParameter("asOfDate", asOfDate);
        Map<Long, LocalDate> dates = new HashMap<>();
        for (Object[] row : rows(query)) {
            dates.put(longValue(row[0]), date(row[1]));
        }
        return dates;
    }

    public Map<Long, RequestTotals> findRequestTotals() {
        Query query = entityManager.createNativeQuery("""
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
                """);
        Map<Long, RequestTotals> totals = new HashMap<>();
        for (Object[] row : rows(query)) {
            totals.put(longValue(row[0]), new RequestTotals(longValue(row[1]), longValue(row[2]),
                    longValue(row[3]), longValue(row[4])));
        }
        return totals;
    }

    public List<VisitHistoryRow> findServiceHistory(long placeId, String serviceCode,
            LocalDate asOfDate, int limit) {
        Query query = entityManager.createNativeQuery("""
                SELECT v.id, v.visit_date, o.id AS organization_id, o.name AS organization_name
                FROM visits v JOIN visit_services s ON s.visit_id = v.id
                JOIN organizations o ON o.id = v.organization_id
                WHERE v.place_id = :placeId AND s.service_code = :serviceCode AND v.visit_date <= :asOfDate
                ORDER BY v.visit_date DESC, v.id DESC
                """).setParameter("placeId", placeId)
                .setParameter("serviceCode", serviceCode)
                .setParameter("asOfDate", asOfDate)
                .setMaxResults(limit);
        return rows(query).stream().map(row -> new VisitHistoryRow(longValue(row[0]), date(row[1]),
                longValue(row[2]), (String) row[3])).toList();
    }

    public List<PerformedTestRow> findPerformedTests(List<Long> visitIds) {
        if (visitIds.isEmpty()) {
            return List.of();
        }
        Query query = entityManager.createNativeQuery("""
                SELECT v.visit_id, t.id, t.name, t.service_code, v.test_count
                FROM visit_tests v JOIN test_types t ON t.id = v.test_type_id
                WHERE v.visit_id IN (:visitIds) ORDER BY t.id
                """).setParameter("visitIds", visitIds);
        return rows(query).stream().map(row -> new PerformedTestRow(longValue(row[0]), longValue(row[1]),
                (String) row[2], (String) row[3], ((Number) row[4]).intValue())).toList();
    }

    private PlaceRow place(Object[] row) {
        return new PlaceRow(longValue(row[0]), (String) row[1], longValue(row[2]), (String) row[3],
                (String) row[4], decimal(row[5]), decimal(row[6]), decimal(row[7]));
    }

    private static List<Object[]> rows(Query query) {
        List<?> results = query.getResultList();
        return results.stream().map(Object[].class::cast).toList();
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private static Double decimal(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        return ((java.sql.Date) value).toLocalDate();
    }

    public record PlaceRow(long id, String name, long dongId, String dongName, String address,
            Double latitude, Double longitude, Double nearestClinicKm) {}

    public record VisitHistoryRow(long id, LocalDate visitDate, long organizationId, String organizationName) {}

    public record PerformedTestRow(long visitId, long testTypeId, String name, String serviceCode, int count) {}

    public record RequestTotals(long pendingRequestCount, long pendingRequestedCount,
            long outstandingRequestCount, long outstandingRequestedCount) {
        public static final RequestTotals EMPTY = new RequestTotals(0, 0, 0, 0);
    }
}
