package kr.anbu.reference;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.entity.DongIndicatorMetadata;
import kr.anbu.persistence.entity.Organization;
import kr.anbu.persistence.entity.OrganizationDong;
import kr.anbu.persistence.entity.TestTypeOrganization;
import kr.anbu.persistence.entity.VisitCycle;
import kr.anbu.persistence.repository.*;
import kr.anbu.reference.ReferenceController.AssignmentInput;
import kr.anbu.reference.ReferenceController.Cycle;
import kr.anbu.reference.ReferenceController.CycleInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReferenceService {
    private static final Set<String> ORGANIZATION_TYPES = Set.of(
            "HEALTH_CENTER", "BRANCH", "ADMIN_CENTER", "COUNCIL", "WELFARE_CENTER");
    private static final Set<String> SERVICE_CODES = Set.of("A", "B", "C", "D");
    private final OrganizationRepository organizations;
    private final OrganizationDongRepository organizationDongs;
    private final TestTypeRepository testTypes;
    private final TestTypeOrganizationRepository assignments;
    private final DongRepository dongs;
    private final DongIndicatorMetadataRepository metadata;
    private final VisitCycleRepository visitCycles;
    private final VisitCycleService cycles;
    private final OrganizationStatisticsRepository statistics;
    private final UserAccountRepository users;

    public ReferenceService(OrganizationRepository organizations, OrganizationDongRepository organizationDongs,
                            TestTypeRepository testTypes, TestTypeOrganizationRepository assignments,
                            DongRepository dongs, DongIndicatorMetadataRepository metadata,
                            VisitCycleRepository visitCycles, VisitCycleService cycles,
                            OrganizationStatisticsRepository statistics, UserAccountRepository users) {
        this.organizations = organizations;
        this.organizationDongs = organizationDongs;
        this.testTypes = testTypes;
        this.assignments = assignments;
        this.dongs = dongs;
        this.metadata = metadata;
        this.visitCycles = visitCycles;
        this.cycles = cycles;
        this.statistics = statistics;
        this.users = users;
    }

    public List<Map<String, Object>> organizations(String type) {
        if (type != null && !ORGANIZATION_TYPES.contains(type)) {
            throw invalid("기관 유형이 올바르지 않습니다.");
        }
        List<Organization> found = type == null
                ? organizations.findAllByOrderByIdAsc() : organizations.findByTypeOrderByIdAsc(type);
        return found.stream().map(organization -> Map.<String, Object>of(
                "id", organization.getId(), "name", organization.getName(), "type", organization.getType(),
                "managedDongIds", organizationDongs.findByOrganizationIdOrderByDongIdAsc(organization.getId())
                        .stream().map(OrganizationDong::getDongId).toList())).toList();
    }

    public List<Cycle> visitCycles() {
        return cycles.cycles(Actor.current().organizationId()).entrySet().stream()
                .map(entry -> new Cycle(entry.getKey(), entry.getValue())).toList();
    }

    @Transactional
    public List<Cycle> updateCycles(CycleInput input) {
        requireManager();
        if (input.cycles().stream().map(Cycle::serviceCode).distinct().count() != input.cycles().size()) {
            throw invalid("서비스 코드는 중복될 수 없습니다.");
        }
        for (Cycle cycle : input.cycles()) {
            if (!SERVICE_CODES.contains(cycle.serviceCode())) {
                throw invalid("서비스 코드는 A~D여야 합니다.");
            }
        }
        long organizationId = Actor.current().organizationId();
        organizations.findByIdForUpdate(organizationId).orElseThrow(() -> notFound("기관을 찾을 수 없습니다."));
        visitCycles.saveAll(input.cycles().stream().map(cycle ->
                new VisitCycle(organizationId, cycle.serviceCode(), cycle.cycleDays())).toList());
        return visitCycles();
    }

    public List<Map<String, Object>> testTypes() {
        var registeredOrganizationIds = new HashSet<>(users.findRegisteredOrganizationIds());
        return testTypes.findAllByOrderByIdAsc().stream().map(testType -> {
            var responsible = assignments.findByTestTypeIdOrderByOrganizationIdAsc(testType.getId()).stream()
                    .map(assignment -> organizations.findById(assignment.getOrganizationId()).orElseThrow())
                    .map(organization -> Map.of("id", organization.getId(), "name", organization.getName()))
                    .toList();
            return Map.<String, Object>of("id", testType.getId(), "name", testType.getName(),
                    "serviceCode", testType.getServiceCode(), "responsibleOrganizations", responsible,
                    "referralOrganizations", responsible.stream()
                            .filter(organization -> registeredOrganizationIds.contains(organization.get("id"))).toList());
        }).toList();
    }

    @Transactional
    public Map<String, Object> assignOrganizations(long testTypeId, AssignmentInput input) {
        if (!Actor.current().role().equals("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자 권한이 필요합니다.");
        }
        Set<Long> desired = new HashSet<>(input.organizationIds());
        if (desired.size() != input.organizationIds().size()) {
            throw invalid("기관 ID는 중복될 수 없습니다.");
        }
        var testType = testTypes.findByIdForUpdate(testTypeId)
                .orElseThrow(() -> notFound("검사 종류를 찾을 수 없습니다."));
        if (organizations.findAllById(desired).size() != desired.size()) {
            throw notFound("기관을 찾을 수 없습니다.");
        }
        var existing = assignments.findByTestTypeIdOrderByOrganizationIdAsc(testTypeId);
        Set<Long> existingIds = existing.stream().map(TestTypeOrganization::getOrganizationId).collect(Collectors.toSet());
        assignments.deleteAll(existing.stream().filter(row -> !desired.contains(row.getOrganizationId())).toList());
        assignments.saveAll(input.organizationIds().stream().filter(id -> !existingIds.contains(id))
                .map(id -> new TestTypeOrganization(testTypeId, id)).toList());
        return Map.of("id", testTypeId, "name", testType.getName(), "responsibleOrganizationIds", input.organizationIds());
    }

    public List<Map<String, Object>> dongs() {
        return dongs.findAllByOrderByIdAsc().stream().map(dong -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", dong.getId());
            view.put("name", dong.getName());
            view.put("gu", dong.getGu());
            view.put("elderlyRate", dong.getElderlyRate());
            view.put("recipientRate", dong.getRecipientRate());
            view.put("singleHouseholdRate", dong.getSingleHouseholdRate());
            view.put("baseDate", dong.getIndicatorsBaseDate());
            view.put("indicatorMetadata", metadata.findByDongIdOrderByIndicatorKeyAsc(dong.getId())
                    .stream().map(this::indicatorMetadata).toList());
            return view;
        }).toList();
    }

    private Map<String, Object> indicatorMetadata(DongIndicatorMetadata item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", item.getIndicatorKey());
        view.put("source", item.getSource());
        view.put("baseDate", item.getBaseDate());
        view.put("denominator", item.getDenominator());
        return view;
    }

    public List<Map<String, Object>> statistics(String month) {
        YearMonth period;
        try {
            if (!month.matches("\\d{4}-\\d{2}")) {
                throw invalid("month는 YYYY-MM 형식이어야 합니다.");
            }
            period = YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw invalid("month는 유효한 YYYY-MM이어야 합니다.");
        }
        return statistics.summarize(period.atDay(1), period.plusMonths(1).atDay(1),
                period.minusMonths(1).atDay(1)).stream().map(row -> Map.<String, Object>of(
                "organization", Map.of("id", row.getOrganizationId(), "name", row.getOrganizationName()),
                "visitCount", row.getVisitCount(), "placeCount", row.getPlaceCount(),
                "requestCreatedCount", row.getRequestCreatedCount(), "requestCompletedCount", row.getRequestCompletedCount(),
                "previousMonthVisitCount", row.getPreviousMonthVisitCount())).toList();
    }

    private static void requireManager() {
        if (!Set.of("ORG_MANAGER", "ADMIN").contains(Actor.current().role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "기관 책임자 권한이 필요합니다.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
