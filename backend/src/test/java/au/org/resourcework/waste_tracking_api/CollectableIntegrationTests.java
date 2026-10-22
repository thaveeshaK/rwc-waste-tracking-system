package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.collectables.*;
import au.org.resourcework.waste_tracking_api.collectables.dto.CollectableRequest;
import au.org.resourcework.waste_tracking_api.collectables.dto.CollectableRequest.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
class CollectableIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired CollectableService service;
    MockMvc mvc;
    MockHttpSession staff;
    MockHttpSession admin;
    long timber;
    long iron;
    private boolean verified;

    @BeforeEach
    void setup() throws Exception {
        verified = false;
        verifyIsolation();
        verified = true;
        jdbc.update("DELETE FROM collectable_material");
        jdbc.update("DELETE FROM collectable_record");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        timber = category("Timber");
        iron = category("Iron and Steel");
        staff = login("STAFF");
        admin = login("ADMIN");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!verified) return;
        verifyIsolation();
        jdbc.update("UPDATE material_category_applicability SET active = true");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_collectables_material ON collectable_material");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_test_collectables_material()");
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    private static final String BASE = "/api/collectable-records";

    @Test
    void previewAndCreateCalculateFixedTareAndIgnoreClientCalculatedFields() throws Exception {
        var input = input("12.00");
        var client = tree(input).put("tareWeight", "0").put("netWeight", "999");
        mvc.perform(post(BASE + "/preview").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grossWeight").value("12.00"))
                .andExpect(jsonPath("$.tareWeight").value("2.00")).andExpect(jsonPath("$.netWeight").value("10.00"))
                .andExpect(jsonPath("$.materials[0].materialWeight").value("10.00"));
        assertThat(count()).isZero();
        var result = mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(client)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.netWeight").value("10.00")).andReturn();
        var saved = json.readTree(result.getResponse().getContentAsString());
        assertThat(saved.path("materials").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collectable_material", Integer.class)).isEqualTo(1);
        assertThat(saved.path("createdBy").asText()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
        assertThat(saved.path("createdAt")).isEqualTo(saved.path("updatedAt"));
        mvc.perform(get(BASE + "/" + saved.path("id").asLong()).session(staff)).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidWeightsTotalsRequiredFieldsAndCategoriesWithoutSaving() throws Exception {
        var cases = new ArrayList<ObjectNode>();
        for (String gross : List.of("2", "1.99", "0", "-1", "12.001", "100000000")) cases.add(tree(input(gross)));
        cases.add(tree(input("12")).putNull("grossWeight"));
        cases.add(tree(input("12")).putNull("recordDate"));
        cases.add(tree(input("12")).put("recordDate", LocalDate.now(clock).plusDays(1).toString()));
        var empty = tree(input("12")); empty.putArray("materials"); cases.add(empty);
        var missing = tree(input("12")); missing.remove("materials"); cases.add(missing);
        for (String pct : List.of("99.99", "100.01", "-1", "50.001")) {
            var invalid = tree(input("12")); ((ObjectNode) invalid.path("materials").get(0)).put("percentage", pct); cases.add(invalid);
        }
        for (String name : List.of("E-waste", "Lead Acid Batteries", "Whitegoods / Large Appliances", "TVs / Monitors / Projectors", "Everything Else")) {
            var invalid = tree(input("12")); ((ObjectNode) invalid.path("materials").get(0)).put("materialCategoryId", category(name)); cases.add(invalid);
        }
        var duplicate = tree(input("12")); duplicate.withArray("materials").add(duplicate.path("materials").get(0).deepCopy()); cases.add(duplicate);
        for (var invalid : cases) mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test
    void categoriesMatchDatasheetAndHistoricalInactiveReferencesRemainCorrectable() throws Exception {
        var names = jdbc.queryForList("SELECT m.name FROM material_category m JOIN material_category_applicability a ON a.material_category_id=m.id WHERE a.context='COLLECTABLES'", String.class);
        assertThat(names).containsExactlyInAnyOrder("Iron and Steel", "Aluminium", "Other Non-Ferrous Metals", "Mixed Metals", "Textiles", "Mattresses", "Timber", "Bricks, Blocks and Pavers", "Building and Demolition Material", "Paper, Cardboard and Books", "Plastics", "Glass", "Mixed Materials");
        mvc.perform(get("/api/material-categories").session(staff).param("context", "COLLECTABLES")).andExpect(jsonPath("$.length()").value(13));
        var input = input("12"); var saved = create(input);
        jdbc.update("UPDATE material_category_applicability SET active=false WHERE context='COLLECTABLES' AND material_category_id=?", timber);
        mvc.perform(get("/api/material-categories").session(staff).param("context", "COLLECTABLES")).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/material-categories").session(staff)).andExpect(jsonPath("$.length()").value(13));
        mvc.perform(get("/api/material-categories").session(staff).param("context", "PICKUP")).andExpect(jsonPath("$.length()").value(13));
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("12")))).andExpect(status().isBadRequest());
        update(saved.path("id").asLong(), new CollectableRequest(input.submissionId(), input.recordDate(), new BigDecimal("13"), input.materials(), saved.path("version").asLong()), 200);
    }

    @Test
    void duplicateRetriesCompareDateGrossAndPercentagesNumerically() throws Exception {
        var input = input("12"); var saved = create(input);
        var retry = new CollectableRequest(input.submissionId(), input.recordDate(), new BigDecimal("12.00"), List.of(new MaterialInput(timber, new BigDecimal("100.00"))), null);
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(retry)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saved.path("id").asLong()));
        for (var changed : List.of(tree(input).put("grossWeight", "13"), tree(input).put("recordDate", input.recordDate().minusDays(1).toString()))) {
            mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        }
        var changed = tree(input); ((ObjectNode) changed.path("materials").get(0)).put("percentage", "99");
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        mvc.perform(post(BASE).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isConflict());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesStoreOneCompleteTub() throws Exception {
        var input = input("12"); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> task = () -> { start.await(); return service.submit(input, System.getenv("INITIAL_STAFF_USERNAME")).record().id(); };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collectable_material", Integer.class)).isEqualTo(1);
    }

    @Test
    void adminCorrectionsRetainTareRoundAndRejectStaleOrZeroNet() throws Exception {
        var input = input("12"); var saved = create(input); long id = saved.path("id").asLong();
        var correction = new CollectableRequest(input.submissionId(), input.recordDate(), new BigDecimal("2.01"),
                List.of(new MaterialInput(timber, new BigDecimal("50")), new MaterialInput(iron, new BigDecimal("50"))), saved.path("version").asLong());
        var updated = update(id, correction, 200);
        assertThat(updated.path("tareWeight").asText()).isEqualTo("2.00");
        assertThat(updated.path("netWeight").asText()).isEqualTo("0.01");
        for (var row : updated.path("materials")) assertThat(row.path("materialWeight").asText()).isEqualTo("0.01");
        assertThat(updated.path("version").asLong()).isGreaterThan(saved.path("version").asLong());
        assertThat(updated.path("createdBy")).isEqualTo(saved.path("createdBy"));
        assertThat(updated.path("createdAt")).isEqualTo(saved.path("createdAt"));
        assertThat(updated.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        update(id, correction, 409);
        mvc.perform(post(BASE + "/preview").param("recordId", String.valueOf(id)).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(correction))).andExpect(status().isConflict());
        update(id, new CollectableRequest(input.submissionId(), input.recordDate(), new BigDecimal("2"), input.materials(), updated.path("version").asLong()), 400);
        assertThat(jdbc.queryForObject("SELECT net_weight FROM collectable_record WHERE id=?", BigDecimal.class, id)).isEqualByComparingTo("0.01");
    }

    @Test
    void permissionsCsrfDateFiltersAndSoftDeletion() throws Exception {
        var input = input("12"); String body = json.writeValueAsString(input);
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).session(staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var saved = create(input); long id = saved.path("id").asLong(); String path = BASE + "/" + id;
        mvc.perform(put(path).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(delete(path).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/preview").param("recordId", String.valueOf(id)).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get(BASE).session(staff).param("from", input.recordDate().toString()).param("to", input.recordDate().toString())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(BASE).session(staff).param("to", input.recordDate().minusDays(1).toString())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(BASE).session(staff).param("from", input.recordDate().toString()).param("to", input.recordDate().minusDays(1).toString())).andExpect(status().isBadRequest());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(path).session(staff)).andExpect(status().isNotFound());
        mvc.perform(get(BASE).session(admin)).andExpect(jsonPath("$.length()").value(0));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collectable_material", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT u.username FROM collectable_record r JOIN app_user u ON u.id=r.deleted_by WHERE r.id=?", String.class, id)).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void rollbackAndDatabaseConstraintsProtectCalculationData() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_collectables_material() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test material failure'' USING ERRCODE = ''23514''; END'");
        jdbc.execute("CREATE TRIGGER reject_test_collectables_material BEFORE INSERT ON collectable_material FOR EACH ROW EXECUTE FUNCTION reject_test_collectables_material()");
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("12")))).andExpect(status().isConflict());
        assertThat(count()).isZero();
        jdbc.execute("DROP TRIGGER reject_test_collectables_material ON collectable_material");
        long id = create(input("12")).path("id").asLong();
        assertThatThrownBy(() -> jdbc.update("UPDATE collectable_record SET gross_weight=2,net_weight=0 WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE collectable_record SET tare_weight=1,net_weight=11 WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private long category(String name) { return jdbc.queryForObject("SELECT id FROM material_category WHERE name=?", Long.class, name); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM collectable_record", Integer.class); }
    private CollectableRequest input(String gross) {
        return new CollectableRequest(UUID.randomUUID(), LocalDate.now(clock), new BigDecimal(gross),
                List.of(new MaterialInput(timber, new BigDecimal("100")), new MaterialInput(iron, BigDecimal.ZERO)), null);
    }
    private JsonNode create(CollectableRequest input) throws Exception {
        var result = mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode update(long id, CollectableRequest input, int status) throws Exception {
        var result = mvc.perform(put(BASE + "/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private ObjectNode tree(CollectableRequest input) throws Exception { return (ObjectNode) json.readTree(json.writeValueAsString(input)); }
    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", System.getenv("INITIAL_" + role + "_USERNAME"))
                .param("password", System.getenv("INITIAL_" + role + "_PASSWORD")))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
