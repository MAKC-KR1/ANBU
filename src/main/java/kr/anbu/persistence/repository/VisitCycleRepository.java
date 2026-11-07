package kr.anbu.persistence.repository;

import java.util.List;
import kr.anbu.persistence.entity.VisitCycle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitCycleRepository extends JpaRepository<VisitCycle, VisitCycle.Key> {
    List<VisitCycle> findByOrganizationId(Long organizationId);
}
