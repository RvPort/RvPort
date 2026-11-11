package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.rvport.backend.agentruntime.*;
import cn.rvport.backend.security.*;
import cn.rvport.backend.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class PersonalModelRoutesTests {
    @Configuration @EnableWebMvc static class Mvc {}

    @Test void removedRoutesAreAbsentAndReturn404AfterAuthenticationAndCsrf() {
        var users = mock(UserRepository.class);
        var profiles = mock(ModelProfileService.class);
        var runtimes = mock(AgentRuntimeService.class);
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
                .withUserConfiguration(SecurityConfiguration.class, Mvc.class)
                .withBean(UserRepository.class, () -> users)
                .withBean(SecurityErrorWriter.class, () -> new SecurityErrorWriter(new ObjectMapper().findAndRegisterModules()))
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(ModelProfileController.class, () -> new ModelProfileController(profiles,
                        new ModelProfileRequestParser(), new ModelProfileAgentProbeRequestParser(), new ModelStageAssignmentRequestParser()))
                .withBean(AgentRuntimeController.class, () -> new AgentRuntimeController(runtimes))
                .run(context -> {
                    var mappings = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet();
                    assertThat(mappings).allSatisfy(mapping -> assertThat(mapping.getPatternValues())
                            .noneMatch(path -> path.startsWith("/api/admin/")));
                    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                    UUID id = UUID.randomUUID();
                    String profile = "/api/admin/model-profiles/" + id;
                    Map<String, List<HttpMethod>> old = new LinkedHashMap<>();
                    old.put("/api/admin/model-profiles", List.of(HttpMethod.GET, HttpMethod.POST));
                    old.put(profile, List.of(HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE));
                    for (String action : List.of(":test", ":test-connectivity", ":test-agents")) {
                        old.put(profile + action, List.of(HttpMethod.POST));
                    }
                    old.put(profile + "/agent-probe-results", List.of(HttpMethod.GET));
                    old.put("/api/admin/model-profile-agent-test-runs/" + id, List.of(HttpMethod.GET));
                    old.put("/api/admin/model-stage-assignments", List.of(HttpMethod.GET, HttpMethod.PATCH));
                    old.put("/api/admin/agent-runtimes", List.of(HttpMethod.GET));
                    old.put("/api/admin/agent-runtimes/" + id, List.of(HttpMethod.GET));
                    for (boolean admin : new boolean[]{false, true}) {
                        var user = new UserView(UUID.randomUUID(), admin ? "admin" : "alice", "fixture", null,
                                admin, false, UserStatus.ACTIVE, null, null, null);
                        when(users.findById(user.id())).thenReturn(Optional.of(user));
                        var principal = RvPortUserPrincipal.from(user);
                        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());
                        for (var route : old.entrySet()) {
                            for (HttpMethod method : route.getValue()) {
                                mvc.perform(request(method, route.getKey()).with(authentication(auth)).with(csrf())
                                        .contentType("application/json").content("{}"))
                                        .andExpect(status().isNotFound())
                                        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
                            }
                        }
                        verifyNoInteractions(profiles, runtimes);
                        mvc.perform(get("/api/me/model-stage-assignments").with(authentication(auth)))
                                .andExpect(status().isOk());
                        verify(profiles).listAssignments(user.id());
                        clearInvocations(profiles);
                        mvc.perform(patch("/api/me/model-stage-assignments").with(authentication(auth))
                                .contentType("application/json").content("{}"))
                                .andExpect(status().isForbidden());
                        if (!admin) {
                            mvc.perform(get("/api/admin/users").with(authentication(auth))).andExpect(status().isForbidden());
                        }
                        var disabled = new UserView(user.id(), user.username(), "fixture", null, admin, false,
                                UserStatus.DISABLED, null, null, null);
                        when(users.findById(user.id())).thenReturn(Optional.of(disabled));
                        mvc.perform(get("/api/me/model-stage-assignments").with(authentication(auth)))
                                .andExpect(status().isUnauthorized())
                                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                                .andExpect(header().string("Pragma", "no-cache"));
                        when(users.findById(user.id())).thenReturn(Optional.of(new UserView(user.id(), user.username(),
                                "fixture", null, admin, true, UserStatus.ACTIVE, null, null, null)));
                        mvc.perform(get("/api/me/model-stage-assignments").with(authentication(auth)))
                                .andExpect(status().isForbidden())
                                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
                        verifyNoInteractions(profiles, runtimes);
                    }
                    mvc.perform(get("/api/me/model-stage-assignments")).andExpect(status().isUnauthorized());
                });
    }
}
