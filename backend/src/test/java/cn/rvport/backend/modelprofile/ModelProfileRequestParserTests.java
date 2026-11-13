package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.common.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ModelProfileRequestParserTests {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ModelProfileRequestParser parser = new ModelProfileRequestParser();

    @Test
    void parsesEveryAllowedFieldAndTracksApiKeyPresence() throws Exception {
        UUID runtimeId = UUID.randomUUID();
        ModelProfileRequest request = parser.parse(node("""
                {"name":"model","enabled":true,"modelProviderType":"OPENAI_COMPATIBLE",
                 "baseUrl":"https://example.test/v1","model":"demo","wireApi":"responses","apiKey":"secret",
                 "agentRuntimeId":"%s","timeoutSeconds":30,"maxRuntimeSeconds":300}
                """.formatted(runtimeId)));

        assertThat(request.name()).isEqualTo("model");
        assertThat(request.enabled()).isTrue();
        assertThat(request.agentRuntimeId()).isEqualTo(runtimeId);
        assertThat(request.apiKeyPresent()).isTrue();
        assertThat(request.presentFields()).contains("name", "apiKey", "maxRuntimeSeconds");
    }

    @Test
    void acceptsEmptyObjectAndExplicitNulls() throws Exception {
        ModelProfileRequest empty = parser.parse(node("{}"));
        ModelProfileRequest nullable = parser.parse(node("{\"name\":null,\"enabled\":null,\"timeoutSeconds\":null,\"agentRuntimeId\":null}"));

        assertThat(empty.presentFields()).isEmpty();
        assertThat(empty.apiKeyPresent()).isFalse();
        assertThat(nullable.name()).isNull();
        assertThat(nullable.enabled()).isNull();
        assertThat(nullable.timeoutSeconds()).isNull();
        assertThat(nullable.agentRuntimeId()).isNull();
        assertThat(nullable.presentFields()).containsExactlyInAnyOrder("name", "enabled", "timeoutSeconds", "agentRuntimeId");
    }

    @Test
    void rejectsNonObjectAndUnknownFields() throws Exception {
        for (String key : java.util.List.of("ownerUserId", "configurationUserId", "executablePath", "executable_path", "command")) {
            assertInvalid(node("{\"" + key + "\":null}"), "请求包含不可写字段: " + key);
        }
        assertInvalid(null, "请求体必须是 JSON object");
        assertInvalid(node("[]"), "请求体必须是 JSON object");
        assertInvalid(node("{\"id\":\"forbidden\"}"), "请求包含不可写字段: id");
    }

    @Test
    void rejectsWrongScalarTypesAndMalformedUuid() throws Exception {
        assertInvalid(node("{\"name\":1}"), "name 必须是 string");
        assertInvalid(node("{\"enabled\":\"yes\"}"), "enabled 必须是 boolean");
        assertInvalid(node("{\"timeoutSeconds\":1.5}"), "timeoutSeconds 必须是 integer");
        assertInvalid(node("{\"maxRuntimeSeconds\":9223372036854775807}"), "maxRuntimeSeconds 必须是 integer");
        assertInvalid(node("{\"agentRuntimeId\":42}"), "agentRuntimeId 必须是 string");
        assertInvalid(node("{\"agentRuntimeId\":\"bad\"}"), "agentRuntimeId 必须是 UUID");
    }

    private JsonNode node(String value) throws Exception {
        return json.readTree(value);
    }

    private void assertInvalid(JsonNode body, String message) {
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(ApiException.class)
                .hasMessage(message)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }
}
