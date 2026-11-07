package kr.anbu.persistence.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kr.anbu.persistence.entity.Dong;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DongRepository extends JpaRepository<Dong, Long> {
    List<Dong> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dong d where d.name = :name")
    Optional<Dong> findByNameForUpdate(@Param("name") String name);
}
