package kr.anbu.persistence.repository;

import jakarta.persistence.LockModeType;
import kr.anbu.persistence.entity.TestRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TestRequestRepository extends JpaRepository<TestRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TestRequest r where r.id = :id")
    Optional<TestRequest> findLockedById(@Param("id") Long id);

    boolean existsBySourceVisitId(Long visitId);
    List<TestRequest> findBySourceVisitIdOrderByIdAsc(Long visitId);

    @Query("""
            select r from TestRequest r where r.placeId = :placeId and
            (r.status <> 'DONE' or (select max(v.visitDate) from RequestFulfillment f join Visit v on v.id = f.visitId
                                   where f.requestId = r.id) >= :cutoff)
            order by r.requestedDate, r.id
            """)
    List<TestRequest> findForPlaceSummary(@Param("placeId") Long placeId, @Param("cutoff") LocalDate cutoff);

    @Query("""
            select r from TestRequest r join Place p on p.id = r.placeId
            where r.status in :statuses
              and (:assignable = false or exists (select capability from TestTypeOrganization capability
                   where capability.testTypeId = r.testTypeId and capability.organizationId = :organizationId))
              and (:dongId is null or p.dongId = :dongId)
              and (:placeId is null or r.placeId = :placeId)
              and (:testTypeId is null or r.testTypeId = :testTypeId)
            order by case when :sortByCount = true then
                r.requestedCount - (select coalesce(sum(f.completedCount),0) from RequestFulfillment f where f.requestId = r.id)
                else 0 end desc,
                case when :sortByCount = false then r.requestedDate else null end asc, r.id asc
            """)
    Page<TestRequest> search(@Param("assignable") boolean assignable, @Param("organizationId") Long organizationId,
                            @Param("statuses") List<String> statuses, @Param("dongId") Long dongId,
                            @Param("placeId") Long placeId, @Param("testTypeId") Long testTypeId,
                            @Param("sortByCount") boolean sortByCount, Pageable pageable);
}
