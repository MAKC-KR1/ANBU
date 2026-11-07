package kr.anbu.reference;

import java.util.List;
import kr.anbu.auth.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reference-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class ReferenceApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO organizations(id,name,type) VALUES(900,'기관 A','BRANCH'),(901,'기관 B','HEALTH_CENTER')");
        jdbc.update("INSERT INTO dongs(id,name,gu) VALUES(900,'시험동','시험구')");
        jdbc.update("INSERT INTO organization_dongs VALUES(900,900)");
        jdbc.update("INSERT INTO test_types(id,name,service_code) VALUES(900,'검사','A')");
        jdbc.update("INSERT INTO test_type_organizations VALUES(900,900)");
    }

    private RequestPostProcessor actor(String role) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(new Actor(900L, "테스트", role, 900L), null, List.of()));
    }

    @Test
    void cycleUpdatesRequireManagerAndSupportExplicitlyDisablingDefaults() throws Exception {
        String body = "{\"cycles\":[{\"serviceCode\":\"A\",\"cycleDays\":null}]}";
        mvc.perform(put("/api/v1/organizations/me/visit-cycles").with(actor("STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/organizations/me/visit-cycles").with(actor("ORG_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cycleDays").isEmpty())
                .andExpect(jsonPath("$.data[1].cycleDays").value(90))
                .andExpect(jsonPath("$.data[3].cycleDays").isEmpty());
        mvc.perform(get("/api/v1/organizations/me/visit-cycles").with(actor("STAFF")))
                .andExpect(jsonPath("$.data[0].cycleDays").isEmpty());
    }

    @Test
    void invalidCyclePayloadsDoNotApplyPartially() throws Exception {
        mvc.perform(put("/api/v1/organizations/me/visit-cycles").with(actor("ORG_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cycles\":[{\"serviceCode\":\"A\",\"cycleDays\":12},{\"serviceCode\":\"A\",\"cycleDays\":15}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/organizations/me/visit-cycles").with(actor("ORG_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cycles\":[{\"serviceCode\":\"B\",\"cycleDays\":1.5}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/organizations/me/visit-cycles").with(actor("STAFF")))
                .andExpect(jsonPath("$.data[0].cycleDays").value(30));
    }

    @Test
    void administratorCanReplaceCapabilitiesAndUnknownOrganizationDoesNotDeleteExistingMapping() throws Exception {
        mvc.perform(put("/api/v1/test-types/900/organizations").with(actor("ORG_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"organizationIds\":[901]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/test-types/900/organizations").with(actor("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"organizationIds\":[999]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/test-types").with(actor("STAFF")))
                .andExpect(jsonPath("$.data[0].responsibleOrganizations[0].id").value(900));
        mvc.perform(put("/api/v1/test-types/900/organizations").with(actor("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"organizationIds\":[901]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/test-types").with(actor("STAFF")))
                .andExpect(jsonPath("$.data[0].responsibleOrganizations[0].id").value(901));
    }

    @Test
    void referenceListsPreserveMissingIndicatorsAndMonthValidation() throws Exception {
        mvc.perform(get("/api/v1/dongs").with(actor("STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].elderlyRate").isEmpty())
                .andExpect(jsonPath("$.data[0].indicatorMetadata").isArray());
        mvc.perform(get("/api/v1/organizations?type=BRANCH").with(actor("STAFF")))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].managedDongIds[0]").value(900));
        mvc.perform(get("/api/v1/stats/organizations?month=2026-13").with(actor("STAFF")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stats/organizations?month=2026-11").with(actor("STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].visitCount").value(0));
    }
}
