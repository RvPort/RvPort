package cn.rvport.backend.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 对 compile_commands 条目作保守、确定性的 C/C++ 项目 TU 分类。 */
public final class CompileEntryClassifier {

    private static final Set<String> C_CPP_SUFFIXES = Set.of(
            ".c", ".cc", ".cp", ".cpp", ".cxx", ".c++");
    private final ObjectMapper objectMapper;

    public CompileEntryClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompileEntryClassification classify(Path compileDatabase, Path sourceRoot) throws IOException {
        return classify(compileDatabase, sourceRoot, null);
    }

    public CompileEntryClassification classify(Path compileDatabase, Path sourceRoot, String remoteRoot) throws IOException {
        JsonNode document = objectMapper.readTree(compileDatabase.toFile());
        if (!document.isArray()) {
            return new CompileEntryClassification(0, 0, 0, List.of(), Map.of("INVALID_DOCUMENT", 1));
        }
        int candidates = 0;
        Map<String, Integer> excluded = new LinkedHashMap<>();
        Map<String, CompileEntryClassification.ProjectUnit> units = new LinkedHashMap<>();
        String sourceIdentity = sourceRoot.getFileName().toString();
        for (JsonNode entry : document) {
            String file = entry.path("file").asText("").replace('\\', '/');
            if (!isCpp(file)) {
                increment(excluded, "NON_C_CPP_COMMAND");
                continue;
            }
            candidates++;
            String lower = file.toLowerCase(Locale.ROOT);
            String directory = entry.path("directory").asText("").replace('\\', '/').toLowerCase(Locale.ROOT);
            if (isProbe(lower, directory)) {
                increment(excluded, "BUILD_SYSTEM_PROBE");
                continue;
            }
            String relative = remoteRoot == null ? relativeProjectPath(file, sourceIdentity)
                    : relativeToPinnedRoot(file, entry.path("directory").asText(remoteRoot), remoteRoot);
            if (relative == null) {
                increment(excluded, "OUTSIDE_SOURCE_ROOT");
                continue;
            }
            String context = contextKey(entry);
            String key = relative + "\n" + context;
            if (units.containsKey(key)) {
                increment(excluded, "DUPLICATE_CONTEXT");
                continue;
            }
            units.put(key, new CompileEntryClassification.ProjectUnit(relative, context));
        }
        List<CompileEntryClassification.ProjectUnit> ordered = new ArrayList<>(units.values());
        ordered.sort(java.util.Comparator.comparing(CompileEntryClassification.ProjectUnit::path)
                .thenComparing(CompileEntryClassification.ProjectUnit::contextKey));
        return new CompileEntryClassification(document.size(), candidates, ordered.size(),
                List.copyOf(ordered), Map.copyOf(excluded));
    }

    private String relativeToPinnedRoot(String file, String directory, String root) {
        String normalized = normalize(file);
        if (!normalized.startsWith("/") && !normalized.contains(":")) {
            normalized = directory.replace('\\', '/') + "/" + normalized;
        }
        if (java.util.Arrays.asList(normalized.split("/", -1)).contains("..")) return null;
        normalized = normalized.replace("/./", "/");
        String prefix = root.replace('\\', '/').replaceAll("/+$", "") + "/";
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.startsWith(prefix)) normalized = normalized.substring(prefix.length());
        if (normalized.startsWith("/") || normalized.contains(":")
                || java.util.Arrays.asList(normalized.split("/", -1)).contains("..")) return null;
        return normalized;
    }

    public Coverage coverage(CompileEntryClassification classification,
                             cn.rvport.backend.buildvalidation.bitcode.BitcodeManifest manifest) {
        Set<String> projectPaths = new LinkedHashSet<>();
        classification.projectUnits().forEach(unit -> projectPaths.add(unit.path()));
        int produced = 0;
        int failed = 0;
        Set<String> observed = new LinkedHashSet<>();
        for (var record : manifest.records()) {
            String path = normalize(record.source().path());
            if (!projectPaths.contains(path) || !observed.add(path)) continue;
            if ("produced".equals(record.status())) produced++;
            else failed++;
        }
        int missing = Math.max(0, classification.projectTranslationUnitCount() - produced - failed);
        return new Coverage(classification.projectTranslationUnitCount(), produced, failed, missing);
    }

    public record Coverage(int planned, int produced, int failed, int missing) {
    }

    private boolean isCpp(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        return C_CPP_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private boolean isProbe(String file, String directory) {
        return file.endsWith("cmakecxxcompilerabi.cpp")
                || file.endsWith("cmakeccompilerabi.c")
                || file.contains("/cmakefiles/") && file.contains("/compilerid")
                || directory.contains("/cmakefiles/cmakescratch/trycompile-");
    }

    private String relativeProjectPath(String file, String sourceIdentity) {
        String normalized = normalize(file);
        if (!normalized.startsWith("/") && !normalized.matches("^[A-Za-z]:/.*")) {
            return normalized;
        }
        String marker = "/" + sourceIdentity + "/";
        int index = normalized.indexOf(marker);
        if (index < 0) return null;
        return normalized.substring(index + marker.length());
    }

    private String contextKey(JsonNode entry) {
        JsonNode arguments = entry.get("arguments");
        if (arguments != null && arguments.isArray()) {
            List<String> semantic = new ArrayList<>();
            arguments.forEach(value -> {
                String text = value.asText();
                if (text.startsWith("-std=") || text.startsWith("-m")
                        || text.startsWith("-f") || text.startsWith("-D")
                        || text.startsWith("-I")) semantic.add(text);
            });
            return String.join("\u0000", semantic);
        }
        return entry.path("command").asText("");
    }

    private String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private void increment(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }
}
