package kr.anbu.persistence.repository;

import java.util.List;
import kr.anbu.persistence.entity.Dong;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DongRepository extends JpaRepository<Dong, Long> {
    List<Dong> findAllByOrderByIdAsc();
}
