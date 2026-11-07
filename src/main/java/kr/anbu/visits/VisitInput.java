package kr.anbu.visits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record VisitInput(
        @NotNull LocalDate visitDate,
        @NotNull @Positive Long placeId,
        @NotEmpty List<@NotNull @Pattern(regexp = "[A-D]") String> serviceCodes,
        List<@NotNull @Valid TestCount> performedTests,
        List<@NotNull @Valid TestCount> requestedTests,
        List<@NotNull @Valid Fulfillment> fulfilledRequests,
        @Size(max = 2000) String memo,
        LocalTime visitTime,
        @Pattern(regexp = "GOOD|CAUTION|FOLLOW_UP") String resultCode) {

    public VisitInput(LocalDate visitDate, Long placeId, List<String> serviceCodes, List<TestCount> performedTests,
                      List<TestCount> requestedTests, List<Fulfillment> fulfilledRequests, String memo) {
        this(visitDate, placeId, serviceCodes, performedTests, requestedTests, fulfilledRequests, memo, null, null);
    }

    public VisitInput {
        performedTests = performedTests == null ? List.of() : List.copyOf(performedTests);
        requestedTests = requestedTests == null ? List.of() : List.copyOf(requestedTests);
        fulfilledRequests = fulfilledRequests == null ? List.of() : List.copyOf(fulfilledRequests);
        memo = memo == null ? "" : memo;
    }

    public record TestCount(@NotNull @Positive Long testTypeId,
                            @NotNull @Positive Integer count,
                            @Positive Long recipientOrganizationId,
                            @Size(max = 500) String note) {
        public TestCount(Long testTypeId, Integer count) {
            this(testTypeId, count, null, null);
        }
    }
    public record Fulfillment(@NotNull @Positive Long requestId,
                              @NotNull @Positive Integer completedCount) {}
    public record Batch(@NotEmpty @Size(max = 100) List<com.fasterxml.jackson.databind.JsonNode> items) {}
}
