package kr.anbu.persistence.repository;

import java.util.List;
import kr.anbu.persistence.entity.OrganizationDong;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationDongRepository extends JpaRepository<OrganizationDong, OrganizationDong.Key> {
    List<OrganizationDong> findByOrganizationIdOrderByDongIdAsc(Long organizationId);
}
