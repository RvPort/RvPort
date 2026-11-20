package cn.rvport.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "rvport")
public record RvPortProperties(
        Runtime runtime,
        KbScan kbScan,
        Worker worker,
        Bitcode bitcode,
        SpecializedAnalysis specializedAnalysis
) {
    public RvPortProperties(Runtime runtime, KbScan kbScan, Worker worker) {
        this(runtime, kbScan, worker, null, null);
    }

    public RvPortProperties(Runtime runtime, KbScan kbScan, Worker worker, Bitcode bitcode) {
        this(runtime, kbScan, worker, bitcode, null);
    }

    @ConstructorBinding
    public RvPortProperties {
        worker = worker == null ? new Worker(false) : worker;
        bitcode = bitcode == null
                ? new Bitcode(null, null, null, null, null, null, null, null, null, null, null, null)
                : bitcode;
        specializedAnalysis = specializedAnalysis == null ? new SpecializedAnalysis(null, null, null, null,
                null, null, null, null, null, null, null, null, null, null) : specializedAnalysis;
    }

    public record SpecializedAnalysis(
            Boolean enabled,
            String workDir,
            Integer timeoutSeconds,
            Long maxOutputBytes,
            Long floatingFrontendOutputBytes,
            Long maxExtractedBytes,
            Integer maxFiles,
            String floatingPython,
            String floatingScanner,
            String floatingClang,
            String floatingIrWorker,
            String memoryOrderPython,
            String memoryOrderIrWorker,
            String memoryOrderSourceWorker
    ) {
        public SpecializedAnalysis {
            enabled = enabled == null || enabled;
            workDir = Bitcode.defaultText(workDir, "./backend/runtime/specialized-analysis");
            timeoutSeconds = Bitcode.positive(timeoutSeconds, 3_600);
            maxOutputBytes = Bitcode.positive(maxOutputBytes, 512L * 1024L * 1024L);
            floatingFrontendOutputBytes = Math.min(
                    Bitcode.positive(floatingFrontendOutputBytes, 512L * 1024L * 1024L),
                    1024L * 1024L * 1024L
            );
            maxExtractedBytes = Bitcode.positive(maxExtractedBytes, 8L * 1024L * 1024L * 1024L);
            maxFiles = Bitcode.positive(maxFiles, 25_000);
            floatingPython = Bitcode.defaultText(floatingPython, "/opt/specialized-analysis-venv/bin/python");
            floatingScanner = Bitcode.defaultText(floatingScanner, "/opt/specialized-analysis-venv/bin/fp-precision-scanner");
            floatingClang = Bitcode.defaultText(floatingClang, "/usr/bin/clang-21");
            floatingIrWorker = Bitcode.defaultText(floatingIrWorker, "/opt/rvport/analysis-tools/bin/fp-ir-worker");
            memoryOrderPython = Bitcode.defaultText(memoryOrderPython, "/opt/specialized-analysis-venv/bin/python");
            memoryOrderIrWorker = Bitcode.defaultText(memoryOrderIrWorker, "/opt/rvport/analysis-tools/bin/moa-worker");
            memoryOrderSourceWorker = Bitcode.defaultText(memoryOrderSourceWorker, "/opt/rvport/analysis-tools/bin/memory-order-source-worker");
        }
    }

    public record Runtime(
            String logDir,
            String archiveDir,
            String uploadDir,
            String sourceDir
    ) {
    }

    public record KbScan(
            String pythonCommand,
            String scannerRoot,
            String rulesetPath,
            String outputDir,
            Integer timeoutSeconds,
            Long maxOutputBytes,
            String language,
            String sourceArch,
            String targetArch,
            Integer contextLines,
            Integer memoryLimitMib
    ) {
        public KbScan {
            sourceArch = Bitcode.defaultText(sourceArch, "x86");
            // kb-scanner 进程级内存硬闸门（KB_SCANNER_MEMORY_LIMIT_MIB → RLIMIT_AS）。
            // 缺省 2048：超限分配在扫描器内转 FATAL 诊断（pipeline.memory-budget-exceeded）
            // 而非拖垮 worker 容器；0 表示不设限（仅调试用）。
            memoryLimitMib = memoryLimitMib == null ? 2048 : Math.max(0, memoryLimitMib);
        }
    }

    public record Bitcode(
            Boolean enabled,
            String toolchainId,
            String toolchainVersion,
            String bundlePath,
            String bundleSha256,
            String remoteCacheDirectory,
            Integer maxTranslationUnits,
            Long maxTranslationUnitBytes,
            Long maxBundleBytes,
            Integer translationUnitTimeoutSeconds,
            Integer timeoutSeconds,
            Integer transferTimeoutSeconds
    ) {
        public Bitcode {
            enabled = enabled == null || enabled;
            toolchainId = defaultText(toolchainId, "llvm-21.1.8-linux-x64");
            toolchainVersion = defaultText(toolchainVersion, "21.1.8");
            bundlePath = defaultText(bundlePath, "/opt/rvport/toolchains/LLVM-21.1.8-Linux-X64.tar.xz");
            bundleSha256 = defaultText(bundleSha256, "b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489");
            remoteCacheDirectory = defaultText(remoteCacheDirectory, ".rvport/toolchains");
            maxTranslationUnits = positive(maxTranslationUnits, 5_000);
            maxTranslationUnitBytes = positive(maxTranslationUnitBytes, 256L * 1024L * 1024L);
            maxBundleBytes = positive(maxBundleBytes, 8L * 1024L * 1024L * 1024L);
            translationUnitTimeoutSeconds = positive(translationUnitTimeoutSeconds, 120);
            timeoutSeconds = positive(timeoutSeconds, 1_800);
            transferTimeoutSeconds = positive(transferTimeoutSeconds, 1_800);
            if (!bundleSha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("rvport.bitcode.bundle-sha256 必须是 64 位十六进制 SHA-256");
            }
            if (remoteCacheDirectory.startsWith("/") || remoteCacheDirectory.contains("..")) {
                throw new IllegalArgumentException("rvport.bitcode.remote-cache-directory 必须是安全相对路径");
            }
        }

        static String defaultText(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        static int positive(Integer value, int fallback) {
            return value == null || value <= 0 ? fallback : value;
        }

        static long positive(Long value, long fallback) {
            return value == null || value <= 0 ? fallback : value;
        }
    }

    public record Worker(
            boolean enabled,
            String instanceId,
            RvPortAppMode mode,
            int concurrencyLimit,
            int maxConcurrencyLimit,
            int heartbeatIntervalSeconds,
            int offlineTimeoutSeconds
    ) {
        public Worker(boolean enabled) {
            this(enabled, null, RvPortAppMode.COMBINED, 1, 8, 10, 60);
        }

        public Worker(boolean enabled, String instanceId) {
            this(enabled, instanceId, RvPortAppMode.COMBINED, 1, 8, 10, 60);
        }

        public Worker {
            mode = mode == null ? RvPortAppMode.COMBINED : mode;
            concurrencyLimit = Math.max(0, concurrencyLimit);
            maxConcurrencyLimit = maxConcurrencyLimit <= 0 ? 8 : maxConcurrencyLimit;
            if (concurrencyLimit > maxConcurrencyLimit) {
                concurrencyLimit = maxConcurrencyLimit;
            }
            heartbeatIntervalSeconds = heartbeatIntervalSeconds <= 0 ? 10 : heartbeatIntervalSeconds;
            offlineTimeoutSeconds = offlineTimeoutSeconds <= 0 ? 60 : offlineTimeoutSeconds;
        }
    }
}
