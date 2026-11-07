package kr.anbu.reference;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.repository.DongRepository;
import kr.anbu.persistence.repository.PlaceRepository;
import kr.anbu.persistence.repository.PlaceRepository.PlaceTestHistory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InspectionPriorityService {
    private static final Comparator<PlaceTestHistory> BY_LAST_TEST = Comparator.comparing(
            PlaceTestHistory::getLastTestDate, Comparator.nullsFirst(Comparator.naturalOrder()));
    private static final Comparator<PlaceTestHistory> BY_PLACE_PRIORITY =
            BY_LAST_TEST.thenComparingLong(PlaceTestHistory::getPlaceId);
    private static final Comparator<PlaceTestHistory> BY_DONG_PRIORITY =
            BY_LAST_TEST.thenComparingLong(PlaceTestHistory::getDongId);

    private final DongRepository dongs;
    private final PlaceRepository places;
    private final Clock clock;

    public InspectionPriorityService(DongRepository dongs, PlaceRepository places, Clock clock) {
        this.dongs = dongs;
        this.places = places;
        this.clock = clock;
    }

    public DongPriorities dongPriorities(Long testTypeId) {
        LocalDate asOfDate = LocalDate.now(clock);
        Map<Long, PlaceTestHistory> representatives = new HashMap<>();
        var higherPriorityPlace = BinaryOperator.minBy(BY_PLACE_PRIORITY);
        for (PlaceTestHistory place : places.findInspectionTargets(null, asOfDate, testTypeId)) {
            representatives.merge(place.getDongId(), place, higherPriorityPlace);
        }

        List<DongPriority> items = new ArrayList<>();
        for (PlaceTestHistory place : representatives.values().stream().sorted(BY_DONG_PRIORITY).toList()) {
            LocalDate lastTestDate = place.getLastTestDate();
            var priorityPlace = new PriorityPlace(place.getPlaceId(), place.getPlaceName(), lastTestDate,
                    elapsedMonths(lastTestDate, asOfDate));
            items.add(new DongPriority(items.size() + 1, place.getDongId(), place.getDongName(), priorityPlace));
        }
        return new DongPriorities(asOfDate, items);
    }

    public PlacePriorities placePriorities(long dongId, Long testTypeId) {
        if (!dongs.existsById(dongId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "동을 찾을 수 없습니다.");
        }
        LocalDate asOfDate = LocalDate.now(clock);
        List<PlaceTestHistory> ranked = places.findInspectionTargets(dongId, asOfDate, testTypeId)
                .stream().sorted(BY_PLACE_PRIORITY).toList();
        List<PlacePriority> items = new ArrayList<>();
        for (PlaceTestHistory place : ranked) {
            LocalDate lastTestDate = place.getLastTestDate();
            items.add(new PlacePriority(items.size() + 1, place.getPlaceId(), place.getPlaceName(),
                    lastTestDate, elapsedMonths(lastTestDate, asOfDate)));
        }
        return new PlacePriorities(asOfDate, dongId, items);
    }

    private static Long elapsedMonths(LocalDate lastTestDate, LocalDate asOfDate) {
        if (lastTestDate == null) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(lastTestDate), YearMonth.from(asOfDate));
        return lastTestDate.plusMonths(months).isAfter(asOfDate) ? months - 1 : months;
    }

    public record DongPriorities(LocalDate asOfDate, List<DongPriority> items) {}

    public record DongPriority(int rank, long dongId, String dongName, PriorityPlace priorityPlace) {}

    public record PriorityPlace(long placeId, String placeName, LocalDate lastTestDate, Long elapsedMonths) {}

    public record PlacePriorities(LocalDate asOfDate, long dongId, List<PlacePriority> items) {}

    public record PlacePriority(int rank, long placeId, String placeName, LocalDate lastTestDate,
                                Long elapsedMonths) {}
}
