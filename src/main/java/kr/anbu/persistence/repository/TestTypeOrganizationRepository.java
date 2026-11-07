package kr.anbu.persistence.repository;

import java.util.List;
import kr.anbu.persistence.entity.TestTypeOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestTypeOrganizationRepository extends JpaRepository<TestTypeOrganization, TestTypeOrganization.Key> {
    List<TestTypeOrganization> findByTestTypeIdOrderByOrganizationIdAsc(Long testTypeId);

    boolean existsByTestTypeIdAndOrganizationId(Long testTypeId, Long organizationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TestTypeOrganization mapping where mapping.testTypeId = :testTypeId")
    void deleteByTestTypeId(@Param("testTypeId") Long testTypeId);
}
