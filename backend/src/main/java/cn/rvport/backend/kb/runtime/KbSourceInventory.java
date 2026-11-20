package cn.rvport.backend.kb.runtime;

import cn.rvport.backend.analysis.AnalysisInputPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只描述和约束交给 Scanner 的源码范围，不实现扫描或问题判定。 */
final class KbSourceInventory {
    private KbSourceInventory() { }

    record Inventory(List<Map<String, Object>> files, List<String> excludedPaths) {
        Map<String, Object> coverage() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, Integer> languages = new LinkedHashMap<>();
            for (var file : files) {
                counts.merge(file.get("status").toString(), 1, Integer::sum);
                if ("PLANNED".equals(file.get("status")))
                    for (String language : castLanguages(file)) languages.merge(language, 1, Integer::sum);
            }
            return Map.of("denominatorKnown", false, "knownSourceFileCount", files.size(),
                    "manifestFileCounts", counts, "plannedFilesByLanguage", languages, "files", files,
                    "byLanguage", cn.rvport.backend.analysis.AnalysisFileCoverage.byLanguage(files));
        }
        @SuppressWarnings("unchecked")
        private List<String> castLanguages(Map<String, Object> file) { return (List<String>) file.get("selectedLanguages"); }
    }

    static Inventory inspect(Path root, AnalysisInputPlan.Identity identity, int limit) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root) || limit < 1)
            throw new IOException("ANALYSIS_SOURCE_INPUT_INVALID");
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> excluded = new ArrayList<>(identity.excludePaths());
        @SuppressWarnings("unchecked") var selected = (List<String>) identity.scanScope().get("paths");
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            int count = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(root)) continue;
                if (++count > limit) throw new IOException("ANALYSIS_SOURCE_MANIFEST_LIMIT_EXCEEDED");
                if (Files.isSymbolicLink(path)) throw new IOException("ANALYSIS_SOURCE_INPUT_INVALID");
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.contains(":") || relative.chars().anyMatch(Character::isISOControl))
                    throw new IOException("ANALYSIS_SOURCE_INPUT_INVALID");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ANALYSIS_SOURCE_INPUT_INVALID");
                List<String> languages = languages(path.getFileName().toString());
                String status = "PLANNED";
                if (matches(relative, identity.excludePaths())) status = "EXCLUDED";
                else if ("PATHS".equals(identity.scanScope().get("mode")) && !matches(relative, selected)) {
                    status = "NOT_SELECTED";
                    excluded.add(relative);
                } else if (languages.isEmpty()) status = "UNSUPPORTED";
                else if (languages.stream().noneMatch(identity.effectiveLanguages()::contains)) status = "NOT_SELECTED";
                files.add(Map.of("path", relative, "languages", languages,
                        "selectedLanguages", languages.stream().filter(identity.effectiveLanguages()::contains).toList(), "status", status));
            }
        }
        files.sort(java.util.Comparator.comparing(value -> value.get("path").toString()));
        return new Inventory(List.copyOf(files), excluded.stream().distinct().sorted().toList());
    }

    private static boolean matches(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static List<String> languages(String name) {
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return switch (suffix) {
            case ".c" -> List.of("c");
            case ".cc", ".cpp", ".cxx" -> List.of("cpp");
            case ".h", ".hh", ".hpp", ".hxx", ".inc" -> List.of("c", "cpp");
            case ".rs" -> List.of("rust");
            case ".go" -> List.of("go");
            case ".java" -> List.of("java");
            case ".py", ".pyx" -> List.of("python");
            case ".s", ".asm" -> List.of("asm");
            default -> List.of();
        };
    }
}
