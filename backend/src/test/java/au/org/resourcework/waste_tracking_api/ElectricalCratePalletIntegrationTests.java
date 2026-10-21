package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.electricalcratepallet.*;
import au.org.resourcework.waste_tracking_api.electricalcratepallet.dto.ElectricalCratePalletRequest;
import au.org.resourcework.waste_tracking_api.electricalcratepallet.dto.ElectricalCratePalletRequest.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"debug=false", "logging.level.org.springframework=INFO", "logging.level.org.hibernate=INFO"})
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@ContextConfiguration(initializers = IsolatedTestDatabase.class)
class ElectricalCratePalletIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired ElectricalCratePalletService service;
    MockMvc mvc;
    MockHttpSession staff;
    MockHttpSession admin;
    long tv;
    long other;
    long cage;
    private boolean verified;

    @BeforeEach
    void setup() throws Exception {
        verified = false;
        verifyIsolation();
        verified = true;
        jdbc.update("DELETE FROM electrical_crate_pallet_material");
        jdbc.update("DELETE FROM electrical_crate_pallet_record");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tv = category("TVs / Monitors / Projectors");
        other = category("Everything Else");
        cage = container("Stillage Cage");
        staff = login("STAFF");
        admin = login("ADMIN");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!verified) return;
        verifyIsolation();
        jdbc.update("UPDATE material_category_applicability SET active = true");
        jdbc.update("UPDATE container_type_applicability SET active = true");
        jdbc.update("UPDATE container_type SET tare_weight=50.00 WHERE id=?", cage);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_electricalcratepallet_material ON electrical_crate_pallet_material");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_test_electricalcratepallet_material()");
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    private static final String BASE = "/api/electrical-crate-pallet-records";

    @Test
    void previewAndCreateUseTareAndReconstructBothCategories() throws Exception {
        var input = input("150.00", "100", "0");
        var client = tree(input).put("tareWeight", "0").put("netWeight", "999");
        mvc.perform(post(BASE + "/preview").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tareWeight").value("50.00"))
                .andExpect(jsonPath("$.netWeight").value("100.00")).andExpect(jsonPath("$.materials.length()").value(2));
        assertThat(count()).isZero();
        var saved = create(input); long id = saved.path("id").asLong();
        assertThat(saved.path("materials").get(0).path("materialWeight").asText()).isEqualTo("100.00");
        assertThat(saved.path("materials").get(1).path("percentage").asText()).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM electrical_crate_pallet_material", Integer.class)).isEqualTo(1);
        mvc.perform(get(BASE + "/" + id).session(staff)).andExpect(status().isOk()).andExpect(jsonPath("$.materials.length()").value(2));
        assertThat(saved.path("createdBy").asText()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
    }

    @Test
    void rejectsZeroNegativeNetInvalidFieldsAndNonElectricalReferences() throws Exception {
        var invalids = new ArrayList<ObjectNode>();
        for (String gross : List.of("50", "49.99", "0", "-1", "150.001", "100000000")) invalids.add(tree(input(gross, "100", "0")));
        invalids.add(tree(input("150", "100", "0")).putNull("grossWeight"));
        invalids.add(tree(input("150", "100", "0")).putNull("recordDate"));
        invalids.add(tree(input("150", "100", "0")).put("recordDate", LocalDate.now(clock).plusDays(1).toString()));
        invalids.add(tree(input("150", "100", "0")).put("containerTypeId", container("Apple Crate")));
        invalids.add(tree(input("150", "100", "0")).putNull("containerTypeId"));
        for (String pct : List.of("99.99", "100.01", "-1", "50.001")) invalids.add(tree(input("150", pct, "0")));
        var missing = tree(input("150", "100", "0")); missing.withArray("materials").remove(1); invalids.add(missing);
        var empty = tree(input("150", "100", "0")); empty.putArray("materials"); invalids.add(empty);
        var duplicate = tree(input("150", "50", "50")); ((ObjectNode) duplicate.path("materials").get(1)).put("materialCategoryId", tv); invalids.add(duplicate);
        for (String name : List.of("Timber", "E-waste")) {
            var wrong = tree(input("150", "100", "0")); ((ObjectNode) wrong.path("materials").get(0)).put("materialCategoryId", category(name)); invalids.add(wrong);
        }
        for (var invalid : invalids) mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test
    void referenceContextsMatchTheDatasheetAndDoNotLeak() throws Exception {
        mvc.perform(get("/api/container-types").session(staff).param("context", "ELECTRICAL_CRATE_PALLET"))
                .andExpect(jsonPath("$.length()").value(4));
        var names = jdbc.queryForList("SELECT c.name FROM container_type c JOIN container_type_applicability a ON a.container_type_id=c.id WHERE a.context='ELECTRICAL_CRATE_PALLET'", String.class);
        assertThat(names).containsExactlyInAnyOrder("Stillage Cage", "Plastic Pallet", "Pine Pallet", "Hardwood Pallet");
        mvc.perform(get("/api/container-types").session(staff)).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/material-categories").session(staff).param("context", "ELECTRICAL_CRATE_PALLET"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("TVs / Monitors / Projectors"))
                .andExpect(jsonPath("$[1].name").value("Everything Else"));
        for (String ctx : List.of("CRATE_PALLET", "PICKUP", "SALVAGE_UP", "SALVAGE_DOWN")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_category_applicability WHERE context=? AND material_category_id IN (?,?)", Integer.class, ctx, tv, other)).isZero();
        }
        mvc.perform(get("/api/container-types").session(staff).param("context", "UNKNOWN")).andExpect(status().isBadRequest());
    }

    @Test
    void tareSnapshotSurvivesReferenceChangesAndSameContainerCorrection() throws Exception {
        var input = input("150", "60", "40"); var saved = create(input); long id = saved.path("id").asLong();
        jdbc.update("UPDATE container_type SET tare_weight=55 WHERE id=?", cage);
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tareWeight").value("50.00"));
        var corrected = update(id, new ElectricalCratePalletRequest(input.submissionId(), input.recordDate(), cage, new BigDecimal("160"), input.materials(), saved.path("version").asLong()), 200);
        assertThat(corrected.path("tareWeight").asText()).isEqualTo("50.00");
        assertThat(corrected.path("netWeight").asText()).isEqualTo("110.00");
        var changed = update(id, new ElectricalCratePalletRequest(input.submissionId(), input.recordDate(), container("Plastic Pallet"), new BigDecimal("16"), input.materials(), corrected.path("version").asLong()), 200);
        assertThat(changed.path("tareWeight").asText()).isEqualTo("6.00");
        assertThat(changed.path("netWeight").asText()).isEqualTo("10.00");
    }

    @Test
    void historicalInactiveReferencesRemainCorrectableButNewRecordsCannotUseThem() throws Exception {
        var input = input("150", "100", "0"); var saved = create(input);
        jdbc.update("UPDATE container_type_applicability SET active=false WHERE context='ELECTRICAL_CRATE_PALLET' AND container_type_id=?", cage);
        jdbc.update("UPDATE material_category_applicability SET active=false WHERE context='ELECTRICAL_CRATE_PALLET' AND material_category_id=?", other);
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("150", "100", "0")))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/container-types").session(staff)).andExpect(jsonPath("$.length()").value(5));
        update(saved.path("id").asLong(), new ElectricalCratePalletRequest(input.submissionId(), input.recordDate(), cage, input.grossWeight(), input.materials(), saved.path("version").asLong()), 200);
    }

    @Test
    void retriesDetectChangedPayloadAndConcurrentSubmissionsSaveOneRecord() throws Exception {
        var input = input("150", "60", "40"); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> task = () -> { start.await(); return service.submit(input, System.getenv("INITIAL_STAFF_USERNAME")).record().id(); };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM electrical_crate_pallet_material", Integer.class)).isEqualTo(2);
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isOk());
        var changes = List.of(tree(input).put("grossWeight", "151"), tree(input).put("containerTypeId", container("Plastic Pallet")), tree(input).put("recordDate", input.recordDate().minusDays(1).toString()));
        for (var changed : changes) mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        var changedPct = tree(input); ((ObjectNode) changedPct.path("materials").get(0)).put("percentage", "61");
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changedPct))).andExpect(status().isConflict());
        mvc.perform(post(BASE).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isConflict());
    }

    @Test
    void correctionsRoundRejectZeroAndRejectStaleVersions() throws Exception {
        var input = input("150", "100", "0"); var saved = create(input); long id = saved.path("id").asLong();
        var correction = new ElectricalCratePalletRequest(input.submissionId(), input.recordDate(), cage, new BigDecimal("50.01"),
                List.of(new MaterialInput(tv, new BigDecimal("50")), new MaterialInput(other, new BigDecimal("50"))), saved.path("version").asLong());
        var updated = update(id, correction, 200);
        for (var row : updated.path("materials")) assertThat(row.path("materialWeight").asText()).isEqualTo("0.01");
        assertThat(updated.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        assertThat(updated.path("createdBy")).isEqualTo(saved.path("createdBy"));
        update(id, correction, 409);
        mvc.perform(post(BASE + "/preview").param("recordId", String.valueOf(id)).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(correction))).andExpect(status().isConflict());
        update(id, new ElectricalCratePalletRequest(input.submissionId(), input.recordDate(), cage, new BigDecimal("50"), input.materials(), updated.path("version").asLong()), 400);
        assertThat(jdbc.queryForObject("SELECT net_weight FROM electrical_crate_pallet_record WHERE id=?", BigDecimal.class, id)).isEqualByComparingTo("0.01");
    }

    @Test
    void permissionsCsrfSoftDeletionAndDateFiltering() throws Exception {
        var input = input("150", "100", "0"); String body = json.writeValueAsString(input);
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).session(staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var saved = create(input); long id = saved.path("id").asLong(); String path = BASE + "/" + id;
        mvc.perform(put(path).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/preview").param("recordId", String.valueOf(id)).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(delete(path).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(BASE).session(staff).param("from", input.recordDate().toString()).param("to", input.recordDate().toString())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(BASE).session(staff).param("to", input.recordDate().minusDays(1).toString())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(path).session(staff)).andExpect(status().isNotFound());
        mvc.perform(get(BASE).session(admin)).andExpect(jsonPath("$.length()").value(0));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM electrical_crate_pallet_material", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT u.username FROM electrical_crate_pallet_record r JOIN app_user u ON u.id=r.deleted_by WHERE r.id=?", String.class, id)).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void materialFailureRollsBackParentAndDatabaseRejectsZeroNet() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_electricalcratepallet_material() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test material failure'' USING ERRCODE = ''23514''; END'");
        jdbc.execute("CREATE TRIGGER reject_test_electricalcratepallet_material BEFORE INSERT ON electrical_crate_pallet_material FOR EACH ROW EXECUTE FUNCTION reject_test_electricalcratepallet_material()");
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("150", "100", "0")))).andExpect(status().isConflict());
        assertThat(count()).isZero();
        jdbc.execute("DROP TRIGGER reject_test_electricalcratepallet_material ON electrical_crate_pallet_material");
        var saved = create(input("150", "100", "0"));
        assertThatThrownBy(() -> jdbc.update("UPDATE electrical_crate_pallet_record SET gross_weight=tare_weight, net_weight=0 WHERE id=?", saved.path("id").asLong()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private long category(String name) { return jdbc.queryForObject("SELECT id FROM material_category WHERE name=?", Long.class, name); }
    private long container(String name) { return jdbc.queryForObject("SELECT id FROM container_type WHERE name=?", Long.class, name); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM electrical_crate_pallet_record", Integer.class); }
    private ElectricalCratePalletRequest input(String gross, String tvPct, String otherPct) {
        return new ElectricalCratePalletRequest(UUID.randomUUID(), LocalDate.now(clock), cage, new BigDecimal(gross),
                List.of(new MaterialInput(tv, new BigDecimal(tvPct)), new MaterialInput(other, new BigDecimal(otherPct))), null);
    }
    private JsonNode create(ElectricalCratePalletRequest input) throws Exception {
        var result = mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode update(long id, ElectricalCratePalletRequest input, int status) throws Exception {
        var result = mvc.perform(put(BASE + "/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private ObjectNode tree(ElectricalCratePalletRequest input) throws Exception { return (ObjectNode) json.readTree(json.writeValueAsString(input)); }
    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", System.getenv("INITIAL_" + role + "_USERNAME"))
                .param("password", System.getenv("INITIAL_" + role + "_PASSWORD")))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
