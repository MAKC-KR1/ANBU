package kr.anbu.persistence.repository;

import kr.anbu.persistence.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {
}
