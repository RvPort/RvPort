import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * kb-scanner 最小调用示例（独立 demo，不依赖 RvPort 后端代码）。
 *
 * <p>演示三件事：
 * <ol>
 *   <li>以子进程方式调起 kb-scanner（与生产后端 KbScanRuntimeService 相同的命令合同）；</li>
 *   <li>等待进程结束（超时强杀 + 取消语义的最小版）；</li>
 *   <li>解析 scan-result.json（V2 schema）并输出迁移点摘要。</li>
 * </ol>
 *
 * <p>运行前提：
 * <ul>
 *   <li>Python 3.12+ 环境装有 kb-scanner 运行依赖（仓库容器内即 /opt/kb-scanner-venv，
 *       开发机可用 tools/kb-scanner/requirements/runtime.lock 建的 venv）；</li>
 *   <li>SCANNER_ROOT 指向本仓库的 tools/kb-scanner（模块经 cwd/PYTHONPATH 可导入）；</li>
 *   <li>目标工程目录下有 C/C++ 源码。</li>
 * </ul>
 *
 * <p>用法示例：
 * <pre>
 *   java KbScannerDemo /path/to/project /path/to/repo/tools/kb-scanner
 * </pre>
 */
public final class KbScannerDemo {

    /** 扫描器 schema 合同版本：结果文件 schemaVersion 必须精确等于该值（与后端一致）。 */
    private static final String SCHEMA_VERSION = "2.0";

    /** 进程级内存硬闸门（MiB）：扫描器入口据此 setrlimit，超限转 FATAL 诊断而非卡死。 */
    private static final String MEMORY_LIMIT_MIB = "2048";

    /** 超时（秒）：超时强杀子进程，退出码按 124 报告（与后端语义一致）。 */
    private static final long TIMEOUT_SECONDS = 1800;

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java KbScannerDemo <project-dir> <scanner-root>");
            System.err.println("示例: java KbScannerDemo ~/my-project ~/RvPort/tools/kb-scanner");
            System.exit(2);
        }
        Path projectDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path scannerRoot = Path.of(args[1]).toAbsolutePath().normalize();
        // 产物目录可用环境变量 KB_SCANNER_DEMO_OUTPUT_DIR 指定；缺省取系统临时目录
        //（便于直接查看 scan-result.json / scan-report.html，避免与 /tmp 系统清理策略冲突时可用 env 改到持久目录）。
        Path outputRoot = Path.of(System.getenv().getOrDefault(
                "KB_SCANNER_DEMO_OUTPUT_DIR", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(outputRoot);
        Path outputDir = Files.createTempDirectory(outputRoot, "kb-scan-demo-");

        // 1) 调起扫描器（与生产后端相同的命令合同）
        int exitCode = runScanner(projectDir, scannerRoot, outputDir);

        // 2) 解析结果并输出摘要
        if (exitCode == 0) {
            printSummary(outputDir.resolve("scan-result.json"), projectDir);
        } else {
            System.err.printf("kb-scanner 退出码 %d；诊断日志: %s%n", exitCode, outputDir.resolve("scan.log"));
        }
    }

    /**
     * 以子进程方式执行 kb-scanner，返回退出码（超时强杀返回 124）。
     *
     * <p>命令合同与 {@code KbScanRuntimeService.command()} 一致：模块经 {@code -m kb_scanner}
     * 调起，工作目录为 scannerRoot，schema 固定 2.0。语言/架构参数按需扩展
     * （如 Rust 工程 {@code --language rust}，ARM 源工程 {@code --source-arch arm64}）。
     */
    private static int runScanner(Path projectDir, Path scannerRoot, Path outputDir)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "python3", "-m", "kb_scanner", "scan",
                "--input", projectDir.toString(),
                "--rules", scannerRoot.resolve("rulesets/riscv-porting").toString(),
                "--output", outputDir.toString(),
                "--format", "json,html",
                "--schema-version", SCHEMA_VERSION,
                "--language", "c,cpp",
                "--source-arch", "x86",
                "--target-arch", "riscv64"));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scannerRoot.toFile());
        // 内存闸门环境变量：扫描器入口 setrlimit(RLIMIT_AS)，把 OOM 卡死变为可诊断失败。
        builder.environment().put("KB_SCANNER_MEMORY_LIMIT_MIB", MEMORY_LIMIT_MIB);

        Instant started = Instant.now();
        Process process = builder.start();
        // 消费 stdout/stderr 防止管道缓冲区写满导致阻塞。
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            System.err.println("kb-scanner 超时，已强杀（退出码 124）");
            return 124;
        }
        System.out.printf("扫描完成：exit=%d，耗时 %s%n", process.exitValue(),
                Duration.between(started, Instant.now()));
        if (!stderr.isBlank()) {
            System.err.println("[scanner stderr] " + stderr.strip());
        }
        return process.exitValue();
    }

    /** 解析 V2 scan-result.json：校验 schemaVersion，打印迁移点摘要（严重度降序）。 */
    private static void printSummary(Path resultJson, Path projectDir) throws IOException {
        JsonNode root = JSON.readTree(resultJson.toFile());
        String version = root.path("schemaVersion").asText();
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalStateException("结果版本不兼容: schemaVersion=" + version + "，期望 " + SCHEMA_VERSION);
        }
        JsonNode summary = root.path("summary");
        System.out.printf("%n=== 扫描摘要（%s）===%n", projectDir.getFileName());
        System.out.printf("文件 %s | findings %s | issues %s | hints %s | 阻断 %s | inactiveOrGuarded %s%n",
                summary.path("files").asInt(), summary.path("findings").asInt(),
                summary.path("issues").asInt(), summary.path("hints").asInt(),
                summary.path("blocking").asInt(), summary.path("inactiveOrGuarded").asInt());

        System.out.println("\n=== 迁移点（严重度降序）===");
        for (JsonNode finding : root.path("findings")) {
            JsonNode conclusion = finding.path("conclusion");
            JsonNode fact = finding.path("fact");
            String location = fact.path("primaryLocation").path("file").asText("?")
                    + ":" + fact.path("primaryLocation").path("span").path("start").path("line").asInt();
            System.out.printf("[%s] %-34s %s%n    %s%n",
                    conclusion.path("severity").asText("info"),
                    conclusion.path("findingType").asText("?"),
                    location,
                    conclusion.path("title").asText(""));
        }
        System.out.printf("%n产物目录: %s（scan-result.json / scan-report.html / scan.log）%n",
                resultJson.getParent());
    }

    private KbScannerDemo() {
    }
}
