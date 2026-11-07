package kr.anbu.persistence.repository;

import java.util.List;
import kr.anbu.persistence.entity.VisitReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitReservationRepository extends JpaRepository<VisitReservation, Long> {
    List<VisitReservation> findByPlaceIdOrderByVisitDateAscVisitTimeAscIdAsc(long placeId);
}
