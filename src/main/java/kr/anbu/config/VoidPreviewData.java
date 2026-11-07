package kr.anbu.config;

import kr.anbu.persistence.entity.*;
import kr.anbu.persistence.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Component
@Profile("void-preview")
@Order(100)
public class VoidPreviewData implements ApplicationRunner {
    private static final String CLINIC = "대부남동보건진료소";
    private static final String PREVIEW_STAFF_NAME = "이재림";
    private static final String BASIC_HEALTH = "기초 건강·만성질환";
    private static final String COGNITIVE_HEALTH = "인지·치매";
    private static final List<PreviewReferralCenter> PREVIEW_REFERRAL_CENTERS = List.of(
            new PreviewReferralCenter("단원보건소", "HEALTH_CENTER", true, List.of(COGNITIVE_HEALTH, "결핵", "구강·치과")),
            new PreviewReferralCenter("상록수보건소", "HEALTH_CENTER", false, List.of(COGNITIVE_HEALTH, BASIC_HEALTH, "대사·체성분", "심장·수면")),
            new PreviewReferralCenter("대부보건지소", "BRANCH", true, List.of(BASIC_HEALTH, "눈·귀", "근육·관절")));
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int LEGACY_VISIT_YEAR = 2023;
    private static final String LEGACY_MEMO_FOOTER = "\n시연용 가상 기록입니다.";
    private static final List<PreviewVisit> PREVIEW_VISITS = List.of(
            new PreviewVisit(LocalDateTime.of(2026, 10, 24, 10, 30), "정기 방역 점검", "GOOD", "위생 점검",
                    "공용 공간과 조리실의 위생 상태를 점검하고 환기 및 손 소독제 비치 상태를 확인했습니다."),
            new PreviewVisit(LocalDateTime.of(2026, 9, 15, 14, 0), "건강 상담", "GOOD", "혈압 및 생활 습관 상담",
                    "혈압을 확인하고 가정에서 실천할 수 있는 운동과 식사 관리 방법을 안내했습니다."),
            new PreviewVisit(LocalDateTime.of(2026, 7, 2, 11, 0), "환경 측정", "CAUTION", "실내 온도·습도 확인",
                    "공용 공간의 온도와 습도를 확인하고 주기적인 환기를 안내했습니다."),
            new PreviewVisit(LocalDateTime.of(2026, 3, 17, 9, 30), "암 예방 검사", "GOOD", "국가 암 검진 안내",
                    "검진 대상과 일정, 예약 방법을 안내했습니다."));
    private final OrganizationRepository organizations;
    private final OrganizationDongRepository managedDongs;
    private final DongRepository dongs;
    private final PlaceRepository places;
    private final TestTypeRepository tests;
    private final TestTypeOrganizationRepository assignments;
    private final UserAccountRepository users;
    private final VisitRepository visits;
    private final VisitServiceCodeRepository visitServices;
    private final VisitTestRepository visitTests;
    private final PasswordEncoder encoder;
    private final String password;

    public VoidPreviewData(OrganizationRepository organizations, OrganizationDongRepository managedDongs,
                           DongRepository dongs, PlaceRepository places, TestTypeRepository tests,
                           TestTypeOrganizationRepository assignments, UserAccountRepository users,
                           VisitRepository visits, VisitServiceCodeRepository visitServices, VisitTestRepository visitTests,
                           PasswordEncoder encoder,
                           @Value("${ANBU_VOID_PREVIEW_PASSWORD:}") String password) {
        this.organizations = organizations;
        this.managedDongs = managedDongs;
        this.dongs = dongs;
        this.places = places;
        this.tests = tests;
        this.assignments = assignments;
        this.users = users;
        this.visits = visits;
        this.visitServices = visitServices;
        this.visitTests = visitTests;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password.isBlank() || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Set ANBU_VOID_PREVIEW_PASSWORD for the local preview.");
        }
        var daebu = dongs.findByNameForUpdate("대부동");
        var account = users.findByLoginId(CLINIC)
                .orElseGet(() -> createAccount(daebu.map(Dong::getId).orElse(null)));
        if ("시연 담당자".equals(account.getName())) {
            account.setName(PREVIEW_STAFF_NAME);
        }
        seedReferralCenters(daebu.map(Dong::getId).orElse(null));
        daebu.ifPresent(dong -> seedVisits(account, dong.getId()));
    }

    private UserAccount createAccount(Long managedDongId) {
        if (organizations.count() != 0) {
            throw new IllegalStateException("Use a separate database for the VOID preview.");
        }
        long organization = organizations.save(new Organization(CLINIC, "BRANCH")).getId();
        if (managedDongId != null) {
            managedDongs.save(new OrganizationDong(organization, managedDongId));
        }
        for (var item : List.of(
                new TestType(BASIC_HEALTH, "A"), new TestType("대사·체성분", "A"),
                new TestType(COGNITIVE_HEALTH, "A"), new TestType("구강·치과", "C"),
                new TestType("결핵", "C"), new TestType("눈·귀", "C"),
                new TestType("심장·수면", "A"), new TestType("근육·관절", "C"))) {
            long id = tests.save(item).getId();
            assignments.save(new TestTypeOrganization(id, organization));
        }
        return users.save(new UserAccount(CLINIC, encoder.encode(password), PREVIEW_STAFF_NAME, "STAFF", organization));
    }

    private void seedReferralCenters(Long daebuDongId) {
        var catalog = tests.findAllByOrderByIdAsc();
        for (var center : PREVIEW_REFERRAL_CENTERS) {
            var supported = catalog.stream().filter(type -> center.testNames().contains(type.getName())).toList();
            if (supported.isEmpty()) {
                continue;
            }
            long organizationId = users.findByLoginId(center.name()).map(UserAccount::getOrganizationId)
                    .orElseGet(() -> {
                        long id = organizations.save(new Organization(center.name(), center.type())).getId();
                        users.save(new UserAccount(center.name(), encoder.encode(password), "연계 담당자", "STAFF", id));
                        return id;
                    });
            if (center.servesDaebu() && daebuDongId != null
                    && !managedDongs.existsById(new OrganizationDong.Key(organizationId, daebuDongId))) {
                managedDongs.save(new OrganizationDong(organizationId, daebuDongId));
            }
            for (var testType : supported) {
                if (!assignments.existsByTestTypeIdAndOrganizationId(testType.getId(), organizationId)) {
                    assignments.save(new TestTypeOrganization(testType.getId(), organizationId));
                }
            }
        }
    }

    private void seedVisits(UserAccount account, long dongId) {
        var place = places.findAllByDongIdOrderByIdAsc(dongId).stream()
                .filter(row -> "흥성마을경로당".equals(row.getName()) && row.getAddress().endsWith("(대부남동)"))
                .findFirst();
        var testType = tests.findAllByOrderByIdAsc().stream()
                .filter(type -> BASIC_HEALTH.equals(type.getName()))
                .filter(type -> assignments.existsByTestTypeIdAndOrganizationId(type.getId(), account.getOrganizationId()))
                .findFirst();
        if (place.isEmpty() || testType.isEmpty()) {
            return;
        }
        for (var sample : PREVIEW_VISITS) {
            seedVisit(account, place.get().getId(), testType.get(), sample);
        }
    }

    private void seedVisit(UserAccount account, long placeId, TestType testType, PreviewVisit sample) {
        // Both creation timestamps identify fixtures even after the user changes the visit.
        var createdAt = sample.time().atZone(SEOUL).toOffsetDateTime();
        var legacyTime = sample.time().withYear(LEGACY_VISIT_YEAR);
        var legacyCreatedAt = legacyTime.atZone(SEOUL).toOffsetDateTime();
        var savedVisit = visits.findFirstByCreatedByAndCreatedAtOrderByIdAsc(account.getId(), createdAt)
                .or(() -> visits.findFirstByCreatedByAndCreatedAtOrderByIdAsc(account.getId(), legacyCreatedAt));
        if (savedVisit.isPresent()) {
            var visit = savedVisit.get();
            if (!visit.getPlaceId().equals(placeId) || !visit.getOrganizationId().equals(account.getOrganizationId())) {
                return;
            }
            var memo = visit.getMemo();
            if (memo.endsWith(LEGACY_MEMO_FOOTER)) {
                visit.setMemo(memo.substring(0, memo.length() - LEGACY_MEMO_FOOTER.length()));
            }
            if (visit.getUpdatedAt() == null
                    && legacyCreatedAt.isEqual(visit.getCreatedAt())
                    && legacyTime.toLocalDate().equals(visit.getVisitDate())
                    && legacyTime.toLocalTime().equals(visit.getVisitTime())
                    && sample.resultCode().equals(visit.getResultCode())
                    && sample.memo().equals(visit.getMemo())) {
                visit.setVisitDate(sample.time().toLocalDate());
                visit.setCreatedAt(createdAt);
            }
            return;
        }
        var visit = new Visit();
        visit.setVisitDate(sample.time().toLocalDate());
        visit.setVisitTime(sample.time().toLocalTime());
        visit.setResultCode(sample.resultCode());
        visit.setPlaceId(placeId);
        visit.setOrganizationId(account.getOrganizationId());
        visit.setCreatedBy(account.getId());
        visit.setCreatedAt(createdAt);
        visit.setMemo(sample.memo());
        long visitId = visits.save(visit).getId();

        var service = new VisitServiceCode();
        service.setVisitId(visitId);
        service.setServiceCode(testType.getServiceCode());
        visitServices.save(service);
        var test = new VisitTest();
        test.setVisitId(visitId);
        test.setTestTypeId(testType.getId());
        test.setTestCount(1);
        visitTests.save(test);
    }

    private record PreviewReferralCenter(String name, String type, boolean servesDaebu, List<String> testNames) {}

    private record PreviewVisit(LocalDateTime time, String purpose, String resultCode, String inspection, String details) {
        String memo() {
            return String.join("\n", "방문 목적: " + purpose, "검사 내용: " + inspection, details);
        }
    }
}
