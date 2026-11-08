package au.org.resourcework.waste_tracking_api;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest(properties={"debug=false","logging.level.org.springframework=INFO","logging.level.org.hibernate=INFO"})
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@ContextConfiguration(initializers=IsolatedTestDatabase.class)
class StaticFrontendIntegrationTests {
 @Autowired WebApplicationContext context; MockMvc mvc;
 @BeforeEach void setup(){mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
 @Test void publicFrontendShellAssetsAndKnownDeepLinksAreAvailable()throws Exception {
  mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
  mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("SPA test shell")));
  mvc.perform(get("/assets/test-shell.js")).andExpect(status().isOk());
  for(String section:new String[]{"users","container-types","material-categories","organisation","reporting","whitegoods"})
   mvc.perform(get("/admin/settings/"+section)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
 }
 @Test void frontendFallbackDoesNotExposeApisOrMaskMissingResources()throws Exception {
  mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/users").with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/not-a-route").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
  mvc.perform(get("/assets/missing.js").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
 }
}
