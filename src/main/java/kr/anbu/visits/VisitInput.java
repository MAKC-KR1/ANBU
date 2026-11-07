package kr.anbu.visits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public record VisitInput(
        @NotNull LocalDate visitDate,
        @NotNull @Positive Long placeId,
        @NotEmpty List<@NotNull @Pattern(regexp = "[A-D]") String> serviceCodes,
        List<@NotNull @Valid TestCount> performedTests,
        List<@NotNull @Valid TestCount> requestedTests,
        List<@NotNull @Valid Fulfillment> fulfilledRequests,
        @Size(max = 2000) String memo) {

    public VisitInput {
        performedTests = performedTests == null ? List.of() : List.copyOf(performedTests);
        requestedTests = requestedTests == null ? List.of() : List.copyOf(requestedTests);
        fulfilledRequests = fulfilledRequests == null ? List.of() : List.copyOf(fulfilledRequests);
        memo = memo == null ? "" : memo;
    }

    public record TestCount(@NotNull @Positive Long testTypeId,
                            @NotNull @Positive Integer count) {}
    public record Fulfillment(@NotNull @Positive Long requestId,
                              @NotNull @Positive Integer completedCount) {}
    public record Batch(@NotEmpty @Size(max = 100) List<com.fasterxml.jackson.databind.JsonNode> items) {}
}
