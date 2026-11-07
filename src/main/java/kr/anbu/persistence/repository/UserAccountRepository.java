package kr.anbu.persistence.repository;

import java.util.List;
import java.util.Optional;
import kr.anbu.persistence.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByLoginId(String loginId);

    boolean existsByOrganizationId(Long organizationId);

    @Query("select distinct u.organizationId from UserAccount u")
    List<Long> findRegisteredOrganizationIds();
}
