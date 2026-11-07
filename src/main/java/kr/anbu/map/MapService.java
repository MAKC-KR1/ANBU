package kr.anbu.map;

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
import kr.anbu.persistence.repository.MapQueryRepository;
import kr.anbu.persistence.repository.MapQueryRepository.PlaceRow;
import kr.anbu.persistence.repository.MapQueryRepository.RequestTotals;
import kr.anbu.persistence.repository.MapQueryRepository.VisitHistoryRow;
import kr.anbu.requests.RequestService;
import kr.anbu.reference.VisitCycleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MapService {
    private static final List<String> SERVICES = List.of("A", "B", "C", "D");
    private static final Set<String> GAP_LEVELS = Set.of("RECENT", "WARNING", "LONG_GAP", "NO_RECORD");
    private final MapQueryRepository mapQueries;
    private final RequestService requests;
    private final VisitCycleService visitCycles;
    private final Clock clock;

    public MapService(MapQueryRepository mapQueries, RequestService requests, VisitCycleService visitCycles, Clock clock) {
        this.mapQueries = mapQueries;
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
        Map<Long, Map<String, LocalDate>> dates = mapQueries.findLatestServiceDates(today);
        Map<Long, LocalDate> latest = mapQueries.findLatestVisitDates(today);
        Map<Long, RequestTotals> totals = mapQueries.findRequestTotals();
        return mapQueries.findRegisteredPlaces().stream()
                .map(this::placeView)
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
        Place place = mapQueries.findPlace(placeId).map(this::placeView)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "장소를 찾을 수 없습니다."));
        LocalDate today = LocalDate.now(clock);
        Map<String, Integer> cycles = visitCycles.cycles(Actor.current().organizationId());
        Map<String, LocalDate> lastByService = mapQueries.findLatestServiceDates(today).getOrDefault(placeId, Map.of());
        LocalDate latest = mapQueries.findLatestVisitDates(today).get(placeId);
        Map<String, List<HistoryVisit>> histories = histories(placeId, historyLimit, today);
        List<ServiceHistory> historyByService = SERVICES.stream().map(code -> {
            LocalDate lastDate = lastByService.get(code);
            Integer cycleDays = cycles.get(code);
            return new ServiceHistory(code, lastDate, gapLevel(lastDate, today), cycleDays,
                    isOverdue(lastDate, cycleDays, today), histories.get(code));
        }).toList();
        RequestTotals totals = mapQueries.findRequestTotals().getOrDefault(placeId, RequestTotals.EMPTY);
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

    private Place placeView(PlaceRow place) {
        return new Place(place.id(), place.name(), place.dongId(), place.dongName(), place.address(),
                place.latitude(), place.longitude(), place.nearestClinicKm());
    }

    private Map<String, List<HistoryVisit>> histories(long placeId, int limit, LocalDate today) {
        Map<String, List<VisitHistoryRow>> visits = new LinkedHashMap<>();
        for (String code : SERVICES) {
            visits.put(code, mapQueries.findServiceHistory(placeId, code, today, limit));
        }
        List<Long> visitIds = visits.values().stream().flatMap(List::stream).map(VisitHistoryRow::id).distinct().toList();
        Map<Long, Map<String, List<PerformedTest>>> tests = performedTests(visitIds);
        Map<String, List<HistoryVisit>> result = new LinkedHashMap<>();
        visits.forEach((code, rows) -> result.put(code, rows.stream().map(visit -> new HistoryVisit(
                visit.id(), visit.visitDate(), new Organization(visit.organizationId(), visit.organizationName()),
                tests.getOrDefault(visit.id(), Map.of()).getOrDefault(code, List.of()))).toList()));
        return result;
    }

    private Map<Long, Map<String, List<PerformedTest>>> performedTests(List<Long> visitIds) {
        Map<Long, Map<String, List<PerformedTest>>> result = new HashMap<>();
        for (var test : mapQueries.findPerformedTests(visitIds)) {
            result.computeIfAbsent(test.visitId(), ignored -> new HashMap<>())
                    .computeIfAbsent(test.serviceCode(), ignored -> new ArrayList<>())
                    .add(new PerformedTest(test.testTypeId(), test.name(), test.count()));
        }
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

}
