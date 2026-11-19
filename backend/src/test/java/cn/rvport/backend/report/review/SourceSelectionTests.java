package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceSelectionTests {
    @Test void extractsUnicodeMultilineAndKeepsColumnSemantics() {
        String content = "开😀头\r\n中间\r尾部\n";
        String hash = SourceSelection.hash(content.getBytes(StandardCharsets.UTF_8));
        var selected = SourceSelection.extract(content, hash, hash, new SourceSelection.Range(1, 2, 3, 2));
        assertThat(selected.snippet()).isEqualTo("😀头\n中间\n尾");
        assertThat(selected.snippetHash()).isEqualTo(SourceSelection.hash("😀头\n中间\n尾".getBytes(StandardCharsets.UTF_8)));
        assertThat(selected.range().endLine()).isEqualTo(3);
        assertThat(SourceSelection.extract(content, hash, hash, new SourceSelection.Range(1, 2, 1, 4)).snippet()).isEqualTo("😀");
        assertThat(SourceSelection.extract("a\n", "h", "h", new SourceSelection.Range(1, 2, 2, 1)).snippet()).isEqualTo("\n");
        assertThat(SourceSelection.hash(new byte[0])).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test void rejectsMissingOrMismatchedSourceBeforeAcceptingAnySelection() {
        assertCode("SOURCE_CONTEXT_UNAVAILABLE", () -> SourceSelection.extract(null, null, null, null));
        for (String actual : new String[]{null, " ", "different"}) {
            assertCode("SOURCE_CONTENT_CHANGED", () -> SourceSelection.extract("a", actual, "h", null));
        }
        assertCode("SOURCE_CONTENT_CHANGED", () -> SourceSelection.extract("a", "h", null, null));
    }

    @Test void rejectsInvalidRangesIncludingSurrogateBoundaries() {
        for (SourceSelection.Range range : new SourceSelection.Range[]{null,
                new SourceSelection.Range(0,1,1,1), new SourceSelection.Range(2,1,1,1),
                new SourceSelection.Range(1,1,3,1), new SourceSelection.Range(1,0,1,2),
                new SourceSelection.Range(1,99,1,99), new SourceSelection.Range(1,1,1,1),
                new SourceSelection.Range(1,4,1,2), new SourceSelection.Range(1,3,1,4),
                new SourceSelection.Range(1,2,1,3), new SourceSelection.Range(1,1,1,Integer.MAX_VALUE)}) {
            assertCode("SOURCE_SELECTION_INVALID", () -> SourceSelection.extract("开😀头", "h", "h", range));
        }
    }

    @Test void refusesOversizedSelectionWithoutSilentlyTruncating() {
        assertCode("SOURCE_SELECTION_TOO_LARGE", () -> SourceSelection.extract("x", "h", "h", new SourceSelection.Range(1,1,1001,1)));
        for (String content : new String[]{"x".repeat(131073), "中".repeat(43691)}) {
            assertCode("SOURCE_SELECTION_TOO_LARGE", () -> SourceSelection.extract(content, "h", "h", new SourceSelection.Range(1,1,1,content.length()+1)));
        }
        String boundary = "x".repeat(131072);
        assertThat(SourceSelection.extract(boundary, "h", "h", new SourceSelection.Range(1,1,1,131073)).snippet()).isEqualTo(boundary);
    }

    private void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
