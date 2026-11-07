package kr.anbu.requests;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

public final class RequestInput {
    private RequestInput() {}

    public record Complete(@NotNull @Positive Long visitId,
                           @NotNull @Positive Integer completedCount) {}

    public record Plan(@NotNull LocalDate plannedDate) {}
}
