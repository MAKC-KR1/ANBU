package kr.anbu.persistence.repository;

import java.time.LocalDate;
import java.util.List;
import kr.anbu.persistence.entity.Organization;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Monthly reporting queries stay in the persistence layer. */
public interface OrganizationStatisticsRepository extends Repository<Organization, Long> {
    @Query(value = """
            SELECT o.id AS "organizationId", o.name AS "organizationName",
              (SELECT COUNT(*) FROM visits v WHERE v.organization_id = o.id
                AND v.visit_date >= :start AND v.visit_date < :end) AS "visitCount",
              (SELECT COUNT(DISTINCT v.place_id) FROM visits v WHERE v.organization_id = o.id
                AND v.visit_date >= :start AND v.visit_date < :end) AS "placeCount",
              (SELECT COUNT(*) FROM test_requests r WHERE r.requested_by_organization_id = o.id
                AND r.requested_date >= :start AND r.requested_date < :end) AS "requestCreatedCount",
              (SELECT COUNT(DISTINCT r.id) FROM test_requests r
                JOIN request_fulfillments f ON f.request_id = r.id
                JOIN visits v ON v.id = f.visit_id
                WHERE r.status = 'DONE' AND v.organization_id = o.id
                  AND v.visit_date >= :start AND v.visit_date < :end
                  AND (SELECT MAX(v2.visit_date) FROM request_fulfillments f2
                       JOIN visits v2 ON v2.id = f2.visit_id WHERE f2.request_id = r.id) < :end
              ) AS "requestCompletedCount",
              (SELECT COUNT(*) FROM visits v WHERE v.organization_id = o.id
                AND v.visit_date >= :previousStart AND v.visit_date < :start) AS "previousMonthVisitCount"
            FROM organizations o ORDER BY o.id
            """, nativeQuery = true)
    List<MonthlyStatistics> summarize(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                       @Param("previousStart") LocalDate previousStart);

    interface MonthlyStatistics {
        Long getOrganizationId();
        String getOrganizationName();
        long getVisitCount();
        long getPlaceCount();
        long getRequestCreatedCount();
        long getRequestCompletedCount();
        long getPreviousMonthVisitCount();
    }
}
