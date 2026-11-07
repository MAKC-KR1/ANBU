package kr.anbu.visits;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.anbu.auth.Actor;
import kr.anbu.config.VoidPreviewData;
import kr.anbu.persistence.repository.UserAccountRepository;
import kr.anbu.persistence.repository.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:void-preview-visits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "ANBU_VOID_PREVIEW_PASSWORD=preview-fixture-test"
})
@ActiveProfiles({"ansan-map", "void-preview"})
@AutoConfigureMockMvc
@Transactional
class VoidPreviewVisitsTest {
    private static final String VISITS = "/api/v1/visits";
    private static final String CLINIC = "대부남동보건진료소";
    private static final String LEGACY_FOOTER = "\n시연용 가상 기록입니다.";

    @Autowired VoidPreviewData seedData;
    @Autowired UserAccountRepository users;
    @Autowired VisitRepository visits;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test
    void referralCentersCoverTheCatalogAndRepeatedSeedingKeepsExistingAccounts() throws Exception {
        var center = users.findByLoginId("단원보건소").orElseThrow();
        jdbc.update("""
                DELETE FROM test_type_organizations
                WHERE organization_id = ? AND test_type_id NOT IN (SELECT id FROM test_types WHERE name = '인지·치매')
                """, center.getOrganizationId());
        seedData.run(null);
        seedData.run(null);

        assertThat(users.findByLoginId("단원보건소").orElseThrow().getId()).isEqualTo(center.getId());
        for (String name : List.of("단원보건소", "상록수보건소", "대부보건지소")) {
            var account = users.findByLoginId(name).orElseThrow();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE name = ?", Long.class, name))
                    .isEqualTo(1L);
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"loginId":"%s","password":"preview-fixture-test"}
                                    """.formatted(name)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user.organization.id").value(account.getOrganizationId().intValue()))
                    .andExpect(jsonPath("$.data.user.organization.name").value(name));
        }
        var response = mvc.perform(get("/api/v1/test-types").with(previewStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name == '인지·치매')].referralOrganizations[*].name",
                        contains(CLINIC, "단원보건소", "상록수보건소")))
                .andExpect(jsonPath("$.data[?(@.name == '구강·치과')].referralOrganizations[*].name",
                        contains(CLINIC, "단원보건소")))
                .andExpect(jsonPath("$.data[?(@.name == '눈·귀')].referralOrganizations[*].name",
                        contains(CLINIC, "대부보건지소")))
                .andReturn().getResponse().getContentAsString();
        var catalog = mapper.readTree(response).path("data");
        assertThat(catalog.size()).isEqualTo(8);
        for (var testType : catalog) {
            assertThat(testType.path("referralOrganizations").size())
                    .as(testType.path("name").asText()).isGreaterThan(1);
        }
    }

    @Test
    void previewHistoryIsReadableAndRepeatedSeedingKeepsEditedAndUserCreatedVisits() throws Exception {
        Long placeId = jdbc.queryForObject("SELECT id FROM places WHERE name = '흥성마을경로당'", Long.class);
        Long fixtureId = jdbc.queryForObject("""
                SELECT id FROM visits WHERE place_id = ? AND visit_date = DATE '2026-10-24'
                """, Long.class, placeId);

        mvc.perform(get(VISITS).param("placeId", placeId.toString()).with(previewStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[*].visitDate",
                        contains("2026-10-24", "2026-09-15", "2026-07-02", "2026-03-17")))
                .andExpect(jsonPath("$.data.content[0].visitTime").value("10:30:00"))
                .andExpect(jsonPath("$.data.content[0].organization.name").value(CLINIC))
                .andExpect(jsonPath("$.data.content[0].resultCode").value("GOOD"))
                .andExpect(jsonPath("$.data.content[0].performedTests[0].name").value("기초 건강·만성질환"))
                .andExpect(jsonPath("$.data.content[0].memo", containsString("방문 목적: 정기 방역 점검\n검사 내용: 위생 점검")))
                .andExpect(jsonPath("$.data.content[0].memo", not(containsString(LEGACY_FOOTER))));

        var fixture = visits.findById(fixtureId).orElseThrow();
        var originalMemo = fixture.getMemo();
        fixture.setMemo(originalMemo + LEGACY_FOOTER);
        visits.flush();
        seedData.run(null);
        assertThat(fixture.getMemo()).isEqualTo(originalMemo);

        mvc.perform(patch(VISITS + "/{id}", fixtureId).with(previewStaff()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visitDate":"2026-10-25","memo":"수정한 시연 기록"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post(VISITS).with(previewStaff()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":%d,"visitDate":"2026-10-24","visitTime":"10:30",
                                 "serviceCodes":["A"],"memo":"사용자 입력 기록"}
                                """.formatted(placeId)))
                .andExpect(status().isCreated());

        seedData.run(null);

        mvc.perform(get(VISITS).param("placeId", placeId.toString()).with(previewStaff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[0].visitId").value(fixtureId))
                .andExpect(jsonPath("$.data.content[0].memo").value("수정한 시연 기록"))
                .andExpect(jsonPath("$.data.content[*].memo", hasItem("사용자 입력 기록")));
    }

    @Test
    void migratesUntouchedLegacyFixturesInPlaceAndLeavesEditsAndUserRecordsAlone() throws Exception {
        var fixtures = visits.findAll();
        var editedFixture = fixtures.stream().filter(visit -> visit.getVisitDate().equals(LocalDate.of(2026, 3, 17)))
                .findFirst().orElseThrow();
        for (var fixture : fixtures) {
            fixture.setVisitDate(fixture.getVisitDate().withYear(2023));
            fixture.setCreatedAt(fixture.getCreatedAt().withYear(2023));
            fixture.setMemo(fixture.getMemo() + LEGACY_FOOTER);
        }
        visits.flush();
        mvc.perform(patch(VISITS + "/{id}", editedFixture.getId()).with(previewStaff())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memo":"사용자가 수정한 이전 시연 기록"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post(VISITS).with(previewStaff()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":%d,"visitDate":"2023-10-24","visitTime":"10:30",
                                 "serviceCodes":["A"],"memo":"별도로 작성한 방문 기록"}
                                """.formatted(editedFixture.getPlaceId())))
                .andExpect(status().isCreated());

        seedData.run(null);
        seedData.run(null);

        assertThat(visits.findAll()).hasSize(5).anySatisfy(visit -> {
            assertThat(visit.getMemo()).isEqualTo("별도로 작성한 방문 기록");
            assertThat(visit.getVisitDate()).isEqualTo(LocalDate.of(2023, 10, 24));
            assertThat(visit.getVisitTime()).isEqualTo(LocalTime.of(10, 30));
        });
        assertThat(fixtures).filteredOn(visit -> visit.getId().equals(editedFixture.getId())).singleElement()
                .satisfies(visit -> {
                    assertThat(visit.getMemo()).isEqualTo("사용자가 수정한 이전 시연 기록");
                    assertThat(visit.getVisitDate()).isEqualTo(LocalDate.of(2023, 3, 17));
                    assertThat(visit.getCreatedAt().getYear()).isEqualTo(2023);
                });
        assertThat(fixtures).filteredOn(visit -> !visit.getId().equals(editedFixture.getId()))
                .extracting(visit -> visit.getVisitDate(), visit -> visit.getVisitTime(), visit -> visit.getCreatedAt().getYear())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 24), LocalTime.of(10, 30), 2026),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 15), LocalTime.of(14, 0), 2026),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 7, 2), LocalTime.of(11, 0), 2026));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PreviewClock {
        @Bean
        @Primary
        Clock previewFixtureClock() {
            return Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    private RequestPostProcessor previewStaff() {
        var account = users.findByLoginId(CLINIC).orElseThrow();
        return authentication(new UsernamePasswordAuthenticationToken(
                new Actor(account.getId(), account.getName(), account.getRole(), account.getOrganizationId()),
                null, List.of()));
    }
}
