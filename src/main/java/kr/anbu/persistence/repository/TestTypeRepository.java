package kr.anbu.persistence.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kr.anbu.persistence.entity.TestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestTypeRepository extends JpaRepository<TestType, Long> {
    List<TestType> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select testType from TestType testType where testType.id = :id")
    Optional<TestType> findByIdForUpdate(@Param("id") Long id);
}
