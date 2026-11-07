package kr.anbu.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.anbu.common.ApiResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class DirectoryController {
    private final JsonNode boundaries;
    private final JsonNode contacts;

    public DirectoryController(ObjectMapper mapper) throws IOException {
        boundaries = load(mapper, "ansan-dong-boundaries.geojson");
        contacts = load(mapper, "ansan-health-contacts.json");
    }

    private static JsonNode load(ObjectMapper mapper, String file) throws IOException {
        try (var input = new ClassPathResource("reference/" + file).getInputStream()) {
            return mapper.readTree(input);
        }
    }

    @GetMapping("/map/boundaries")
    public ApiResponse<JsonNode> boundaries() { return ApiResponse.ok(boundaries); }

    @GetMapping("/organization-contacts")
    public ApiResponse<JsonNode> contacts() { return ApiResponse.ok(contacts); }
}
