package kr.anbu.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.anbu.auth.Actor;
import kr.anbu.config.AnsanMapData;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ansan-map-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("ansan-map")
@AutoConfigureMockMvc
@Transactional
class AnsanMapDataTest {
    private static final String COVERAGE_URL = "/api/v1/map/dongs/{dongId}/coverage";
    private static final double AREA_TOLERANCE = 1e-12;

    @Autowired AnsanMapData importData;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void importedFacilitiesAreVisibleOnTheirDongMapAndStartupCanBeRepeated() throws Exception {
        long daebuId = jdbc.queryForObject("SELECT id FROM dongs WHERE name = '대부동'", Long.class);
        long hallId = jdbc.queryForObject("""
                SELECT id FROM places WHERE dong_id = ? AND name = '풍도마을회관'
                """, Long.class, daebuId);
        importData.run(null);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongs", Long.class)).isEqualTo(25L);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT dong_id) FROM places", Long.class)).isEqualTo(25L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM places WHERE place_type = 'SENIOR_CENTER'
                """, Long.class)).isEqualTo(269L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM places WHERE place_type = 'VILLAGE_HALL'
                """, Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT id FROM places WHERE dong_id = ? AND name = '풍도마을회관'
                """, Long.class, daebuId)).isEqualTo(hallId);

        mvc.perform(get("/api/v1/map/places").param("dongId", Long.toString(daebuId)).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(24)))
                .andExpect(jsonPath("$.data[*].dongName", everyItem(is("대부동"))))
                .andExpect(jsonPath("$.data[*].name", hasItem("풍도마을회관")))
                .andExpect(jsonPath("$.data[*].name", hasItem("풍도경로당")));
    }

    @Test
    void facilityAreasCoverAll25DongBoundariesWithoutOverlappingInteriors() throws Exception {
        var reader = new GeoJsonReader();
        JsonNode dongs;
        try (var input = new ClassPathResource("reference/ansan-dong-boundaries.geojson").getInputStream()) {
            dongs = mapper.readTree(input).path("features");
        }
        assertThat(dongs.size()).isEqualTo(25);
        for (JsonNode dong : dongs) {
            String dongName = dong.path("properties").path("name").asText();
            long id = jdbc.queryForObject("SELECT id FROM dongs WHERE name = ?", Long.class, dongName);
            List<Long> expectedFacilities = jdbc.queryForList(
                    "SELECT id FROM places WHERE dong_id = ? ORDER BY id", Long.class, id);
            Geometry boundary = reader.read(dong.path("geometry").toString());
            JsonNode coverage = fetchCoverage(id);
            assertThat(coverage.path("dongName").asText()).isEqualTo(dongName);
            List<Geometry> areas = new ArrayList<>();
            List<Long> facilityIds = new ArrayList<>();
            Map<String, Geometry> areasByName = new HashMap<>();
            for (JsonNode feature : coverage.path("features")) {
                Geometry area = reader.read(feature.path("geometry").toString());
                assertThat(area).as("%s: %s", dongName, feature.path("properties").path("name").asText())
                        .isInstanceOf(Polygonal.class);
                assertThat(area.isValid()).as("%s polygon validity", dongName).isTrue();
                assertThat(area.getArea()).isPositive();
                areas.add(area);
                areasByName.put(feature.path("properties").path("name").asText(), area);
                facilityIds.add(feature.path("properties").path("placeId").asLong());
            }
            assertThat(facilityIds).as("%s facilities", dongName).containsExactlyElementsOf(expectedFacilities);
            Geometry union = OverlayNGRobust.union(areas);
            double difference = OverlayNGRobust.overlay(boundary, union, OverlayNG.SYMDIFFERENCE).getArea();
            assertThat(difference).as("%s uncovered or outside area", dongName).isLessThan(AREA_TOLERANCE);
            assertThat(areas.stream().mapToDouble(Geometry::getArea).sum()).as("%s overlap", dongName)
                    .isCloseTo(union.getArea(), within(AREA_TOLERANCE));

            if (dongName.equals("대부동")) {
                assertThat(areasByName.get("북11통경로당").getArea())
                        .isCloseTo(areasByName.get("상동경로당").getArea(), within(AREA_TOLERANCE));
            }
        }
    }

    @Test
    void oneEligibleFacilityCoversTheDongAndOutsideOrUnrelatedPlacesAreExcluded() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM dongs WHERE name = '일동'", Long.class);
        long kept = jdbc.queryForObject("SELECT MIN(id) FROM places WHERE dong_id = ?", Long.class, id);
        jdbc.update("DELETE FROM places WHERE dong_id = ? AND id <> ?", id, kept);
        jdbc.update("""
                INSERT INTO places (name, dong_id, address, latitude, longitude, place_type)
                SELECT '경로당 옆 공원', dong_id, address, latitude, longitude, 'OTHER'
                FROM places WHERE id = ?
                """, kept);
        jdbc.update("""
                INSERT INTO places (name, dong_id, address, latitude, longitude, place_type)
                VALUES ('동 밖 경로당', ?, '테스트', 37, 126, 'SENIOR_CENTER')
                """, id);

        JsonNode only = fetchCoverage(id).path("features");
        assertThat(only.size()).isEqualTo(1);
        assertThat(only.get(0).path("id").asLong()).isEqualTo(kept);
        jdbc.update("DELETE FROM places WHERE id = ?", kept);
        assertThat(fetchCoverage(id).path("features").isEmpty()).isTrue();

        mvc.perform(get(COVERAGE_URL, Long.MAX_VALUE).with(staff()))
                .andExpect(status().isNotFound());
        mvc.perform(get(COVERAGE_URL, id)).andExpect(status().isUnauthorized());
    }

    private JsonNode fetchCoverage(long id) throws Exception {
        byte[] response = mvc.perform(get(COVERAGE_URL, id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("FeatureCollection"))
                .andReturn().getResponse().getContentAsByteArray();
        return mapper.readTree(response).path("data");
    }

    private RequestPostProcessor staff() {
        return authentication(new UsernamePasswordAuthenticationToken(
                new Actor(1L, "테스트 담당자", "STAFF", 1L), null, List.of()));
    }
}
