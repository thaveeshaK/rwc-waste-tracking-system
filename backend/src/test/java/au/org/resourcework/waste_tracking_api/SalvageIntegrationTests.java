package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.salvage.*;
import au.org.resourcework.waste_tracking_api.salvage.dto.SalvageRequest;
import au.org.resourcework.waste_tracking_api.salvage.dto.SalvageRequest.*;
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
class SalvageIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired SalvageService service;
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
        jdbc.update("DELETE FROM salvage_material");
        jdbc.update("DELETE FROM salvage_record");
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
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_salvage_material ON salvage_material");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_test_salvage_material()");
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    @ParameterizedTest
    @CsvSource({"10.00,20.00,2", "0.00,20.00,1", "10.00,0.00,1", "0.00,0.00,0"})
    void savesAllFourWeightCombinationsWithMandatoryTimes(String up, String down, int rows) throws Exception {
        var input = input(up, down);
        mvc.perform(post("/api/salvage-records/preview").session(staff).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.crew").value("Alex, Sam"));
        assertThat(count()).isZero();
        var saved = create(input);
        long id = saved.path("id").asLong();
        assertThat(saved.path("up").path("kg").asText()).isEqualTo(up);
        assertThat(saved.path("down").path("kg").asText()).isEqualTo(down);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material WHERE salvage_record_id = ?", Integer.class, id)).isEqualTo(rows);
        assertThat(saved.path("up").path("materials").size()).isEqualTo(up.equals("0.00") ? 0 : 1);
        assertThat(saved.path("down").path("materials").size()).isEqualTo(down.equals("0.00") ? 0 : 1);
        assertThat(saved.path("createdBy").asText()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
        mvc.perform(get("/api/salvage-records/" + id).session(staff)).andExpect(status().isOk());
        String date = LocalDate.now(clock).toString();
        mvc.perform(get("/api/salvage-records").session(staff).param("from", date).param("to", date))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/salvage-records").session(staff).param("to", LocalDate.now(clock).minusDays(1).toString()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void zeroDirectionsDiscardStaleRowsAndPositiveDirectionsPersistIndependentRoundedWeights() throws Exception {
        var base = input("0.00", "0.00");
        var stale = new DirectionInput(LocalTime.of(15, 30), BigDecimal.ZERO, List.of(new MaterialInput(timber, new BigDecimal("35"))));
        var zero = create(new SalvageRequest(base.submissionId(), base.recordDate(), base.crew(), stale, stale, null));
        assertThat(zero.path("up").path("materials").size()).isZero();
        assertThat(zero.path("down").path("materials").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material", Integer.class)).isZero();
        var rounded = new DirectionInput(LocalTime.of(15, 30), new BigDecimal("0.01"),
                List.of(new MaterialInput(timber, new BigDecimal("50")), new MaterialInput(iron, new BigDecimal("50"))));
        var saved = create(new SalvageRequest(UUID.randomUUID(), base.recordDate(), base.crew(), rounded, direction("125.50"), null));
        assertThat(saved.path("up").path("materials").size()).isEqualTo(2);
        assertThat(saved.path("up").path("materials").get(0).path("materialWeight").asText()).isEqualTo("0.01");
        assertThat(saved.path("up").path("materials").get(1).path("materialWeight").asText()).isEqualTo("0.01");
        assertThat(saved.path("down").path("materials").get(0).path("materialWeight").asText()).isEqualTo("125.50");
    }

    @Test
    void rejectsInvalidTripsWithoutSavingEitherDirection() throws Exception {
        var valid = input("10.00", "20.00");
        var invalids = new ArrayList<ObjectNode>();
        var sixtyForty = tree(valid);
        material(sixtyForty, "up").put("percentage", "60.00");
        material(sixtyForty, "down").put("percentage", "40.00");
        invalids.add(sixtyForty);
        for (var side : List.of("up", "down")) {
            var bad = tree(valid); material(bad, side).put("percentage", "99.99"); invalids.add(bad);
            bad = tree(valid); ((ObjectNode) bad.path(side)).put("kg", "-1"); invalids.add(bad);
            bad = tree(valid); ((ObjectNode) bad.path(side)).put("kg", "1.001"); invalids.add(bad);
            bad = tree(input("0.00", "0.00")); ((ObjectNode) bad.path(side)).putNull("time"); invalids.add(bad);
        }
        var bad = tree(valid); bad.put("crew", "   "); invalids.add(bad);
        bad = tree(valid); bad.put("recordDate", LocalDate.now(clock).plusDays(1).toString()); invalids.add(bad);
        bad = tree(valid); material(bad, "up").put("materialCategoryId", category("Lead Acid Batteries")); invalids.add(bad);
        bad = tree(valid); material(bad, "down").put("materialCategoryId", category("Mattresses")); invalids.add(bad);
        bad = tree(valid);
        ((tools.jackson.databind.node.ArrayNode) bad.path("up").path("materials")).add(material(bad, "up").deepCopy());
        invalids.add(bad);
        for (var invalid : invalids) {
            mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material", Integer.class)).isZero();
    }

    @Test
    void seedsExactDirectionSetsAndKeepsSalvageOnlyCategoriesOutOfCratePallet() throws Exception {
        assertThat(names("SALVAGE_UP")).containsExactlyInAnyOrder("Iron and Steel", "Timber", "Textiles", "Plastics",
                "Paper, Cardboard and Books", "Mattresses", "E-waste", "Glass", "Mixed Materials");
        assertThat(names("SALVAGE_DOWN")).containsExactlyInAnyOrder("Iron and Steel", "Aluminium", "Other Non-Ferrous Metals", "Mixed Metals",
                "E-waste", "Lead Acid Batteries", "Timber", "Bricks, Blocks and Pavers", "Building and Demolition Material",
                "Whitegoods / Large Appliances", "Paper, Cardboard and Books", "Plastics", "Glass", "Mixed Materials");
        assertThat(names("CRATE_PALLET")).hasSize(13).doesNotContain("E-waste", "Lead Acid Batteries", "Whitegoods / Large Appliances");
        mvc.perform(get("/api/material-categories").session(staff).param("context", "SALVAGE_UP")).andExpect(jsonPath("$.length()").value(9));
        mvc.perform(get("/api/material-categories").session(staff).param("context", "SALVAGE_DOWN")).andExpect(jsonPath("$.length()").value(14));
        mvc.perform(get("/api/material-categories").session(staff).param("context", "UNKNOWN")).andExpect(status().isBadRequest());
        long container = jdbc.queryForObject("SELECT id FROM container_type WHERE name = 'Apple Crate'", Long.class);
        for (var name : List.of("E-waste", "Lead Acid Batteries", "Whitegoods / Large Appliances")) {
            String body = json.writeValueAsString(Map.of("submissionId", UUID.randomUUID(), "recordDate", LocalDate.now(clock),
                    "containerTypeId", container, "grossWeight", "160.00", "materials", List.of(Map.of("materialCategoryId", category(name), "percentage", "100.00"))));
            mvc.perform(post("/api/crate-pallet-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void correctionsRemoveRowsAtZeroRequireBreakdownAtPositiveAndRejectStaleVersions() throws Exception {
        var originalInput = input("10.00", "20.00");
        var saved = create(originalInput);
        long id = saved.path("id").asLong();
        long initialVersion = saved.path("version").asLong();
        var upZero = new SalvageRequest(originalInput.submissionId(), originalInput.recordDate(), "Changed crew",
                direction("0.00"), direction("20.00"), initialVersion);
        saved = update(id, upZero, 200);
        assertThat(saved.path("up").path("materials").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material WHERE direction = 'UP'", Integer.class)).isZero();
        var bothZero = new SalvageRequest(originalInput.submissionId(), originalInput.recordDate(), "Changed crew",
                direction("0.00"), direction("0.00"), saved.path("version").asLong());
        saved = update(id, bothZero, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material", Integer.class)).isZero();
        for (var side : List.of("up", "down")) {
            var invalid = tree(new SalvageRequest(originalInput.submissionId(), originalInput.recordDate(), "Changed crew",
                    direction("0.00"), direction("0.00"), saved.path("version").asLong()));
            ((ObjectNode) invalid.path(side)).put("kg", "10.00");
            mvc.perform(put("/api/salvage-records/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        var positive = new SalvageRequest(originalInput.submissionId(), originalInput.recordDate(), "Changed crew",
                direction("30.00"), direction("40.00"), saved.path("version").asLong());
        saved = update(id, positive, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material", Integer.class)).isEqualTo(2);
        assertThat(saved.path("createdBy").asText()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
        assertThat(saved.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        update(id, upZero, 409);
        assertThat(jdbc.queryForObject("SELECT kg_up FROM salvage_record WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("30.00");
        mvc.perform(post("/api/salvage-records/preview").session(admin).with(csrf()).param("recordId", String.valueOf(id))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(upZero))).andExpect(status().isConflict());
    }

    @Test
    void retriesAreIdempotentAndConflictingCrewIsRejected() throws Exception {
        var input = input("10.00", "20.00");
        var saved = create(input);
        mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saved.path("id").asLong()));
        var conflict = tree(input); conflict.put("crew", "Different crew");
        mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(conflict))).andExpect(status().isConflict());
        conflict = tree(input); ((ObjectNode) conflict.path("up")).put("time", "11:00");
        mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(conflict))).andExpect(status().isConflict());
        mvc.perform(post("/api/salvage-records").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isConflict());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesSaveOneCompleteTrip() throws Exception {
        var input = input("10.00", "20.00");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<SalvageService.SubmissionResult> submit = () -> { gate.await(); return service.submit(input, System.getenv("INITIAL_STAFF_USERNAME")); };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            gate.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.record().id()).isEqualTo(b.record().id());
            assertThat(a.created()).isNotEqualTo(b.created());
            assertThat(a.record().up().materials()).hasSize(1);
            assertThat(b.record().down().materials()).hasSize(1);
            assertThat(count()).isEqualTo(1);
        }
    }

    @Test
    void securesOperationsAndSoftDeletesTheWholeTrip() throws Exception {
        var input = input("10.00", "20.00");
        mvc.perform(get("/api/salvage-records")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/salvage-records").session(staff).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        long id = create(input).path("id").asLong();
        String path = "/api/salvage-records/" + id;
        mvc.perform(put(path).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/salvage-records/preview").session(staff).with(csrf()).param("recordId", String.valueOf(id))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(path).session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(path).session(staff)).andExpect(status().isNotFound());
        mvc.perform(get("/api/salvage-records").session(admin)).andExpect(jsonPath("$.length()").value(0));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT u.username FROM salvage_record r JOIN app_user u ON u.id = r.deleted_by WHERE r.id = ?", String.class, id))
                .isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
    }

    @Test
    void failureWritingMaterialsRollsBackTheParentAndBothDirections() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_salvage_material() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test material failure'' USING ERRCODE = ''23514''; END'");
        jdbc.execute("CREATE TRIGGER reject_test_salvage_material BEFORE INSERT ON salvage_material FOR EACH ROW EXECUTE FUNCTION reject_test_salvage_material()");
        mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("10.00", "20.00")))).andExpect(status().isConflict());
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salvage_material", Integer.class)).isZero();
    }

    @Test
    void inactiveApplicabilityIsScopedAndHistoricalCorrectionsRemainPossible() throws Exception {
        var input = input("10.00", "20.00");
        var saved = create(input);
        jdbc.update("UPDATE material_category_applicability SET active = false WHERE context = 'SALVAGE_UP' AND material_category_id = ?", timber);
        mvc.perform(get("/api/material-categories").session(staff).param("context", "SALVAGE_UP")).andExpect(jsonPath("$.length()").value(8));
        mvc.perform(get("/api/material-categories").session(staff).param("context", "SALVAGE_DOWN")).andExpect(jsonPath("$.length()").value(14));
        mvc.perform(get("/api/material-categories").session(staff)).andExpect(jsonPath("$.length()").value(13));
        mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("10.00", "20.00")))).andExpect(status().isBadRequest());
        update(saved.path("id").asLong(), new SalvageRequest(input.submissionId(), input.recordDate(), "Corrected crew",
                input.up(), input.down(), saved.path("version").asLong()), 200);
    }

    private List<String> names(String context) {
        return jdbc.queryForList("SELECT m.name FROM material_category m JOIN material_category_applicability a ON a.material_category_id = m.id WHERE a.context = ?", String.class, context);
    }
    private long category(String name) { return jdbc.queryForObject("SELECT id FROM material_category WHERE name = ?", Long.class, name); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM salvage_record", Integer.class); }
    private SalvageRequest input(String up, String down) {
        return new SalvageRequest(UUID.randomUUID(), LocalDate.now(clock), "Alex, Sam", direction(up), direction(down), null);
    }
    private DirectionInput direction(String kg) {
        var weight = new BigDecimal(kg);
        return new DirectionInput(LocalTime.of(9, 15), weight,
                weight.signum() == 0 ? List.of() : List.of(new MaterialInput(timber, new BigDecimal("100.00"))));
    }
    private JsonNode create(SalvageRequest input) throws Exception {
        var result = mvc.perform(post("/api/salvage-records").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode update(long id, SalvageRequest input, int status) throws Exception {
        var result = mvc.perform(put("/api/salvage-records/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private ObjectNode tree(SalvageRequest input) throws Exception { return (ObjectNode) json.readTree(json.writeValueAsString(input)); }
    private ObjectNode material(ObjectNode input, String side) { return (ObjectNode) input.path(side).path("materials").get(0); }
    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", System.getenv("INITIAL_" + role + "_USERNAME"))
                .param("password", System.getenv("INITIAL_" + role + "_PASSWORD")))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
