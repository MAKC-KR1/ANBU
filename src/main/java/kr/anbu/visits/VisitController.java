package kr.anbu.visits;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import kr.anbu.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {
    private final VisitService service;
    public VisitController(VisitService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@Valid @RequestBody VisitInput input) {
        return ApiResponse.ok(service.create(input));
    }
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> batch(@Valid @RequestBody VisitInput.Batch input) {
        return ApiResponse.ok(service.batch(input));
    }
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) Long placeId,
            @RequestParam(required = false) Long dongId, @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String serviceCode, @RequestParam(required = false) Long testTypeId,
            @RequestParam(required = false) LocalDate fromDate, @RequestParam(required = false) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(placeId, dongId, organizationId, serviceCode, testTypeId, fromDate, toDate, page, size));
    }
    @PatchMapping("/{visitId}")
    public ApiResponse<?> update(@PathVariable long visitId, @RequestBody JsonNode input) {
        return ApiResponse.ok(service.update(visitId, input));
    }
    @DeleteMapping("/{visitId}")
    public ApiResponse<?> delete(@PathVariable long visitId) {
        return ApiResponse.ok(service.delete(visitId));
    }
}
