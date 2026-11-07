package kr.anbu.persistence.repository;

import kr.anbu.persistence.entity.VisitTest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VisitTestRepository extends JpaRepository<VisitTest, VisitTest.Key> {
    List<VisitTest> findByVisitIdOrderByTestTypeIdAsc(Long visitId);
    void deleteByVisitId(Long visitId);
}
