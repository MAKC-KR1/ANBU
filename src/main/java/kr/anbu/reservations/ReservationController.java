package kr.anbu.reservations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import kr.anbu.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visit-reservations")
public class ReservationController {
    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam @Positive long placeId) {
        return ApiResponse.ok(reservations.list(placeId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@Valid @RequestBody ReservationService.Input input) {
        return ApiResponse.ok(reservations.create(input));
    }
}
