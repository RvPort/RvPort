package cn.rvport.backend.modelprofile;

import cn.rvport.backend.common.error.ApiException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ModelProfileRequestParser {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "name",
            "enabled",
            "modelProviderType",
            "baseUrl",
            "model",
            "wireApi",
            "apiKey",
            "agentRuntimeId",
            "timeoutSeconds",
            "maxRuntimeSeconds"
    );

    public ModelProfileRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw validation("请求体必须是 JSON object");
        }
        Set<String> presentFields = new HashSet<>();
        body.propertyNames().forEach(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw validation("请求包含不可写字段: " + field);
            }
            presentFields.add(field);
        });
        return new ModelProfileRequest(
                optionalString(body, "name"),
                optionalBoolean(body, "enabled"),
                optionalString(body, "executablePath"),
                optionalString(body, "modelProviderType"),
                optionalString(body, "baseUrl"),
                optionalString(body, "model"),
                optionalString(body, "wireApi"),
                optionalString(body, "apiKey"),
                body.has("apiKey"),
                optionalUuid(body, "agentRuntimeId"),
                optionalInteger(body, "timeoutSeconds"),
                optionalInteger(body, "maxRuntimeSeconds"),
                Set.copyOf(presentFields)
        );
    }

    private Boolean optionalBoolean(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw validation(field + " 必须是 boolean");
        }
        return node.booleanValue();
    }

    private String optionalString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw validation(field + " 必须是 string");
        }
        return node.textValue();
    }

    private Integer optionalInteger(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt()) {
            throw validation(field + " 必须是 integer");
        }
        return node.intValue();
    }

    private java.util.UUID optionalUuid(JsonNode body, String field) {
        String value = optionalString(body, field);
        if (value == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw validation(field + " 必须是 UUID");
        }
    }

    ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
