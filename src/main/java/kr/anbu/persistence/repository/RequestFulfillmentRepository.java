package kr.anbu.persistence.repository;

import kr.anbu.persistence.entity.RequestFulfillment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface RequestFulfillmentRepository extends JpaRepository<RequestFulfillment, RequestFulfillment.Key> {
    boolean existsByVisitId(Long visitId);
    @Query("""
            select f from RequestFulfillment f join TestRequest r on r.id = f.requestId
            where f.visitId = :visitId
              and (r.recipientOrganizationId is null or r.recipientOrganizationId = :organizationId
                   or r.requestedByOrganizationId = :organizationId)
            order by f.requestId
            """)
    List<RequestFulfillment> findVisibleForVisit(@Param("visitId") Long visitId,
                                                 @Param("organizationId") Long organizationId);

    @Query("select coalesce(sum(f.completedCount),0) from RequestFulfillment f where f.requestId = :requestId")
    long sumCompletedByRequestId(@Param("requestId") Long requestId);

    @Query("""
            select coalesce(sum(f.completedCount),0) from RequestFulfillment f
            join TestRequest r on r.id = f.requestId
            where f.visitId = :visitId and r.testTypeId = :testTypeId
            """)
    long sumAllocatedToVisitTest(@Param("visitId") Long visitId, @Param("testTypeId") Long testTypeId);

    @Query("""
            select max(v.visitDate) from RequestFulfillment f join Visit v on v.id = f.visitId
            where f.requestId = :requestId
            """)
    LocalDate findCompletedDate(@Param("requestId") Long requestId);

    @Query("""
            select f from RequestFulfillment f join Visit v on v.id = f.visitId
            where f.requestId = :requestId order by v.visitDate, v.id
            """)
    List<RequestFulfillment> findByRequestId(@Param("requestId") Long requestId);
}
