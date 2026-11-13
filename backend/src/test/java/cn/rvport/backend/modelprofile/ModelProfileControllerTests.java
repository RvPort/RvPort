package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.user.UserStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

class ModelProfileControllerTests {
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private final ModelProfileService service = mock(ModelProfileService.class);
    private final ModelProfileRequestParser profileParser = mock(ModelProfileRequestParser.class);
    private final ModelProfileAgentProbeRequestParser agentProbeParser = mock(ModelProfileAgentProbeRequestParser.class);
    private final ModelStageAssignmentRequestParser assignmentParser = mock(ModelStageAssignmentRequestParser.class);
    private final ModelProfileController controller = new ModelProfileController(
            service, profileParser, agentProbeParser, assignmentParser);
    private final RvPortUserPrincipal principal = new RvPortUserPrincipal(UUID.randomUUID(), "admin", "Admin",
            "admin@example.com", true, false, UserStatus.ACTIVE);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void delegatesCrudAndAssignmentEndpointsThroughParsers() {
        UUID profileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        JsonNode body = mock(JsonNode.class);
        ModelProfileRequest parsedProfile = new ModelProfileRequest("profile", true, null, "OPENAI",
                "https://example.test/v1", "model", "responses", null, false, null, 60, 120, Set.of("name"));
        ModelProfileAgentProbeRequest parsedProbe = new ModelProfileAgentProbeRequest(List.of(UUID.randomUUID()));
        ModelStageAssignmentRequest parsedAssignments = new ModelStageAssignmentRequest(List.of());
        when(profileParser.parse(body)).thenReturn(parsedProfile);
        when(agentProbeParser.parse(body)).thenReturn(parsedProbe);
        when(assignmentParser.parse(body)).thenReturn(parsedAssignments);

        assertThat(controller.list(principal, null,  request).timestamp()).isNotNull();
        assertThat(controller.create(principal, body, request).timestamp()).isNotNull();
        assertThat(controller.get(principal, profileId,  request).timestamp()).isNotNull();
        assertThat(controller.update(principal, profileId, body, request).timestamp()).isNotNull();
        assertThat(controller.delete(principal, profileId,  request).data()).containsEntry("deleted", true);
        assertThat(controller.testAgents(principal, profileId, body, request).timestamp()).isNotNull();
        assertThat(controller.getAgentTestRun(principal, runId,  request).timestamp()).isNotNull();
        assertThat(controller.listAgentProbeResults(principal, profileId,  request).timestamp()).isNotNull();
        assertThat(controller.listAssignments(principal, request).timestamp()).isNotNull();
        assertThat(controller.saveAssignments(principal, body, request).timestamp()).isNotNull();

        verify(service).create(parsedProfile, principal.id());
        verify(service).update(profileId, parsedProfile, principal.id());
        verify(service).delete(profileId, principal.id());
        verify(service).startAgentTests(profileId, parsedProbe, principal.id());
        verify(service).saveAssignments(parsedAssignments, principal.id());
    }

    @Test
    void optionalTestBodiesSkipParserWhenAbsentAndParseOverridesWhenPresent() {
        UUID profileId = UUID.randomUUID();
        JsonNode body = mock(JsonNode.class);
        ModelProfileRequest parsed = new ModelProfileRequest("override", true, null, "OPENAI",
                "https://example.test/v1", "model", "responses", null, false, null, 60, 120, Set.of());
        when(profileParser.parse(body)).thenReturn(parsed);

        assertThat(controller.test(principal, profileId, null, request).timestamp()).isNotNull();
        assertThat(controller.testConnectivity(principal, profileId, null, request).timestamp()).isNotNull();
        verify(service).test(profileId, null, principal.id());
        verify(service).testConnectivity(profileId, null, principal.id());
        verify(profileParser, never()).parse(null);

        controller.test(principal, profileId, body, request);
        controller.testConnectivity(principal, profileId, body, request);
        verify(service).test(profileId, parsed, principal.id());
        verify(service).testConnectivity(profileId, parsed, principal.id());
    }
}
