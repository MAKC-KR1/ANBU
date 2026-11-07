package kr.anbu.map;

import kr.anbu.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MapController {
    private final MapService maps;

    public MapController(MapService maps) {
        this.maps = maps;
    }

    @GetMapping("/map/places")
    public ApiResponse<?> places(
            @RequestParam(required = false) Long dongId,
            @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String gapLevel,
            @RequestParam(required = false) Boolean hasPendingRequest) {
        return ApiResponse.ok(maps.places(dongId, serviceCode, gapLevel, hasPendingRequest));
    }

    @GetMapping("/places/{placeId}/summary")
    public ApiResponse<?> summary(
            @PathVariable long placeId,
            @RequestParam(defaultValue = "5") int historyLimit) {
        return ApiResponse.ok(maps.summary(placeId, historyLimit));
    }
}
