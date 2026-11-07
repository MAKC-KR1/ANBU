package kr.anbu.persistence.repository;

import java.time.LocalDate;
import java.util.List;
import kr.anbu.persistence.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, Long> {
    String INSPECTION_TARGET = """
            (p.placeType in ('SENIOR_CENTER', 'VILLAGE_HALL')
             or (p.placeType is null
                 and (replace(p.name, ' ', '') like '%경로당%'
                      or replace(p.name, ' ', '') like '%마을회관%')))
            """;

    List<Place> findAllByDongIdOrderByIdAsc(long dongId);

    @Query("select p from Place p where p.dongId = :dongId and " + INSPECTION_TARGET + " order by p.id")
    List<Place> findInspectionTargetsInDong(@Param("dongId") long dongId);

    @Query("""
            select p.id as placeId, p.name as placeName, d.id as dongId, d.name as dongName,
                   (select max(v.visitDate) from Visit v
                    where v.placeId = p.id and v.visitDate <= :asOfDate
                      and exists (select 1 from VisitTest t where t.visitId = v.id and t.testCount > 0
                                  and (:testTypeId is null or t.testTypeId = :testTypeId))
                   ) as lastTestDate
            from Place p join Dong d on d.id = p.dongId
            where (:dongId is null or p.dongId = :dongId)
              and
            """ + INSPECTION_TARGET)
    List<PlaceTestHistory> findInspectionTargets(@Param("dongId") Long dongId,
                                                 @Param("asOfDate") LocalDate asOfDate,
                                                 @Param("testTypeId") Long testTypeId);

    interface PlaceTestHistory {
        long getPlaceId();
        String getPlaceName();
        long getDongId();
        String getDongName();
        LocalDate getLastTestDate();
    }
}
