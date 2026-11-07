package kr.anbu.persistence.repository;

import java.util.Optional;
import kr.anbu.persistence.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByLoginId(String loginId);
}
