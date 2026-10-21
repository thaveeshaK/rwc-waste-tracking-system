package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.cratepallet.*;
import au.org.resourcework.waste_tracking_api.cratepallet.dto.CratePalletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses a dedicated PostgreSQL database, never an in-memory substitute. See scripts/test-backend.ps1. */
@SpringBootTest(properties = {"debug=false", "logging.level.org.springframework=INFO", "logging.level.org.hibernate=INFO"})
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@ContextConfiguration(initializers = IsolatedTestDatabase.class)
class CratePalletIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired CratePalletService service;
    MockMvc mvc;
    MockHttpSession staff;
    MockHttpSession admin;
    long container;
    long material;
    long otherMaterial;
    private boolean isolationVerified;

    @BeforeEach
    void setup() throws Exception {
        isolationVerified = false;
        verifyIsolation();
        isolationVerified = true;
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM crate_pallet_material");
        jdbc.update("DELETE FROM crate_pallet_record");
        jdbc.update("UPDATE container_type SET active = true WHERE name = 'Apple Crate'");
        jdbc.update("UPDATE container_type SET tare_weight = 60 WHERE name = 'Apple Crate'");
        jdbc.update("UPDATE material_category SET active = true");
        container = jdbc.queryForObject("SELECT id FROM container_type WHERE name = 'Apple Crate'", Long.class);
        material = jdbc.queryForObject("SELECT id FROM material_category WHERE name = 'Timber'", Long.class);
        otherMaterial = jdbc.queryForObject("SELECT id FROM material_category WHERE name = 'Glass'", Long.class);
        staff = login("STAFF");
        admin = login("ADMIN");
    }

    @Test
    void migratesApprovedReferenceDataAndSecuresEndpoints() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM container_type", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_category", Integer.class)).isEqualTo(18);
        mvc.perform(get("/api/material-categories").session(staff))
                .andExpect(jsonPath("$.length()").value(13))
                .andExpect(jsonPath("$[?(@.name == 'E-waste')]").isEmpty());
        mvc.perform(get("/api/crate-pallet-records")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "unknown").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/crate-pallet-records").session(staff).contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(staff)).andExpect(jsonPath("$.role").value("STAFF"));
        mvc.perform(post("/api/auth/logout").session(staff).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(staff)).andExpect(status().isUnauthorized());
    }

    @AfterEach
    void restoreReferenceData() throws Exception {
        if (!isolationVerified) return;
        verifyIsolation();
        jdbc.update("UPDATE container_type SET active = true, tare_weight = 60 WHERE name = 'Apple Crate'");
        jdbc.update("UPDATE material_category SET active = true");
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    @Test
    void previewsThenSavesRecalculatedValuesAndRetriesOnlyOnce() throws Exception {
        var idempotency = UUID.randomUUID();
        var body = body(idempotency);
        mvc.perform(post("/api/crate-pallet-records/preview").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.netWeight").value("100.00"));
        assertThat(count()).isZero();
        // Submitted calculated fields are ignored; accepted values are recalculated from raw inputs.
        var tampered = body.substring(0, body.length() - 1) + ",\"netWeight\":\"9999\",\"tareWeight\":\"0\"}";
        var result = mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tareWeight").value("60.00"))
                .andExpect(jsonPath("$.netWeight").value("100.00"))
                .andExpect(jsonPath("$.materials.length()").value(1)).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crate_pallet_material", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/crate-pallet-records").session(staff)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/crate-pallet-records/" + id).session(staff)).andExpect(status().isOk());
        mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("160.00", "170.00")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/crate-pallet-records").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidInputWithoutPartialRecords() throws Exception {
        var valid = body(UUID.randomUUID());
        for (var invalid : List.of(valid.replace("100.00", "99.99"), valid.replace("160.00", "59.99"),
                valid.replace("160.00", "160.001"), valid.replace("160.00", "-1"),
                valid.replace(LocalDate.now(clock).toString(), LocalDate.now(clock).plusDays(1).toString()),
                valid.replace("\"percentage\":\"0.00\"", "\"percentage\":\"-1.00\""),
                valid.replace("\"materialCategoryId\":" + otherMaterial, "\"materialCategoryId\":" + material),
                valid.replace("\"containerTypeId\":" + container, "\"containerTypeId\":999999"),
                valid.replace("\"percentage\":\"100.00\"", "\"percentage\":null"))) {
            mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        assertThat(count()).isZero();
        mvc.perform(get("/api/crate-pallet-records").session(staff).param("from", "2026-10-22").param("to", "2026-10-21"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restrictsEditsAndRetainsSoftDeletedRecordsAndActors() throws Exception {
        var input = body(UUID.randomUUID());
        var result = mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        String path = "/api/crate-pallet-records/" + id;
        mvc.perform(put(path).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/crate-pallet-records/preview").param("recordId", String.valueOf(id))
                .session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(withVersion(input.replace("160.00", "170.00"), id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.netWeight").value("110.00"))
                .andExpect(jsonPath("$.createdBy").value(System.getenv("INITIAL_STAFF_USERNAME")))
                .andExpect(jsonPath("$.updatedBy").value(System.getenv("INITIAL_ADMIN_USERNAME")));
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path).session(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/crate-pallet-records").session(staff)).andExpect(jsonPath("$.length()").value(0));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crate_pallet_material", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT u.username FROM crate_pallet_record r JOIN app_user u ON u.id = r.deleted_by WHERE r.id = ?",
                String.class, id)).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isConflict());
    }

    @Test
    void preservesHistoricalTareAndInactiveReferencesWhenEditing() throws Exception {
        var input = body(UUID.randomUUID());
        var result = mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        jdbc.update("UPDATE container_type SET tare_weight = 70, active = false WHERE id = ?", container);
        jdbc.update("UPDATE material_category SET active = false WHERE id = ?", material);
        mvc.perform(get("/api/container-types").session(staff)).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/material-categories").session(staff)).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(put("/api/crate-pallet-records/" + id).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(withVersion(input, id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tareWeight").value("60.00"));
        mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID()))).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRetriesProduceOneCompleteRecord() throws Exception {
        var input = new CratePalletRequest(UUID.randomUUID(), LocalDate.now(clock), container, new BigDecimal("160.00"),
                List.of(new CratePalletRequest.MaterialInput(material, new BigDecimal("100.00"))), null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<CratePalletService.SubmissionResult> submit = () -> {
                gate.await();
                return service.submit(input, System.getenv("INITIAL_STAFF_USERNAME"));
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            gate.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.record().id()).isEqualTo(b.record().id());
            assertThat(a.created()).isNotEqualTo(b.created());
            assertThat(a.record().materials()).hasSize(1);
            assertThat(b.record().materials()).hasSize(1);
            assertThat(count()).isEqualTo(1);
        }
    }

    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", System.getenv("INITIAL_" + role + "_USERNAME"))
                .param("password", System.getenv("INITIAL_" + role + "_PASSWORD")))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private int count() { return jdbc.queryForObject("SELECT count(*) FROM crate_pallet_record", Integer.class); }

    private String withVersion(String input, long id) throws Exception {
        var body = json.readTree(input).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) body).put("version",
                jdbc.queryForObject("SELECT version FROM crate_pallet_record WHERE id = ?", Long.class, id));
        return json.writeValueAsString(body);
    }

    @Test
    void staleAdminCorrectionReturnsConflictWithoutOverwritingTheNewerRecord() throws Exception {
        var input = body(UUID.randomUUID());
        var result = mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isCreated()).andReturn();
        var original = json.readTree(result.getResponse().getContentAsString());
        long id = original.path("id").asLong();
        long version = original.path("version").asLong();
        var staleInput = withVersion(input, id);
        mvc.perform(put("/api/crate-pallet-records/" + id).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(staleInput.replace("160.00", "170.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version + 1));
        mvc.perform(put("/api/crate-pallet-records/" + id).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(staleInput.replace("160.00", "180.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("record has changed")));
        mvc.perform(get("/api/crate-pallet-records/" + id).session(admin))
                .andExpect(jsonPath("$.grossWeight").value("170.00"))
                .andExpect(jsonPath("$.version").value(version + 1));
        mvc.perform(put("/api/crate-pallet-records/" + id).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
    }

    private String body(UUID submissionId) {
        return """
            {"submissionId":"%s","recordDate":"%s","containerTypeId":%d,"grossWeight":"160.00",
             "materials":[{"materialCategoryId":%d,"percentage":"100.00"},
                          {"materialCategoryId":%d,"percentage":"0.00"}]}
            """.formatted(submissionId, LocalDate.now(clock), container, material, otherMaterial).strip();
    }
}
