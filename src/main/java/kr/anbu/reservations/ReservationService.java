package kr.anbu.reservations;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.entity.VisitReservation;
import kr.anbu.persistence.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class ReservationService {
    private final VisitReservationRepository reservations;
    private final PlaceRepository places;
    private final OrganizationRepository organizations;
    private final TestTypeRepository tests;
    private final TestTypeOrganizationRepository assignments;
    private final Clock clock;

    public ReservationService(VisitReservationRepository reservations, PlaceRepository places,
                              OrganizationRepository organizations, TestTypeRepository tests,
                              TestTypeOrganizationRepository assignments, Clock clock) {
        this.reservations = reservations;
        this.places = places;
        this.organizations = organizations;
        this.tests = tests;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Transactional
    public Reservation create(Input input) {
        var actor = Actor.current();
        requirePlace(input.placeId());
        var test = tests.findById(input.testTypeId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "검사 종류를 찾을 수 없습니다."));
        if (!assignments.existsByTestTypeIdAndOrganizationId(test.getId(), actor.organizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "소속 기관에서 제공하는 검사만 예약할 수 있습니다.");
        }
        var time = input.visitTime().withSecond(0).withNano(0);
        if (!LocalDateTime.of(input.visitDate(), time).isAfter(LocalDateTime.now(clock))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "현재 이후의 방문 일시를 선택해 주세요.");
        }
        VisitReservation saved;
        try {
            saved = reservations.saveAndFlush(new VisitReservation(input.placeId(), actor.organizationId(),
                    test.getId(), actor.id(), input.visitDate(), time));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RESERVED", "이 시설의 같은 일시에 소속 기관의 예약이 이미 있습니다.");
        }
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<Reservation> list(long placeId) {
        requirePlace(placeId);
        return reservations.findByPlaceIdOrderByVisitDateAscVisitTimeAscIdAsc(placeId).stream()
                .map(this::view).toList();
    }

    private void requirePlace(long placeId) {
        if (!places.existsById(placeId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "시설을 찾을 수 없습니다.");
        }
    }

    private Reservation view(VisitReservation saved) {
        return new Reservation(saved.getId(), saved.getPlaceId(), saved.getVisitDate(), saved.getVisitTime(),
                saved.getOrganizationId(), organizations.findById(saved.getOrganizationId()).orElseThrow().getName(),
                saved.getTestTypeId(), tests.findById(saved.getTestTypeId()).orElseThrow().getName());
    }

    public record Input(@NotNull @Positive Long placeId, @NotNull LocalDate visitDate, @NotNull LocalTime visitTime,
                        @NotNull @Positive Long testTypeId) {}
    public record Reservation(long id, long placeId, LocalDate visitDate, LocalTime visitTime, long organizationId,
                              String organizationName, long testTypeId, String testTypeName) {}
}
