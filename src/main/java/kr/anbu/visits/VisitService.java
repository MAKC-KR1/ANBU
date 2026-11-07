package kr.anbu.visits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.entity.*;
import kr.anbu.persistence.repository.*;
import kr.anbu.requests.RequestService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static kr.anbu.requests.RequestService.*;

@Service
public class VisitService {
    private final VisitRepository visits;
    private final VisitServiceCodeRepository serviceCodes;
    private final VisitTestRepository visitTests;
    private final TestRequestRepository testRequests;
    private final RequestFulfillmentRepository fulfillments;
    private final PlaceRepository places;
    private final TestTypeRepository testTypes;
    private final OrganizationRepository organizations;
    private final UserAccountRepository users;
    private final RequestService requests;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;

    public VisitService(VisitRepository visits, VisitServiceCodeRepository serviceCodes, VisitTestRepository visitTests,
                        TestRequestRepository testRequests, RequestFulfillmentRepository fulfillments,
                        PlaceRepository places, TestTypeRepository testTypes, OrganizationRepository organizations,
                        UserAccountRepository users,
                        RequestService requests, ObjectMapper mapper, Validator validator, Clock clock) {
        this.visits = visits;
        this.serviceCodes = serviceCodes;
        this.visitTests = visitTests;
        this.testRequests = testRequests;
        this.fulfillments = fulfillments;
        this.places = places;
        this.testTypes = testTypes;
        this.organizations = organizations;
        this.users = users;
        this.requests = requests;
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> create(VisitInput input) {
        validate(input);
        long organizationId = Actor.current().organizationId();
        validatePlace(input.placeId());
        Map<Long, Integer> performed = performedCounts(input.performedTests(), input.serviceCodes());
        Map<RequestTarget, RequestedCount> requested = requestedCounts(input.requestedTests(), organizationId);
        Visit visit = new Visit();
        visit.setVisitDate(input.visitDate());
        visit.setVisitTime(input.visitTime());
        visit.setResultCode(input.resultCode());
        visit.setPlaceId(input.placeId());
        visit.setOrganizationId(organizationId);
        visit.setCreatedBy(Actor.current().id());
        visit.setMemo(input.memo());
        visit.setCreatedAt(OffsetDateTime.now(clock));
        long visitId = visits.saveAndFlush(visit).getId();
        saveServicesAndTests(visitId, input.serviceCodes(), performed);
        List<Long> requestIds = new ArrayList<>();
        for (var test : requested.entrySet()) {
            TestRequest request = new TestRequest();
            request.setSourceVisitId(visitId);
            request.setPlaceId(input.placeId());
            request.setTestTypeId(test.getKey().testTypeId());
            request.setRequestedCount(test.getValue().count());
            request.setRequestedDate(input.visitDate());
            request.setRequestedByOrganizationId(organizationId);
            request.setRecipientOrganizationId(test.getKey().recipientOrganizationId());
            request.setNote(test.getValue().note());
            request.setStatus("PENDING");
            request.setCreatedAt(OffsetDateTime.now(clock));
            requestIds.add(testRequests.saveAndFlush(request).getId());
        }
        List<Map<String, Object>> applied = new ArrayList<>();
        // All request locks for this visit are acquired in a stable order.
        List<VisitInput.Fulfillment> ordered = input.fulfilledRequests().stream()
                .sorted(Comparator.comparing(VisitInput.Fulfillment::requestId)).toList();
        for (var fulfillment : ordered) {
            Map<String, Object> result = requests.complete(fulfillment.requestId(), visitId, fulfillment.completedCount());
            result.remove("visitId");
            result.remove("completedDate");
            result.remove("daysToComplete");
            applied.add(result);
        }
        return fields("visitId", visitId, "createdRequestIds", requestIds, "fulfillments", applied);
    }

    @Transactional
    public Map<String, Object> batch(VisitInput.Batch batch) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < batch.items().size(); i++) {
            try {
                results.add(create(mapper.convertValue(batch.items().get(i), VisitInput.class)));
            } catch (ApiException ex) {
                throw new ApiException(ex.status(), ex.code(), ex.getMessage(), fields("itemIndex", i));
            } catch (PessimisticLockingFailureException | DataIntegrityViolationException ex) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "동시에 변경된 데이터와 충돌합니다.", fields("itemIndex", i));
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "방문 입력 형식이 올바르지 않습니다.", fields("itemIndex", i));
            }
        }
        return fields("items", results);
    }

    private void validate(VisitInput input) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw invalid("필수 값과 검사 건수를 확인해 주세요.");
        }
        if (input.visitDate().isAfter(requests.today())) {
            throw invalid("방문일은 미래 날짜일 수 없습니다.");
        }
        if (new HashSet<>(input.serviceCodes()).size() != input.serviceCodes().size()) {
            throw invalid("서비스 유형은 중복할 수 없습니다.");
        }
        if (input.fulfilledRequests().stream().map(VisitInput.Fulfillment::requestId).distinct().count() != input.fulfilledRequests().size()) {
            throw conflict("같은 요청을 한 방문에 중복 연결할 수 없습니다.");
        }
    }

    private void validatePlace(long id) {
        if (!places.existsById(id)) {
            throw missing();
        }
    }

    private Map<Long, Integer> performedCounts(List<VisitInput.TestCount> tests, List<String> services) {
        Map<Long, Integer> counts = new TreeMap<>();
        for (var test : tests) {
            TestType type = testTypes.findById(test.testTypeId()).orElseThrow(RequestService::missing);
            if (test.recipientOrganizationId() != null || test.note() != null) {
                throw invalid("받을 기관과 전달 메모는 연계 요청에만 입력할 수 있습니다.");
            }
            if (!services.contains(type.getServiceCode())) {
                throw invalid("제공 검사의 서비스 유형을 선택해 주세요.");
            }
            if (!requests.capable(test.testTypeId(), Actor.current().organizationId())) {
                throw forbidden();
            }
            counts.merge(test.testTypeId(), test.count(), VisitService::addCounts);
        }
        return counts;
    }

    private Map<RequestTarget, RequestedCount> requestedCounts(List<VisitInput.TestCount> tests, long sender) {
        Map<RequestTarget, RequestedCount> counts = new TreeMap<>(Comparator.comparingLong(RequestTarget::testTypeId)
                .thenComparing(RequestTarget::recipientOrganizationId, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (var test : tests) {
            if (!testTypes.existsById(test.testTypeId())) {
                throw missing();
            }
            Long recipient = test.recipientOrganizationId();
            String note = test.note() == null || test.note().isBlank() ? null : test.note().strip();
            if (recipient != null && (recipient == sender || !requests.capable(test.testTypeId(), recipient)
                    || !users.existsByOrganizationId(recipient))) {
                throw invalid("선택한 검사 요청을 받을 수 있는 다른 기관을 선택해 주세요.");
            }
            if (recipient == null && note != null) {
                throw invalid("전달 메모를 받는 기관을 선택해 주세요.");
            }
            counts.merge(new RequestTarget(test.testTypeId(), recipient), new RequestedCount(test.count(), note),
                    (existing, added) -> {
                        if (!Objects.equals(existing.note(), added.note())) {
                            throw invalid("같은 검사와 기관의 요청에는 전달 메모를 하나만 입력해 주세요.");
                        }
                        return new RequestedCount(addCounts(existing.count(), added.count()), existing.note());
                    });
        }
        return counts;
    }

    private static int addCounts(int existing, int added) {
        try {
            return Math.addExact(existing, added);
        } catch (ArithmeticException ex) {
            throw invalid("검사 건수가 허용 범위를 초과합니다.");
        }
    }

    private record RequestTarget(Long testTypeId, Long recipientOrganizationId) {}
    private record RequestedCount(int count, String note) {}

    private void saveServicesAndTests(long id, List<String> services, Map<Long, Integer> tests) {
        for (String service : services) {
            VisitServiceCode entry = new VisitServiceCode();
            entry.setVisitId(id);
            entry.setServiceCode(service);
            serviceCodes.save(entry);
        }
        for (var test : tests.entrySet()) {
            VisitTest entry = new VisitTest();
            entry.setVisitId(id);
            entry.setTestTypeId(test.getKey());
            entry.setTestCount(test.getValue());
            visitTests.save(entry);
        }
        visitTests.flush();
    }

    private Visit ownLockedVisit(long id) {
        Visit visit = visits.findLockedById(id).orElseThrow(RequestService::missing);
        if (!Objects.equals(visit.getOrganizationId(), Actor.current().organizationId())) {
            throw forbidden();
        }
        return visit;
    }

    private boolean linked(long id) {
        return testRequests.existsBySourceVisitId(id) || fulfillments.existsByVisitId(id);
    }

    private ApiException linkedConflict() {
        return new ApiException(HttpStatus.CONFLICT, "LINKED_VISIT_CONFLICT", "검사 요청 또는 처리 실적이 연결된 방문은 메모만 수정할 수 있습니다.");
    }

    @Transactional
    public Map<String, Object> update(long id, JsonNode patch) {
        Visit visit = ownLockedVisit(id);
        Set<String> allowed = Set.of("visitDate", "visitTime", "resultCode", "placeId", "serviceCodes", "performedTests", "memo");
        if (!patch.isObject() || patch.isEmpty()) {
            throw invalid("수정할 필드를 입력해 주세요.");
        }
        Iterator<String> names = patch.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw invalid("지원하지 않는 수정 필드입니다.");
            }
        }
        if (linked(id) && (patch.size() != 1 || !patch.has("memo"))) {
            throw linkedConflict();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (patch.size() == 1 && patch.has("memo")) {
            if (!patch.get("memo").isTextual() || patch.get("memo").textValue().length() > 2000) {
                throw invalid("메모는 2000자 이하의 문자열이어야 합니다.");
            }
            visit.setMemo(patch.get("memo").textValue());
            visit.setUpdatedAt(now);
            visits.saveAndFlush(visit);
            return fields("visitId", id, "updatedAt", now);
        }
        Map<String, Object> current = mapView(visit);
        ObjectNode merged = mapper.createObjectNode();
        for (String field : allowed) {
            merged.set(field, mapper.valueToTree(current.get(field)));
        }
        // Only writable test fields participate in patch validation.
        merged.set("performedTests", mapper.valueToTree(visitTests.findByVisitIdOrderByTestTypeIdAsc(id).stream()
                .map(test -> fields("testTypeId", test.getTestTypeId(), "count", test.getTestCount())).toList()));
        patch.properties().forEach(entry -> merged.set(entry.getKey(), entry.getValue()));
        if (patch.has("memo") && !patch.get("memo").isTextual()) {
            throw invalid("메모는 문자열이어야 합니다.");
        }
        VisitInput updated;
        try {
            updated = mapper.convertValue(merged, VisitInput.class);
        } catch (IllegalArgumentException ex) {
            throw invalid("수정 입력 형식이 올바르지 않습니다.");
        }
        validate(updated);
        validatePlace(updated.placeId());
        Map<Long, Integer> tests = performedCounts(updated.performedTests(), updated.serviceCodes());
        visit.setVisitDate(updated.visitDate());
        visit.setVisitTime(updated.visitTime());
        visit.setResultCode(updated.resultCode());
        visit.setPlaceId(updated.placeId());
        visit.setMemo(updated.memo());
        visit.setUpdatedAt(now);
        visits.saveAndFlush(visit);
        if (patch.has("serviceCodes") || patch.has("performedTests")) {
            deleteServicesAndTests(id);
            saveServicesAndTests(id, updated.serviceCodes(), tests);
        }
        return fields("visitId", id, "updatedAt", now);
    }

    private void deleteServicesAndTests(long id) {
        serviceCodes.deleteByVisitId(id);
        visitTests.deleteByVisitId(id);
        visitTests.flush();
    }

    @Transactional
    public Map<String, Object> delete(long id) {
        Visit visit = ownLockedVisit(id);
        if (linked(id)) {
            throw linkedConflict();
        }
        deleteServicesAndTests(id);
        visits.delete(visit);
        visits.flush();
        return fields("visitId", id, "deleted", true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> view(long id) {
        return mapView(visits.findById(id).orElseThrow(RequestService::missing));
    }

    private Map<String, Object> mapView(Visit visit) {
        long id = visit.getId();
        long viewerOrganizationId = Actor.current().organizationId();
        Organization organization = organizations.findById(visit.getOrganizationId()).orElseThrow(RequestService::missing);
        return fields("visitId", id, "placeId", visit.getPlaceId(),
                "organization", fields("id", organization.getId(), "name", organization.getName()),
                "visitDate", visit.getVisitDate(), "visitTime", visit.getVisitTime(), "resultCode", visit.getResultCode(),
                "memo", visit.getMemo(),
                "createdAt", visit.getCreatedAt(), "updatedAt", visit.getUpdatedAt(),
                "serviceCodes", serviceCodes.findByVisitIdOrderByServiceCodeAsc(id).stream().map(VisitServiceCode::getServiceCode).toList(),
                "performedTests", visitTests.findByVisitIdOrderByTestTypeIdAsc(id).stream().map(this::testView).toList(),
                "createdRequestIds", testRequests.findVisibleIdsForVisit(id, viewerOrganizationId),
                "fulfillments", fulfillments.findVisibleForVisit(id, viewerOrganizationId).stream()
                        .map(fulfillment -> fields("requestId", fulfillment.getRequestId(), "completedCount", fulfillment.getCompletedCount())).toList());
    }

    private Map<String, Object> testView(VisitTest test) {
        TestType type = testTypes.findById(test.getTestTypeId()).orElseThrow(RequestService::missing);
        return fields("testTypeId", type.getId(), "name", type.getName(), "serviceCode", type.getServiceCode(), "count", test.getTestCount());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Long placeId, Long dongId, Long organizationId, String serviceCode,
                                    Long testTypeId, LocalDate fromDate, LocalDate toDate, int page, int size) {
        validatePage(page, size);
        validateId(placeId);
        validateId(dongId);
        validateId(organizationId);
        validateId(testTypeId);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw invalid("시작일은 종료일 이전이어야 합니다.");
        }
        if (serviceCode != null && !Set.of("A", "B", "C", "D").contains(serviceCode)) {
            throw invalid("서비스 유형이 올바르지 않습니다.");
        }
        Page<Visit> result = visits.search(placeId, dongId, organizationId, serviceCode, testTypeId,
                fromDate, toDate, PageRequest.of(page, size));
        return fields("content", result.getContent().stream().map(this::mapView).toList(), "page", page, "size", size,
                "totalElements", result.getTotalElements(), "totalPages", (long) result.getTotalPages());
    }
}
