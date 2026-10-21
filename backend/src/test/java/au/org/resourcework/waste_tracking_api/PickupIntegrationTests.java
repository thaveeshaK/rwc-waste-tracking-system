package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.pickup.*;
import au.org.resourcework.waste_tracking_api.pickup.dto.PickupRequest;
import au.org.resourcework.waste_tracking_api.pickup.dto.PickupRequest.*;
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
class PickupIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired PickupService service;
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
        jdbc.update("DELETE FROM pickup_material");
        jdbc.update("DELETE FROM pickup_record");
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
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_pickup_material ON pickup_material");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_test_pickup_material()");
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    @Test
    void previewsWithoutSavingAndStoresFinalWeightAndOnlyPositiveRows() throws Exception {
        var input = input("125.50");
        var tree = tree(input);
        tree.put("materialWeight", "99999"); // Calculated client values cannot affect saved results.
        mvc.perform(post("/api/pickup-records/preview").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(tree))).andExpect(status().isOk())
                .andExpect(jsonPath("$.netWeight").value("125.50")).andExpect(jsonPath("$.materials[0].materialWeight").value("125.50"));
        assertThat(count()).isZero();
        var saved = create(input);
        assertThat(saved.path("pickupLocation").asText()).isEqualTo("Test location");
        assertThat(saved.path("netWeight").asText()).isEqualTo("125.50");
        assertThat(saved.path("materials").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pickup_material", Integer.class)).isEqualTo(1);
        assertThat(saved.path("createdBy").asText()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
        assertThat(saved.path("updatedAt").asText()).isEqualTo(saved.path("createdAt").asText());
        mvc.perform(get("/api/pickup-records/" + saved.path("id").asLong()).session(staff)).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidWeightsRequiredFieldsTotalsAndCategoriesWithoutSaving() throws Exception {
        var cases = new ArrayList<ObjectNode>();
        for (String weight : List.of("0", "-0.01", "1.001", "100000000")) cases.add(tree(input("10")).put("netWeight", weight));
        cases.add(tree(input("10")).putNull("netWeight"));
        cases.add(tree(input("10")).putNull("recordDate"));
        cases.add(tree(input("10")).put("recordDate", LocalDate.now(clock).plusDays(1).toString()));
        cases.add(tree(input("10")).put("pickupLocation", "  "));
        cases.add(tree(input("10")).put("pickupLocation", "x".repeat(256)));
        var empty = tree(input("10")); empty.putArray("materials"); cases.add(empty);
        var missing = tree(input("10")); missing.remove("materials"); cases.add(missing);
        for (String total : List.of("99.99", "100.01", "-1", "50.001")) {
            var invalid = tree(input("10")); ((ObjectNode) invalid.path("materials").get(0)).put("percentage", total); cases.add(invalid);
        }
        for (String name : List.of("E-waste", "Lead Acid Batteries", "Whitegoods / Large Appliances")) {
            var invalid = tree(input("10")); ((ObjectNode) invalid.path("materials").get(0)).put("materialCategoryId", category(name)); cases.add(invalid);
        }
        var duplicate = tree(input("10")); duplicate.withArray("materials").add(duplicate.path("materials").get(0).deepCopy()); cases.add(duplicate);
        for (var invalid : cases) {
            mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        assertThat(count()).isZero();
    }

    @Test
    void pickupCategorySetIsExplicitAndIndependent() throws Exception {
        var names = jdbc.queryForList("SELECT m.name FROM material_category m JOIN material_category_applicability a ON a.material_category_id=m.id WHERE a.context='PICKUP'", String.class);
        assertThat(names).containsExactlyInAnyOrder("Iron and Steel", "Aluminium", "Other Non-Ferrous Metals", "Mixed Metals", "Textiles", "Mattresses", "Timber", "Bricks, Blocks and Pavers", "Building and Demolition Material", "Paper, Cardboard and Books", "Plastics", "Glass", "Mixed Materials");
        mvc.perform(get("/api/material-categories").session(staff).param("context", "PICKUP")).andExpect(jsonPath("$.length()").value(13));
        var input = input("10"); var saved = create(input);
        jdbc.update("UPDATE material_category_applicability SET active=false WHERE context='PICKUP' AND material_category_id=?", timber);
        mvc.perform(get("/api/material-categories").session(staff).param("context", "PICKUP")).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/material-categories").session(staff)).andExpect(jsonPath("$.length()").value(13));
        mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("10")))).andExpect(status().isBadRequest());
        update(saved.path("id").asLong(), new PickupRequest(input.submissionId(), input.recordDate(), "Historical correction", input.netWeight(), input.materials(), saved.path("version").asLong()), 200);
    }

    @Test
    void retriesMatchNormalizedLocationNumericWeightAndMaterialRows() throws Exception {
        var input = input("10"); var saved = create(input);
        var retry = new PickupRequest(input.submissionId(), input.recordDate(), "Test location", new BigDecimal("10.00"),
                List.of(new MaterialInput(timber, new BigDecimal("100"))), null);
        mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(retry))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saved.path("id").asLong()));
        for (var changed : List.of(tree(input).put("pickupLocation", "Other"), tree(input).put("netWeight", "11"),
                tree(input).put("recordDate", input.recordDate().minusDays(1).toString()))) {
            mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        }
        mvc.perform(post("/api/pickup-records").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isConflict());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void simultaneousRetriesSaveOneCompletePickup() throws Exception {
        var input = input("10"); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> task = () -> { start.await(); return service.submit(input, System.getenv("INITIAL_STAFF_USERNAME")).record().id(); };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pickup_material", Integer.class)).isEqualTo(1);
    }

    @Test
    void correctionsRecalculateAndRejectStaleVersionsAndZero() throws Exception {
        var input = input("10"); var saved = create(input); long id = saved.path("id").asLong();
        var correction = new PickupRequest(input.submissionId(), input.recordDate(), "Corrected location", new BigDecimal("0.01"),
                List.of(new MaterialInput(timber, new BigDecimal("50")), new MaterialInput(iron, new BigDecimal("50"))), saved.path("version").asLong());
        var corrected = update(id, correction, 200);
        assertThat(corrected.path("version").asLong()).isGreaterThan(saved.path("version").asLong());
        assertThat(corrected.path("materials").get(0).path("materialWeight").asText()).isEqualTo("0.01");
        assertThat(corrected.path("materials").get(1).path("materialWeight").asText()).isEqualTo("0.01");
        assertThat(corrected.path("createdBy")).isEqualTo(saved.path("createdBy"));
        assertThat(corrected.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        update(id, correction, 409);
        mvc.perform(post("/api/pickup-records/preview").param("recordId", String.valueOf(id)).session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(correction))).andExpect(status().isConflict());
        update(id, new PickupRequest(input.submissionId(), input.recordDate(), "Bad zero correction", BigDecimal.ZERO, input.materials(), corrected.path("version").asLong()), 400);
        assertThat(jdbc.queryForObject("SELECT net_weight FROM pickup_record WHERE id=?", BigDecimal.class, id)).isEqualByComparingTo("0.01");
    }

    @Test
    void enforcesPermissionsCsrfSoftDeletionAndDateFilters() throws Exception {
        var input = input("10"); var body = json.writeValueAsString(input);
        mvc.perform(post("/api/pickup-records").session(staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pickup-records")).andExpect(status().isUnauthorized());
        var saved = create(input); long id = saved.path("id").asLong(); String path = "/api/pickup-records/" + id;
        mvc.perform(put(path).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/pickup-records/preview").param("recordId", String.valueOf(id)).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(delete(path).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/pickup-records").session(staff).param("from", input.recordDate().toString()).param("to", input.recordDate().toString())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/pickup-records").session(staff).param("to", input.recordDate().minusDays(1).toString())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/pickup-records").session(staff).param("from", input.recordDate().toString()).param("to", input.recordDate().minusDays(1).toString())).andExpect(status().isBadRequest());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(path).session(staff)).andExpect(status().isNotFound());
        mvc.perform(get("/api/pickup-records").session(admin)).andExpect(jsonPath("$.length()").value(0));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pickup_material", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT u.username FROM pickup_record r JOIN app_user u ON u.id=r.deleted_by WHERE r.id=?", String.class, id)).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void materialWriteFailureRollsBackParent() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_pickup_material() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test material failure'' USING ERRCODE = ''23514''; END'");
        jdbc.execute("CREATE TRIGGER reject_test_pickup_material BEFORE INSERT ON pickup_material FOR EACH ROW EXECUTE FUNCTION reject_test_pickup_material()");
        mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("10")))).andExpect(status().isConflict());
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pickup_material", Integer.class)).isZero();
    }

    private long category(String name) { return jdbc.queryForObject("SELECT id FROM material_category WHERE name=?", Long.class, name); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM pickup_record", Integer.class); }
    private PickupRequest input(String weight) {
        return new PickupRequest(UUID.randomUUID(), LocalDate.now(clock), "  Test location  ", new BigDecimal(weight),
                List.of(new MaterialInput(timber, new BigDecimal("100")), new MaterialInput(iron, BigDecimal.ZERO)), null);
    }
    private JsonNode create(PickupRequest input) throws Exception {
        var result = mvc.perform(post("/api/pickup-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode update(long id, PickupRequest input, int status) throws Exception {
        var result = mvc.perform(put("/api/pickup-records/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private ObjectNode tree(PickupRequest input) throws Exception { return (ObjectNode) json.readTree(json.writeValueAsString(input)); }
    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", System.getenv("INITIAL_" + role + "_USERNAME"))
                .param("password", System.getenv("INITIAL_" + role + "_PASSWORD")))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
