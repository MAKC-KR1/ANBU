package kr.anbu.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.anbu.persistence.entity.Dong;
import kr.anbu.persistence.entity.Organization;
import kr.anbu.persistence.entity.OrganizationDong;
import kr.anbu.persistence.entity.Place;
import kr.anbu.persistence.entity.TestType;
import kr.anbu.persistence.entity.TestTypeOrganization;
import kr.anbu.persistence.entity.UserAccount;
import kr.anbu.persistence.repository.DongRepository;
import kr.anbu.persistence.repository.OrganizationDongRepository;
import kr.anbu.persistence.repository.OrganizationRepository;
import kr.anbu.persistence.repository.PlaceRepository;
import kr.anbu.persistence.repository.TestTypeOrganizationRepository;
import kr.anbu.persistence.repository.TestTypeRepository;
import kr.anbu.persistence.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {
    private final OrganizationRepository organizations;
    private final DongRepository dongs;
    private final OrganizationDongRepository managedDongs;
    private final PlaceRepository places;
    private final TestTypeRepository testTypes;
    private final TestTypeOrganizationRepository capabilities;
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final String password;

    public DemoData(OrganizationRepository organizations, DongRepository dongs,
                    OrganizationDongRepository managedDongs, PlaceRepository places,
                    TestTypeRepository testTypes, TestTypeOrganizationRepository capabilities,
                    UserAccountRepository users, PasswordEncoder passwords,
                    @Value("${ANBU_DEMO_PASSWORD:}") String password) {
        this.organizations = organizations;
        this.dongs = dongs;
        this.managedDongs = managedDongs;
        this.places = places;
        this.testTypes = testTypes;
        this.capabilities = capabilities;
        this.users = users;
        this.passwords = passwords;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("The demo profile requires ANBU_DEMO_PASSWORD of at least 12 characters and at most 72 UTF-8 bytes.");
        }
        if (organizations.count() > 0) {
            return;
        }
        long health = organizations.save(new Organization("시연 보건소", "HEALTH_CENTER")).getId();
        long welfare = organizations.save(new Organization("시연 복지관", "WELFARE_CENTER")).getId();
        long dong = dongs.save(new Dong("시연동", "시연구")).getId();
        managedDongs.saveAll(List.of(new OrganizationDong(health, dong), new OrganizationDong(welfare, dong)));
        Place seniorCenter = new Place("시연 경로당", dong, "시연용 주소 (실제 시설 아님)",
                new BigDecimal("37.3219"), new BigDecimal("126.8309"));
        seniorCenter.setPlaceType("SENIOR_CENTER");
        places.save(seniorCenter);
        long testType = testTypes.save(new TestType("시연 건강검사", "B")).getId();
        capabilities.save(new TestTypeOrganization(testType, health));
        String hash = passwords.encode(password);
        createUser("health-demo", "시연 보건소 담당자", "STAFF", health, hash);
        createUser("welfare-demo", "시연 복지관 담당자", "STAFF", welfare, hash);
        createUser("manager-demo", "시연 기관 관리자", "ORG_MANAGER", health, hash);
        createUser("admin-demo", "시연 시스템 관리자", "ADMIN", health, hash);
    }

    private void createUser(String loginId, String name, String role, long organizationId, String hash) {
        users.save(new UserAccount(loginId, hash, name, role, organizationId));
    }
}
