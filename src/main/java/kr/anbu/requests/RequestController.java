package kr.anbu.requests;

import jakarta.validation.Valid;
import kr.anbu.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/test-requests")
public class RequestController {
    private final RequestService service;
    public RequestController(RequestService service) { this.service = service; }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "true") boolean assignable,
            @RequestParam(defaultValue = "PENDING,PLANNED") String status,
            @RequestParam(required = false) Long dongId, @RequestParam(required = false) Long placeId,
            @RequestParam(required = false) Long testTypeId, @RequestParam(defaultValue = "oldest") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(assignable, status, dongId, placeId, testTypeId, sort, page, size));
    }
    @PatchMapping("/{requestId}/plan")
    public ApiResponse<?> plan(@PathVariable long requestId, @Valid @RequestBody RequestInput.Plan input) {
        return ApiResponse.ok(service.plan(requestId, input.plannedDate()));
    }
    @PatchMapping("/{requestId}/complete")
    public ApiResponse<?> complete(@PathVariable long requestId, @Valid @RequestBody RequestInput.Complete input) {
        return ApiResponse.ok(service.complete(requestId, input.visitId(), input.completedCount()));
    }
    @PatchMapping("/{requestId}/unplan")
    public ApiResponse<?> unplan(@PathVariable long requestId) {
        return ApiResponse.ok(service.unplan(requestId));
    }
    @DeleteMapping("/{requestId}/plan")
    public ApiResponse<?> deletePlan(@PathVariable long requestId) {
        return ApiResponse.ok(service.unplan(requestId));
    }
}
