package kr.anbu.persistence.repository;

import kr.anbu.persistence.entity.VisitServiceCode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VisitServiceCodeRepository extends JpaRepository<VisitServiceCode, VisitServiceCode.Key> {
    List<VisitServiceCode> findByVisitIdOrderByServiceCodeAsc(Long visitId);
    void deleteByVisitId(Long visitId);
}
