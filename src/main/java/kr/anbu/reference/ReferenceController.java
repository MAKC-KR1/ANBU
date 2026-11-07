package kr.anbu.reference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.anbu.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReferenceController {
    private final ReferenceService references;

    public ReferenceController(ReferenceService references) {
        this.references = references;
    }

    @GetMapping("/organizations")
    public ApiResponse<?> organizations(@RequestParam(required = false) String type) {
        return ApiResponse.ok(references.organizations(type));
    }

    @GetMapping("/organizations/me/visit-cycles")
    public ApiResponse<?> visitCycles() {
        return ApiResponse.ok(references.visitCycles());
    }

    @PutMapping("/organizations/me/visit-cycles")
    public ApiResponse<?> updateCycles(@Valid @RequestBody CycleInput input) {
        return ApiResponse.ok(references.updateCycles(input));
    }

    @GetMapping("/test-types")
    public ApiResponse<?> testTypes() {
        return ApiResponse.ok(references.testTypes());
    }

    @PutMapping("/test-types/{testTypeId}/organizations")
    public ApiResponse<?> assignOrganizations(@PathVariable long testTypeId,
                                               @Valid @RequestBody AssignmentInput input) {
        return ApiResponse.ok(references.assignOrganizations(testTypeId, input));
    }

    @GetMapping("/dongs")
    public ApiResponse<?> dongs() {
        return ApiResponse.ok(references.dongs());
    }

    @GetMapping("/stats/organizations")
    public ApiResponse<?> statistics(@RequestParam String month) {
        return ApiResponse.ok(references.statistics(month));
    }

    public record Cycle(@NotNull String serviceCode, @Positive Integer cycleDays) {}
    public record CycleInput(@NotNull @Size(min = 1, max = 4) List<@NotNull @Valid Cycle> cycles) {}
    public record AssignmentInput(@NotNull @Size(max = 1000) List<@NotNull @Positive Long> organizationIds) {}
}
