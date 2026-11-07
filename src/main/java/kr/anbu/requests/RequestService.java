package kr.anbu.requests;

import kr.anbu.auth.Actor;
import kr.anbu.common.ApiException;
import kr.anbu.persistence.entity.*;
import kr.anbu.persistence.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RequestService {
    private final TestRequestRepository requests;
    private final VisitRepository visits;
    private final VisitTestRepository visitTests;
    private final RequestFulfillmentRepository fulfillments;
    private final TestTypeOrganizationRepository capabilities;
    private final OrganizationRepository organizations;
    private final PlaceRepository places;
    private final DongRepository dongs;
    private final TestTypeRepository testTypes;
    private final Clock clock;

    public RequestService(TestRequestRepository requests, VisitRepository visits, VisitTestRepository visitTests,
                          RequestFulfillmentRepository fulfillments, TestTypeOrganizationRepository capabilities,
                          OrganizationRepository organizations, PlaceRepository places, DongRepository dongs,
                          TestTypeRepository testTypes, Clock clock) {
        this.requests = requests;
        this.visits = visits;
        this.visitTests = visitTests;
        this.fulfillments = fulfillments;
        this.capabilities = capabilities;
        this.organizations = organizations;
        this.places = places;
        this.dongs = dongs;
        this.testTypes = testTypes;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_CONFLICT", message);
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "이 작업을 수행할 기관 권한이 없습니다.");
    }

    public static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
    }

    public boolean capable(long testTypeId, long organizationId) {
        return capabilities.existsByTestTypeIdAndOrganizationId(testTypeId, organizationId);
    }

    @Transactional
    public Map<String, Object> plan(long id, LocalDate plannedDate) {
        if (plannedDate == null || plannedDate.isBefore(today())) {
            throw invalid("방문 예정일은 오늘 이후여야 합니다.");
        }
        TestRequest request = requests.findLockedById(id).orElseThrow(RequestService::missing);
        long organizationId = Actor.current().organizationId();
        if (!capable(request.getTestTypeId(), organizationId)) {
            throw forbidden();
        }
        if (request.getStatus().equals("DONE") || (request.getPlannedOrganizationId() != null
                && request.getPlannedOrganizationId() != organizationId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PLANNED", "이미 완료되었거나 다른 기관이 맡은 요청입니다.");
        }
        request.setStatus("PLANNED");
        request.setPlannedOrganizationId(organizationId);
        request.setPlannedDate(plannedDate);
        requests.saveAndFlush(request);
        return planView(request);
    }

    @Transactional
    public Map<String, Object> unplan(long id) {
        TestRequest request = requests.findLockedById(id).orElseThrow(RequestService::missing);
        if (!request.getStatus().equals("PLANNED")) {
            throw conflict("방문 예정 상태에서만 취소할 수 있습니다.");
        }
        if (!Objects.equals(request.getPlannedOrganizationId(), Actor.current().organizationId())) {
            throw forbidden();
        }
        request.setStatus("PENDING");
        request.setPlannedOrganizationId(null);
        request.setPlannedDate(null);
        requests.saveAndFlush(request);
        return planView(request);
    }

    private Map<String, Object> planView(TestRequest request) {
        Map<String, Object> full = mapView(request);
        return fields("requestId", request.getId(), "status", full.get("status"),
                "plannedOrganization", full.get("plannedOrganization"), "plannedDate", full.get("plannedDate"),
                "requestedCount", full.get("requestedCount"), "completedCount", full.get("completedCount"),
                "remainingCount", full.get("remainingCount"), "isPartiallyFulfilled", full.get("isPartiallyFulfilled"));
    }

    @Transactional
    public Map<String, Object> complete(long requestId, long visitId, int count) {
        if (count < 1) {
            throw invalid("처리 건수는 1 이상이어야 합니다.");
        }
        // Every allocation locks its visit before its request. Different requests therefore
        // cannot concurrently allocate the same visit's remaining test capacity.
        Visit visit = visits.findLockedById(visitId).orElseThrow(RequestService::missing);
        TestRequest request = requests.findLockedById(requestId).orElseThrow(RequestService::missing);
        long organizationId = Actor.current().organizationId();
        if (!Objects.equals(visit.getOrganizationId(), organizationId) || !capable(request.getTestTypeId(), organizationId)) {
            throw forbidden();
        }
        if (request.getStatus().equals("PLANNED") && !Objects.equals(request.getPlannedOrganizationId(), organizationId)) {
            throw forbidden();
        }
        if (request.getStatus().equals("DONE")) {
            throw conflict("이미 완료된 요청입니다.");
        }
        if (!Objects.equals(visit.getPlaceId(), request.getPlaceId())
                || visit.getVisitDate().isBefore(request.getRequestedDate())) {
            throw invalid("요청과 방문 장소가 같고 방문일이 요청일 이후여야 합니다.");
        }
        VisitTest provided = visitTests.findById(new VisitTest.Key(visitId, request.getTestTypeId()))
                .orElseThrow(() -> invalid("방문에 실제 제공한 검사 기록이 없습니다."));
        if (fulfillments.existsById(new RequestFulfillment.Key(requestId, visitId))) {
            throw conflict("같은 요청과 방문은 한 번만 연결할 수 있습니다.");
        }
        long completed = fulfillments.sumCompletedByRequestId(requestId);
        long allocated = fulfillments.sumAllocatedToVisitTest(visitId, request.getTestTypeId());
        if (count > request.getRequestedCount() - completed || count > provided.getTestCount() - allocated) {
            throw conflict("요청 잔여 건수 또는 실제 제공 검사 건수를 초과합니다.");
        }
        RequestFulfillment fulfillment = new RequestFulfillment();
        fulfillment.setRequestId(requestId);
        fulfillment.setVisitId(visitId);
        fulfillment.setCompletedCount(count);
        fulfillments.saveAndFlush(fulfillment);
        if (completed + count == request.getRequestedCount()) {
            request.setStatus("DONE");
            requests.saveAndFlush(request);
        }
        Map<String, Object> full = mapView(request);
        return fields("requestId", requestId, "visitId", visitId, "appliedCount", count,
                "requestedCount", full.get("requestedCount"), "completedCount", full.get("completedCount"),
                "remainingCount", full.get("remainingCount"), "status", full.get("status"),
                "isPartiallyFulfilled", full.get("isPartiallyFulfilled"), "completedDate", full.get("completedDate"),
                "daysToComplete", full.get("daysToComplete"));
    }

    private Map<String, Object> organization(Long id) {
        if (id == null) {
            return null;
        }
        Organization organization = organizations.findById(id).orElseThrow(RequestService::missing);
        return fields("id", id, "name", organization.getName());
    }

    private Map<String, Object> mapView(TestRequest request) {
        long completed = fulfillments.sumCompletedByRequestId(request.getId());
        long requested = request.getRequestedCount();
        boolean done = request.getStatus().equals("DONE");
        LocalDate completedDate = done ? fulfillments.findCompletedDate(request.getId()) : null;
        Place place = places.findById(request.getPlaceId()).orElseThrow(RequestService::missing);
        Dong dong = dongs.findById(place.getDongId()).orElseThrow(RequestService::missing);
        TestType type = testTypes.findById(request.getTestTypeId()).orElseThrow(RequestService::missing);
        return fields("requestId", request.getId(),
                "place", fields("id", place.getId(), "name", place.getName(), "dongId", dong.getId(), "dongName", dong.getName()),
                "testType", fields("id", type.getId(), "name", type.getName(), "serviceCode", type.getServiceCode()),
                "requestedCount", requested, "completedCount", completed, "remainingCount", requested - completed,
                "isPartiallyFulfilled", completed > 0 && completed < requested, "requestedDate", request.getRequestedDate(),
                "daysPending", done ? null : ChronoUnit.DAYS.between(request.getRequestedDate(), today()),
                "requestedByOrganization", organization(request.getRequestedByOrganizationId()),
                "status", request.getStatus(), "plannedOrganization", organization(request.getPlannedOrganizationId()),
                "plannedDate", request.getPlannedDate(), "completedDate", completedDate,
                "daysToComplete", completedDate == null ? null : ChronoUnit.DAYS.between(request.getRequestedDate(), completedDate));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> view(long id) {
        return mapView(requests.findById(id).orElseThrow(RequestService::missing));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> placeRequests(long placeId) {
        List<Map<String, Object>> items = new ArrayList<>();
        long organizationId = Actor.current().organizationId();
        for (TestRequest request : requests.findForPlaceSummary(placeId, today().minusDays(30))) {
            Map<String, Object> item = mapView(request);
            item.put("fulfillments", fulfillments.findByRequestId(request.getId()).stream()
                    .map(this::fulfillmentView).toList());
            item.put("assignableToMe", !request.getStatus().equals("DONE") && capable(request.getTestTypeId(), organizationId)
                    && (request.getPlannedOrganizationId() == null || request.getPlannedOrganizationId() == organizationId));
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> fulfillmentView(RequestFulfillment fulfillment) {
        Visit visit = visits.findById(fulfillment.getVisitId()).orElseThrow(RequestService::missing);
        return fields("visitId", visit.getId(), "visitDate", visit.getVisitDate(),
                "organizationId", visit.getOrganizationId(), "completedCount", fulfillment.getCompletedCount());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(boolean assignable, String statuses, Long dongId, Long placeId,
                                    Long testTypeId, String sort, int page, int size) {
        validatePage(page, size);
        validateId(dongId);
        validateId(placeId);
        validateId(testTypeId);
        List<String> statusList = Arrays.asList(statuses.split(",", -1));
        if (statusList.isEmpty() || !Set.of("PENDING", "PLANNED", "DONE").containsAll(statusList)) {
            throw invalid("요청 상태가 올바르지 않습니다.");
        }
        if (!Set.of("oldest", "count").contains(sort)) {
            throw invalid("정렬 값이 올바르지 않습니다.");
        }
        Page<TestRequest> result = requests.search(assignable, Actor.current().organizationId(), statusList,
                dongId, placeId, testTypeId, sort.equals("count"), PageRequest.of(page, size));
        return fields("content", result.getContent().stream().map(this::mapView).toList(),
                "page", page, "size", size, "totalElements", result.getTotalElements(), "totalPages", (long) result.getTotalPages());
    }

    public static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("page는 0 이상, size는 1~100이어야 합니다.");
        }
    }

    public static void validateId(Long id) {
        if (id != null && id < 1) {
            throw invalid("ID는 양수여야 합니다.");
        }
    }
}
