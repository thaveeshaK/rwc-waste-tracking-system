package au.org.resourcework.waste_tracking_api;

import au.org.resourcework.waste_tracking_api.whitegoods.*;
import au.org.resourcework.waste_tracking_api.whitegoods.dto.WhitegoodsRequest;
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
class WhitegoodsIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired WhitegoodsService service;
    MockMvc mvc;
    MockHttpSession staff;
    MockHttpSession admin;
    private boolean verified;

    @BeforeEach
    void setup() throws Exception {
        verified = false;
        verifyIsolation();
        verified = true;
        jdbc.update("DELETE FROM whitegoods_record");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        staff = login("STAFF");
        admin = login("ADMIN");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (!verified) return;
        verifyIsolation();
    }

    private void verifyIsolation() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            IsolatedTestDatabase.verify(connection, IsolatedTestDatabase.settings());
        }
    }

    private static final String BASE = "/api/whitegoods-records";

    @Test void savesZeroAndMultipleSameDayEntriesAndIgnoresClientCalculations() throws Exception {
        var input = input(LocalDate.now(clock), "5");
        var client = tree(input).put("kgPerItem", "1").put("estimatedWeight", "999");
        mvc.perform(post(BASE + "/preview").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estimatedWeight").value("240.00"));
        assertThat(count()).isZero();
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(client)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kgPerItem").value("48.00")).andExpect(jsonPath("$.count").value(5));
        var zero = create(input(LocalDate.now(clock), "0"));
        create(input(LocalDate.now(clock), "5"));
        assertThat(count()).isEqualTo(3);
        assertThat(zero.path("estimatedWeight").asText()).isEqualTo("0.00");
        assertThat(zero.path("createdBy").asText()).isEqualTo(System.getenv("INITIAL_STAFF_USERNAME"));
        assertThat(zero.path("updatedAt")).isEqualTo(zero.path("createdAt"));
        summary(YearMonth.now(clock).toString(), 10, "480.00");
    }

    @Test void rejectsInvalidCountDateAndMonthInputs() throws Exception {
        var cases = new ArrayList<ObjectNode>();
        for (String n : List.of("-1", "0.5", "1.9", "2147483648")) cases.add(tree(input(LocalDate.now(clock), n)));
        cases.add(tree(input(LocalDate.now(clock), "1")).putNull("count"));
        cases.add(tree(input(LocalDate.now(clock), "1")).putNull("recordDate"));
        cases.add(tree(input(LocalDate.now(clock).plusDays(1), "1")));
        for (var invalid : cases) mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
        for (String month : List.of("2024-13", "2024-2", "0000-01", "invalid", YearMonth.now(clock).plusMonths(1).toString())) {
            mvc.perform(get(BASE + "/monthly-summary").session(staff).param("month", month)).andExpect(status().isBadRequest());
        }
    }

    @Test void monthlyBoundariesHandleLeapFebruaryDecemberAndEmptyMonths() throws Exception {
        create(input(LocalDate.of(2024, 1, 31), "1"));
        create(input(LocalDate.of(2024, 2, 1), "2"));
        create(input(LocalDate.of(2024, 2, 29), "3"));
        create(input(LocalDate.of(2024, 3, 1), "4"));
        create(input(LocalDate.of(2023, 12, 31), "5"));
        summary("2024-02", 5, "240.00"); summary("2024-01", 1, "48.00");
        summary("2023-12", 5, "240.00"); summary("2024-03", 4, "192.00"); summary("2024-04", 0, "0.00");
    }

    @Test void fullIntegerRangeAndLongMonthlySumDoNotOverflow() throws Exception {
        var first = create(input(LocalDate.of(2024, 1, 1), "2147483647"));
        assertThat(first.path("estimatedWeight").asText()).isEqualTo("103079215056.00");
        create(input(LocalDate.of(2024, 1, 1), "2147483647"));
        summary("2024-01", 4294967294L, "206158430112.00");
    }

    @Test void correctionsMoveCountsBetweenMonthsAndDeletionExcludesThem() throws Exception {
        var input = input(LocalDate.of(2024, 1, 31), "10"); var saved = create(input); long id = saved.path("id").asLong();
        summary("2024-01", 10, "480.00");
        var correction = new WhitegoodsRequest(input.submissionId(), LocalDate.of(2024, 2, 1), new BigDecimal("15"), saved.path("version").asLong());
        var updated = update(id, correction, 200);
        assertThat(updated.path("kgPerItem").asText()).isEqualTo("48.00");
        assertThat(updated.path("createdAt")).isEqualTo(saved.path("createdAt"));
        assertThat(updated.path("updatedBy").asText()).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        summary("2024-01", 0, "0.00"); summary("2024-02", 15, "720.00");
        update(id, correction, 409);
        mvc.perform(post(BASE + "/preview").param("recordId", String.valueOf(id)).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(correction))).andExpect(status().isConflict());
        mvc.perform(delete(BASE + "/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        summary("2024-02", 0, "0.00");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT u.username FROM whitegoods_record r JOIN app_user u ON u.id=r.deleted_by WHERE r.id=?", String.class, id)).isEqualTo(System.getenv("INITIAL_ADMIN_USERNAME"));
        mvc.perform(get(BASE + "/" + id).session(staff)).andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/" + id).session(admin).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void concurrentRetriesProduceOneEntryAndChangedPayloadsConflict() throws Exception {
        var input = input(LocalDate.now(clock), "5"); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Long> task = () -> { start.await(); return service.submit(input, System.getenv("INITIAL_STAFF_USERNAME")).record().id(); };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(count()).isEqualTo(1);
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isOk());
        for (var changed : List.of(tree(input).put("count", 6), tree(input).put("recordDate", input.recordDate().minusDays(1).toString()))) {
            mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        }
        mvc.perform(post(BASE).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isConflict());
        var saved = service.list(null, null).get(0); mvc.perform(delete(BASE + "/" + saved.id()).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isConflict());
    }

    @Test void permissionsCsrfAndDateFiltersFollowExistingRoleModel() throws Exception {
        var input = input(LocalDate.now(clock), "1"); String body = json.writeValueAsString(input);
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/monthly-summary").param("month", "2024-01")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).session(staff).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var saved = create(input); long id = saved.path("id").asLong();
        mvc.perform(put(BASE + "/" + id).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/" + id).session(staff).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/preview").param("recordId", String.valueOf(id)).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get(BASE).session(staff).param("from", input.recordDate().toString()).param("to", input.recordDate().toString())).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(BASE).session(staff).param("to", input.recordDate().minusDays(1).toString())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(BASE).session(admin).param("from", "2024-02-01").param("to", "2024-01-01")).andExpect(status().isBadRequest());
    }

    @Test void databaseConstraintsRejectInvalidCalculationData() throws Exception {
        long id = create(input(LocalDate.now(clock), "1")).path("id").asLong();
        assertThatThrownBy(() -> jdbc.update("UPDATE whitegoods_record SET kg_per_item=47,estimated_weight=47 WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE whitegoods_record SET whitegoods_count=-1,estimated_weight=-48 WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE whitegoods_record SET estimated_weight=0 WHERE id=?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private int count() { return jdbc.queryForObject("SELECT count(*) FROM whitegoods_record", Integer.class); }
    private WhitegoodsRequest input(LocalDate date, String count) { return new WhitegoodsRequest(UUID.randomUUID(), date, new BigDecimal(count), null); }
    private JsonNode create(WhitegoodsRequest input) throws Exception {
        var result = mvc.perform(post(BASE).session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode update(long id, WhitegoodsRequest input, int status) throws Exception {
        var result = mvc.perform(put(BASE + "/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private void summary(String month, long count, String weight) throws Exception {
        mvc.perform(get(BASE + "/monthly-summary").session(staff).param("month", month)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(count)).andExpect(jsonPath("$.estimatedWeight").value(weight));
    }
    private ObjectNode tree(WhitegoodsRequest input) throws Exception { return (ObjectNode) json.readTree(json.writeValueAsString(input)); }
    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("username", System.getenv("INITIAL_" + role + "_USERNAME"))
                .param("password", System.getenv("INITIAL_" + role + "_PASSWORD"))).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
