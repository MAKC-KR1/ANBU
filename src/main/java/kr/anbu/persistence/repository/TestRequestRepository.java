package kr.anbu.persistence.repository;

import jakarta.persistence.LockModeType;
import kr.anbu.persistence.entity.TestRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TestRequestRepository extends JpaRepository<TestRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TestRequest r where r.id = :id")
    Optional<TestRequest> findLockedById(@Param("id") Long id);

    @Query(value = """
            SELECT COUNT(*) FROM test_requests r
            WHERE r.recipient_organization_id = :organizationId
              AND NOT EXISTS (SELECT 1 FROM test_request_reads receipt
                              WHERE receipt.request_id = r.id AND receipt.user_id = :userId)
            """, nativeQuery = true)
    long countUnread(@Param("organizationId") long organizationId, @Param("userId") long userId);

    @Query(value = """
            SELECT r.id FROM test_requests r
            WHERE r.recipient_organization_id = :organizationId AND r.id IN (:requestIds)
              AND NOT EXISTS (SELECT 1 FROM test_request_reads receipt
                              WHERE receipt.request_id = r.id AND receipt.user_id = :userId)
            """, nativeQuery = true)
    List<Long> findUnreadIds(@Param("organizationId") long organizationId, @Param("userId") long userId,
                             @Param("requestIds") List<Long> requestIds);

    @Modifying
    @Query(value = """
            INSERT INTO test_request_reads (request_id, user_id)
            SELECT :requestId, :userId
            WHERE NOT EXISTS (SELECT 1 FROM test_request_reads
                              WHERE request_id = :requestId AND user_id = :userId)
            """, nativeQuery = true)
    void insertReadReceipt(@Param("requestId") long requestId, @Param("userId") long userId);

    boolean existsBySourceVisitId(Long visitId);
    @Query("""
            select r.id from TestRequest r where r.sourceVisitId = :visitId
              and (r.recipientOrganizationId is null or r.recipientOrganizationId = :organizationId
                   or r.requestedByOrganizationId = :organizationId)
            order by r.id
            """)
    List<Long> findVisibleIdsForVisit(@Param("visitId") Long visitId, @Param("organizationId") Long organizationId);

    @Query("""
            select r from TestRequest r where r.placeId = :placeId and
            (r.status <> 'DONE' or (select max(v.visitDate) from RequestFulfillment f join Visit v on v.id = f.visitId
                                   where f.requestId = r.id) >= :cutoff)
            and (r.recipientOrganizationId is null or r.recipientOrganizationId = :organizationId
                 or r.requestedByOrganizationId = :organizationId)
            order by r.requestedDate, r.id
            """)
    List<TestRequest> findForPlaceSummary(@Param("placeId") Long placeId, @Param("cutoff") LocalDate cutoff,
                                         @Param("organizationId") Long organizationId);

    @Query("""
            select r from TestRequest r join Place p on p.id = r.placeId
            where r.status in :statuses
              and (r.recipientOrganizationId is null or r.recipientOrganizationId = :organizationId
                   or r.requestedByOrganizationId = :organizationId)
              and (:direction = 'all'
                   or (:direction = 'incoming' and r.recipientOrganizationId = :organizationId)
                   or (:direction = 'outgoing' and r.recipientOrganizationId is not null
                       and r.requestedByOrganizationId = :organizationId))
              and (:assignable = false or ((r.recipientOrganizationId is null or r.recipientOrganizationId = :organizationId)
                   and exists (select capability from TestTypeOrganization capability
                   where capability.testTypeId = r.testTypeId and capability.organizationId = :organizationId)))
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
                            @Param("sortByCount") boolean sortByCount, @Param("direction") String direction,
                            Pageable pageable);
}
