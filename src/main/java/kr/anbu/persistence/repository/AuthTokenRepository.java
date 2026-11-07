package kr.anbu.persistence.repository;

import java.time.Instant;
import java.util.Optional;
import kr.anbu.persistence.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {
    Optional<AuthToken> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

    @Modifying
    @Query("delete from AuthToken token where token.expiresAt <= :now")
    void deleteExpired(@Param("now") Instant now);
}
