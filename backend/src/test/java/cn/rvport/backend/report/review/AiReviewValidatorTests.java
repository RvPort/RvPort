package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiReviewValidatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiReviewValidator validator = new AiReviewValidator();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test void allInputsMustHaveExactlyOneResultAndOrderingUsesManifest() {
        ObjectNode output = mapper.createObjectNode();
        output.putArray("results").add(row(second, "INSUFFICIENT_EVIDENCE", false))
                .add(row(first, "CONFIRMED_ISSUE", true));
        var results = validator.validate(List.of(first, second), Map.of(first, Set.of("evidence-1")), output);
        assertThat(results).extracting(AiReviewValidator.Result::inputId).containsExactly(first, second);
        assertThat(results.get(0).verdict()).isEqualTo(AiReviewValidator.Verdict.CONFIRMED_ISSUE);
        assertThat(results.get(0).evidenceRefs()).containsExactly("evidence-1");
        assertThat(results.get(1).suggestion()).isNull();
        assertThatThrownBy(() -> results.clear()).isInstanceOf(UnsupportedOperationException.class);
        ObjectNode empty = mapper.createObjectNode();
        empty.putArray("results");
        assertThat(validator.validate(List.of(), Map.of(), empty)).isEmpty();
    }

    @Test void incompleteDuplicateUnexpectedAndMalformedResultsFailWholeAttempt() {
        invalid(null);
        invalid(mapper.createArrayNode());
        invalid(mapper.createObjectNode());
        invalid(mapper.createObjectNode().put("results", "invalid"));
        ObjectNode missing = mapper.createObjectNode(); missing.putArray("results"); invalid(missing);
        ObjectNode duplicate = wrapper(row(first, "CONFIRMED_ISSUE", true));
        duplicate.withArray("results").add(row(first, "CONFIRMED_ISSUE", true)); invalid(duplicate);
        invalid(wrapper(row(second, "CONFIRMED_ISSUE", true)));
        ObjectNode scalar = mapper.createObjectNode(); scalar.putArray("results").add(1); invalid(scalar);
        for (String key : List.of("inputId", "verdict", "reason", "reasonEn")) {
            ObjectNode row = row(first,"CONFIRMED_ISSUE",true); row.remove(key); invalid(wrapper(row));
            row = row(first,"CONFIRMED_ISSUE",true); row.putNull(key); invalid(wrapper(row));
            row = row(first,"CONFIRMED_ISSUE",true); row.put(key, 42); invalid(wrapper(row));
            row = row(first,"CONFIRMED_ISSUE",true); row.put(key, " "); invalid(wrapper(row));
        }
        ObjectNode row = row(first,"CONFIRMED_ISSUE",true); row.put("inputId","wrong"); invalid(wrapper(row));
        invalid(wrapper(row(first,"NEW_VERDICT",true)));
        row = row(first,"CONFIRMED_ISSUE",true); row.put("reason","x".repeat(16001)); invalid(wrapper(row));
        assertThatThrownBy(() -> validator.validate(List.of(first,first),Map.of(),missing))
                .hasMessage("REPORT_AI_OUTPUT_INVALID");
    }

    @Test void citationsCannotReferenceOtherInputsAndSuggestionsRequireBothLanguages() {
        ObjectNode row = row(first,"SUSPECTED_FALSE_POSITIVE",true);
        row.put("suggestion","核对编译路径").put("suggestionEn","Check compilation paths");
        assertThat(validator.validate(List.of(first),Map.of(first,Set.of("evidence-1")),wrapper(row)).getFirst().suggestion())
                .isEqualTo("核对编译路径");
        for (String key : List.of("suggestion","suggestionEn")) {
            ObjectNode one = row.deepCopy(); one.remove(key); invalid(wrapper(one));
        }
        row.withArray("evidenceRefs").add("evidence-1"); invalid(wrapper(row));
        row = row(first,"CONFIRMED_ISSUE",true); row.putArray("evidenceRefs").add("other-input-evidence"); invalid(wrapper(row));
        row.putArray("evidenceRefs").add(3); invalid(wrapper(row));
        row.putNull("evidenceRefs"); invalid(wrapper(row));
        invalid(wrapper(row(first,"CONFIRMED_ISSUE",false)));
        assertThatThrownBy(() -> validator.validate(List.of(first),Map.of(),wrapper(row(first,"CONFIRMED_ISSUE",true))))
                .hasMessage("REPORT_AI_OUTPUT_INVALID");
    }

    private ObjectNode row(UUID id, String verdict, boolean evidence) {
        ObjectNode row = mapper.createObjectNode().put("inputId",id.toString()).put("verdict",verdict)
                .put("reason","需结合固定证据核对").put("reasonEn","Review against fixed evidence");
        var refs = row.putArray("evidenceRefs"); if(evidence) refs.add("evidence-1");
        row.putNull("suggestion").putNull("suggestionEn");
        return row;
    }
    private ObjectNode wrapper(ObjectNode row) { var output=mapper.createObjectNode();output.putArray("results").add(row);return output; }
    private void invalid(com.fasterxml.jackson.databind.JsonNode output) {
        assertThatThrownBy(() -> validator.validate(List.of(first),Map.of(first,Set.of("evidence-1")),output))
                .hasMessage("REPORT_AI_OUTPUT_INVALID");
    }
}
