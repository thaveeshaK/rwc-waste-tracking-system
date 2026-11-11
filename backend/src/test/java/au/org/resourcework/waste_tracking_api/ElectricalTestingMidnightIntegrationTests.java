package au.org.resourcework.waste_tracking_api;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
@Import(ElectricalTestingMidnightIntegrationTests.TestTime.class)
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class ElectricalTestingMidnightIntegrationTests {
    @TestConfiguration static class TestTime {
        @Bean @Primary Clock midnightClock() {
            var clock=mock(Clock.class);when(clock.getZone()).thenReturn(ZoneId.of("Australia/Hobart"));
            when(clock.instant()).thenReturn(Instant.parse("2026-11-08T12:59:59Z"));return clock;
        }
    }
    @Autowired Clock clock;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void ET_16_pendingDateRemainsHistoricalAfterMidnightAndWritesAreForbidden() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {IsolatedTestDatabase.verify(c,IsolatedTestDatabase.settings());}
        when(clock.instant()).thenReturn(Instant.parse("2026-11-08T12:59:59Z")); // 8 Nov 23:59:59 Hobart.
        var date=LocalDate.now(clock);assertThat(date).isEqualTo(LocalDate.of(2026,11,8));
        var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String staff=jdbc.queryForObject("select username from app_user where role='STAFF' limit 1",String.class);
        long category=jdbc.queryForObject("select min(id) from electrical_testing_category",Long.class);
        var savedInput=Map.of("submissionId",UUID.randomUUID(),"recordDate",date.toString(),"categoryId",category,"tallyType","TESTED_AND_PRICED","count",2);
        var saved=json.readTree(mvc.perform(post("/api/electrical-testing/tallies").with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(savedInput))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var pending=new LinkedHashMap<String,Object>(savedInput);pending.put("submissionId",UUID.randomUUID());pending.put("count",3);
        String pendingPayload=json.writeValueAsString(pending);
        var before=jdbc.queryForList("select * from electrical_testing_tally order by id");
        when(clock.instant()).thenReturn(Instant.parse("2026-11-08T13:00:01Z"));
        assertThat(LocalDate.now(clock)).isEqualTo(date.plusDays(1));
        for(int count:List.of(3,4)) {
            pending.put("count",count);
            mvc.perform(post("/api/electrical-testing/tallies").with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(pending)))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.containsString("Only today's tally can be changed")));
        }
        mvc.perform(post("/api/electrical-testing/tallies/"+saved.get("id").asLong()+"/reduction").with(user(staff).roles("STAFF")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("recordDate",date.toString(),"version",saved.get("version").asLong(),"count",1))))
            .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.containsString("Historical tallies are read-only")));
        assertThat(jdbc.queryForList("select * from electrical_testing_tally order by id")).isEqualTo(before);
        assertThat(json.readTree(pendingPayload).get("recordDate").asText()).isEqualTo(date.toString());
    }
}
