package kr.anbu.persistence.repository;

import java.util.List;
import kr.anbu.persistence.entity.DongIndicatorMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DongIndicatorMetadataRepository extends JpaRepository<DongIndicatorMetadata, DongIndicatorMetadata.Key> {
    List<DongIndicatorMetadata> findByDongIdOrderByIndicatorKeyAsc(Long dongId);
}
