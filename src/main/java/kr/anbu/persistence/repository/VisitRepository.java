package kr.anbu.persistence.repository;

import jakarta.persistence.LockModeType;
import kr.anbu.persistence.entity.Visit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface VisitRepository extends JpaRepository<Visit, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Visit> findFirstByCreatedByAndCreatedAtOrderByIdAsc(Long createdBy, OffsetDateTime createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Visit v where v.id = :id")
    Optional<Visit> findLockedById(@Param("id") Long id);

    @Query("""
            select v from Visit v join Place p on p.id = v.placeId
            where (:placeId is null or v.placeId = :placeId)
              and (:dongId is null or p.dongId = :dongId)
              and (:organizationId is null or v.organizationId = :organizationId)
              and (:serviceCode is null or exists (select s from VisitServiceCode s where s.visitId = v.id and s.serviceCode = :serviceCode))
              and (:testTypeId is null or exists (select t from VisitTest t where t.visitId = v.id and t.testTypeId = :testTypeId))
              and (:fromDate is null or v.visitDate >= :fromDate)
              and (:toDate is null or v.visitDate <= :toDate)
            order by v.visitDate desc, v.id desc
            """)
    Page<Visit> search(@Param("placeId") Long placeId, @Param("dongId") Long dongId,
                       @Param("organizationId") Long organizationId, @Param("serviceCode") String serviceCode,
                       @Param("testTypeId") Long testTypeId, @Param("fromDate") LocalDate fromDate,
                       @Param("toDate") LocalDate toDate, Pageable pageable);
}
