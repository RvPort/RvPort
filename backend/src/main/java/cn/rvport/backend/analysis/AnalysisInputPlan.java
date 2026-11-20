package cn.rvport.backend.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 固定分析输入身份。可选构建属于输入事实，不能代替源码或伪装为已执行验证。 */
public record AnalysisInputPlan(
        int planVersion, UUID projectId, UUID sourceRevisionId, String sourceDigest,
        String analyzer, String toolVersion, String profileVersion, Identity identity,
        InputOrigin inputOrigin, BuildBinding build, List<Artifact> artifacts,
        List<String> assumptions, List<String> missingEvidence, String requestedInputDigest) {
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public enum InputOrigin { SOURCE_DISCOVERY, BUILD_ARTIFACTS }

    public AnalysisInputPlan {
        require(planVersion == VERSION && projectId != null && sourceRevisionId != null && identity != null,
                "ANALYSIS_PLAN_INVALID");
        require(sourceDigest != null && (sourceDigest.matches("[0-9a-f]{64}")
                || sourceDigest.matches("git:[0-9a-f]{40}|git:[0-9a-f]{64}")), "ANALYSIS_SOURCE_DIGEST_INVALID");
        text(analyzer); text(toolVersion); text(profileVersion);
        require(inputOrigin != null, "ANALYSIS_PLAN_INVALID");
        require(artifacts != null && assumptions != null && missingEvidence != null, "ANALYSIS_PLAN_INVALID");
        artifacts = List.copyOf(artifacts);
        assumptions = List.copyOf(assumptions);
        missingEvidence = List.copyOf(missingEvidence);
        require(inputOrigin == InputOrigin.SOURCE_DISCOVERY ? build == null && artifacts.isEmpty()
                        : build != null && !artifacts.isEmpty(), "ANALYSIS_PLAN_ORIGIN_MISMATCH");
        if (build != null) {
            require(Objects.equals(build.projectId(), projectId) && Objects.equals(build.sourceRevisionId(), sourceRevisionId),
                    "ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
            require(artifacts.stream().allMatch(a -> a.ownerId().equals(build.id())), "ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
            require(!identity.architectureAssumed() && identity.effectiveSourceArch().equals(build.sourceArch()),
                    "ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        }
        if (requestedInputDigest != null) hashValue(requestedInputDigest);
    }

    public record BuildBinding(UUID id, UUID projectId, UUID sourceRevisionId, UUID environmentId,
            long environmentVersion, String sourceArch, String producer, String target, String abi) {
        public BuildBinding {
            require(id != null && projectId != null && sourceRevisionId != null && environmentId != null
                    && environmentVersion >= 0, "ANALYSIS_BUILD_IDENTITY_INVALID");
            text(sourceArch); text(producer); text(target); text(abi);
        }
    }

    public record Artifact(UUID id, UUID ownerId, String type, String sha256) {
        public Artifact { require(id != null && ownerId != null, "ANALYSIS_ARTIFACT_IDENTITY_MISMATCH"); text(type); hashValue(sha256); }
    }

    public record Identity(String requestedLanguage, List<String> effectiveLanguages,
            String requestedSourceArch, String effectiveSourceArch, boolean architectureAssumed,
            String targetArch, Map<String, Object> scanScope, List<String> excludePaths, String ruleVersion) {
        private static final List<String> LANGUAGES = List.of("c", "cpp", "rust", "go", "java", "python", "asm");
        public Identity {
            require(effectiveLanguages != null && !effectiveLanguages.isEmpty()
                    && LANGUAGES.containsAll(effectiveLanguages), "ANALYSIS_LANGUAGE_UNSUPPORTED");
            effectiveLanguages = LANGUAGES.stream().filter(effectiveLanguages::contains).toList();
            require(effectiveSourceArch != null && List.of("x86", "arm64").contains(effectiveSourceArch), "ANALYSIS_ARCHITECTURE_INVALID");
            text(targetArch); text(ruleVersion);
            require(scanScope != null && java.util.Set.of("mode", "paths").containsAll(scanScope.keySet()), "ANALYSIS_SCOPE_INVALID");
            Object modeValue = scanScope.getOrDefault("mode", "ALL");
            require(modeValue instanceof String, "ANALYSIS_SCOPE_INVALID");
            String mode = ((String) modeValue).trim().toUpperCase(Locale.ROOT);
            require(mode.equals("ALL") || mode.equals("PATHS"), "ANALYSIS_SCOPE_INVALID");
            Object pathsValue = scanScope.getOrDefault("paths", List.of());
            require(pathsValue instanceof List<?>, "ANALYSIS_SCOPE_INVALID");
            List<String> paths = ((List<?>) pathsValue).stream().map(value -> {
                require(value instanceof String, "ANALYSIS_SCOPE_INVALID");
                return relativePath((String) value);
            }).distinct().sorted().toList();
            require(mode.equals("ALL") || !paths.isEmpty(), "ANALYSIS_SCOPE_INVALID");
            scanScope = Map.of("mode", mode, "paths", paths);
            require(excludePaths != null, "ANALYSIS_PATH_INVALID");
            excludePaths = excludePaths.stream().map(Identity::relativePath).distinct().sorted().toList();
            require(requestedLanguage == null || requestedLanguage.length() <= 200, "ANALYSIS_INPUT_TOO_LARGE");
            require(requestedSourceArch == null || requestedSourceArch.length() <= 100, "ANALYSIS_INPUT_TOO_LARGE");
        }
        public static Identity of(String language, String sourceArch, String targetArch,
                Map<String, Object> scope, List<String> excludes, String ruleVersion) {
            String selected = language == null ? "c,cpp" : language;
            List<String> parsed = java.util.Arrays.stream(selected.split(",", -1))
                    .map(v -> v.trim().toLowerCase(Locale.ROOT)).toList();
            String rawArch = sourceArch == null ? "x86" : sourceArch.trim().toLowerCase(Locale.ROOT);
            boolean x86 = List.of("x86", "x86_64", "amd64", "i386", "i686", "x64").contains(rawArch);
            boolean arm = List.of("arm64", "aarch64", "arm").contains(rawArch);
            return new Identity(language, parsed, sourceArch, arm ? "arm64" : "x86", !x86 && !arm,
                    targetArch, scope, excludes, ruleVersion);
        }
        private static String relativePath(String value) {
            text(value);
            String path = value.replace('\\', '/');
            require(!path.startsWith("/") && !path.contains(":")
                    && !path.chars().anyMatch(Character::isISOControl)
                    && java.util.Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isBlank() || p.equals(".") || p.equals("..")),
                    "ANALYSIS_PATH_INVALID");
            return path;
        }
        Map<String, Object> semantic() {
            return Map.of("normalizationVersion", VERSION, "effectiveLanguages", effectiveLanguages,
                    "effectiveSourceArch", effectiveSourceArch, "architectureAssumed", architectureAssumed,
                    "targetArch", targetArch, "scanScope", scanScope, "excludePaths", excludePaths, "ruleVersion", ruleVersion);
        }
    }

    public String effectiveInputDigest() { return hash(canonical(semantic())); }
    public String originalInputDigest() { return requestedInputDigest == null ? effectiveInputDigest() : requestedInputDigest; }

    public AnalysisInputPlan sourceFallback(String reasonCode) {
        text(reasonCode);
        List<String> gaps = new ArrayList<>(missingEvidence);
        if (!gaps.contains(reasonCode)) gaps.add(reasonCode);
        List<String> defaults = new ArrayList<>(assumptions);
        for (String code : "KB".equals(analyzer) ? List.of("COMPILE_DATABASE_UNAVAILABLE", "BUILD_SELECTION_UNVERIFIED")
                : List.of("COMPILE_DATABASE_UNAVAILABLE", "BITCODE_UNAVAILABLE"))
            if (!gaps.contains(code)) gaps.add(code);
        if (!"KB".equals(analyzer)) for (String assumption : List.of("DEFAULT_ASSUMPTION", "C17_CPP17", "OPEN_SCOPE"))
            if (!defaults.contains(assumption)) defaults.add(assumption);
        return new AnalysisInputPlan(planVersion, projectId, sourceRevisionId, sourceDigest, analyzer, toolVersion,
                profileVersion, identity, InputOrigin.SOURCE_DISCOVERY, null, List.of(), defaults, gaps, originalInputDigest());
    }

    public AnalysisInputPlan withoutBitcode(String reasonCode) {
        text(reasonCode);
        List<String> gaps = new ArrayList<>(missingEvidence);
        if (!gaps.contains(reasonCode)) gaps.add(reasonCode);
        return new AnalysisInputPlan(planVersion, projectId, sourceRevisionId, sourceDigest, analyzer, toolVersion,
                profileVersion, identity, inputOrigin, build, artifacts.stream()
                    .filter(a -> "COMPILE_COMMANDS_JSON".equals(a.type())).toList(), assumptions, gaps, originalInputDigest());
    }

    /** 对外不返回工作目录、制品下载地址、命令、凭据或原始请求文本。 */
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>(semantic());
        Map<String,Object> inputIdentity = new LinkedHashMap<>(identity.semantic());
        inputIdentity.put("requestedLanguage", identity.requestedLanguage());
        inputIdentity.put("requestedSourceArch", identity.requestedSourceArch());
        result.put("identity", java.util.Collections.unmodifiableMap(inputIdentity));
        result.put("requestedInputDigest", originalInputDigest());
        result.put("effectiveInputDigest", effectiveInputDigest());
        return java.util.Collections.unmodifiableMap(result);
    }

    private Map<String, Object> semantic() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planVersion", planVersion); values.put("projectId", projectId); values.put("sourceRevisionId", sourceRevisionId);
        values.put("sourceDigest", sourceDigest); values.put("analyzer", analyzer); values.put("toolVersion", toolVersion);
        values.put("profileVersion", profileVersion); values.put("identity", identity.semantic()); values.put("inputOrigin", inputOrigin);
        values.put("build", build); values.put("artifacts", artifacts.stream().sorted(java.util.Comparator.comparing(a -> a.id().toString())).toList());
        values.put("assumptions", assumptions.stream().distinct().sorted().toList());
        values.put("missingEvidence", missingEvidence.stream().distinct().sorted().toList());
        return values;
    }

    private static byte[] canonical(Object value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (java.io.IOException ex) { throw new IllegalArgumentException("ANALYSIS_PLAN_INVALID", ex); }
    }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void hashValue(String value) { require(value != null && value.matches("[0-9a-f]{64}"), "ANALYSIS_HASH_INVALID"); }
    private static void text(String value) { require(value != null && !value.isBlank(), "ANALYSIS_PLAN_INVALID"); }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalArgumentException(code); }
}
