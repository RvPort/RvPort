package cn.rvport.backend.report.review;

import cn.rvport.backend.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** 文件内容必须由报告绑定的不可变制品提供，不能使用客户端提交的片段。 */
public final class SourceSelection {
    private SourceSelection() { }

    public record Range(int startLine, int startColumn, int endLine, int endColumn) { }
    public record Selected(String snippet, String snippetHash, Range range) { }

    public static Selected extract(String content, String actualHash, String expectedHash, Range range) {
        if (content == null) throw error("SOURCE_CONTEXT_UNAVAILABLE", HttpStatus.CONFLICT);
        if (actualHash == null || actualHash.isBlank() || !Objects.equals(actualHash, expectedHash)) {
            throw error("SOURCE_CONTENT_CHANGED", HttpStatus.CONFLICT);
        }
        if (range == null || range.startLine() < 1 || range.endLine() < range.startLine()) throw invalid();
        if ((long) range.endLine() - range.startLine() + 1 > 1000) throw tooLarge();
        String[] lines = content.split("\\r\\n|\\n|\\r", -1);
        if (range.endLine() > lines.length) throw invalid();
        String start = lines[range.startLine() - 1];
        String end = lines[range.endLine() - 1];
        int from = offset(start, range.startColumn());
        int to = offset(end, range.endColumn());
        if (range.startLine() == range.endLine() && to <= from) throw invalid();
        StringBuilder result = new StringBuilder();
        for (int line = range.startLine(); line <= range.endLine(); line++) {
            if (line > range.startLine()) result.append('\n');
            String value = lines[line - 1];
            result.append(value, line == range.startLine() ? from : 0,
                    line == range.endLine() ? to : value.length());
            if (result.length() > 128 * 1024) throw tooLarge();
        }
        String snippet = result.toString();
        if (snippet.getBytes(StandardCharsets.UTF_8).length > 128 * 1024) throw tooLarge();
        return new Selected(snippet, hash(snippet.getBytes(StandardCharsets.UTF_8)), range);
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static int offset(String line, int column) {
        if (column < 1 || (long) column > (long) line.length() + 1) throw invalid();
        int offset = column - 1;
        if (offset > 0 && offset < line.length() && Character.isHighSurrogate(line.charAt(offset - 1))
                && Character.isLowSurrogate(line.charAt(offset))) throw invalid();
        return offset;
    }

    private static ApiException invalid() { return error("SOURCE_SELECTION_INVALID", HttpStatus.BAD_REQUEST); }
    private static ApiException tooLarge() { return error("SOURCE_SELECTION_TOO_LARGE", HttpStatus.BAD_REQUEST); }
    private static ApiException error(String code, HttpStatus status) {
        return new ApiException(status, code, "源码选区不可用，请重新读取绑定版本并选择有效范围");
    }
}
