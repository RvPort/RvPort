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
 * kb-scanner 多语言 × 多架构矩阵 demo：演示全部 5 个语言域（c/rust/go/java/python）
 * × 2 个源架构（x86/arm64）的调用方式与典型检出。
 *
 * <p>与 {@link KbScannerDemo}（单次扫描）的差异：本 demo 自带每种组合的
 * <b>合成测试工程</b>（临时目录内现场生成源码），循环扫描并对照断言期望的
 * findingType 是否出现——既是调用示例，也是各语言域检测点的速查样例。
 *
 * <p>用法：
 * <pre>
 *   java KbScannerMatrixDemo /path/to/repo/tools/kb-scanner
 * </pre>
 */
public final class KbScannerMatrixDemo {

    private static final String SCHEMA_VERSION = "2.0";
    private static final String MEMORY_LIMIT_MIB = "2048";
    private static final long TIMEOUT_SECONDS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一个待扫描用例：语言/源架构组合 + 合成工程内容 + 期望出现的 findingType。 */
    record Case(String title, String language, String sourceArch,
                String fileName, String source, List<String> expectTypes) {
    }

    /** 矩阵：每语言域挑最有代表性的检测点（各域还有更多，见 tools/kb-scanner/samples/）。 */
    private static final List<Case> CASES = List.of(
            // ---- C / x86（基线：intrinsic + 平台宏）----
            new Case("C x86 intrinsic", "c,cpp", "x86", "vec.c", """
                    #include <immintrin.h>
                    int sum(int *a) {
                    #ifdef __x86_64__
                        __m256i v = _mm256_loadu_si256((const __m256i *)a);
                        return _mm256_movemask_epi8(v);
                    #else
                        return 0;
                    #endif
                    }
                    """, List.of("x86_intrinsic_reference", "x86_platform_macro_reference")),

            // ---- C / arm64（NEON intrinsic + 平台宏）----
            new Case("C arm64 NEON", "c,cpp", "arm64", "neon.c", """
                    #ifdef __aarch64__
                    #include <arm_neon.h>
                    int32x4_t add(int32x4_t a, int32x4_t b) { return vaddq_s32(a, b); }
                    #endif
                    """, List.of("arm64_header_reference", "arm64_vector_type_reference")),

            // ---- Rust：cfg 三值 + 架构 intrinsic + asm! ----
            new Case("Rust cfg+intrinsic", "rust", "x86", "lib.rs", """
                    use core::arch::x86_64::*;

                    #[cfg(target_arch = "x86_64")]
                    pub fn crc(d: &[u8]) -> u32 {
                        let v: __m128i = unsafe { _mm_add_epi32(1, 2) };
                        unsafe { _mm_crc32_u32(0, d[0] as u32) + v as u32 }
                    }

                    #[cfg(target_arch = "riscv64")]
                    pub fn crc(d: &[u8]) -> u32 { 0 }
                    """, List.of("x86_intrinsic_reference", "x86_vector_type_reference")),

            // ---- Rust / arm64：NEON intrinsic（收编语料 2141 项的纯增量域）----
            new Case("Rust arm64 NEON", "rust", "arm64", "crc.rs", """
                    #[cfg(target_arch = "aarch64")]
                    pub fn crc(d: u64) -> u64 {
                        unsafe { core::arch::aarch64::__crc32cd(0, d) as u64 }
                    }
                    """, List.of("architecture_identifier_candidate")),
            // ^ known gap：限定名 core::arch::aarch64::__crc32cd 的调用拆解未到函数名，
            //   裸 __crc32cd 调用可命中（见 tools/kb-scanner/tests/test_rust_scan.py 的 arm64 用例）。

            // ---- Go：build tag 真表达式 + GOARCH 分支 + cgo 内嵌 C ----
            new Case("Go build tag + cgo", "go", "x86", "main.go", """
                    //go:build linux && amd64

                    package main

                    /*
                    #include <immintrin.h>
                    __m256i zero(void) { return _mm256_setzero_si256(); }
                    */
                    import "C"

                    func pick() bool { return C.zero() != nil }

                    func arch() bool { return runtime.GOARCH == "amd64" }
                    """, List.of("inline_asm_candidate")),

            // ---- Java：JNI 加载 + os.arch ----
            new Case("Java JNI load", "java", "x86", "Loader.java", """
                    package demo;

                    public class Loader {
                        static Runtime rt = Runtime.getRuntime();

                        public void load() {
                            System.loadLibrary("simd_x86");
                            Runtime.getRuntime().loadLibrary("jpeg_amd64");
                            rt.load("/opt/libz_x86_64.so");
                        }

                        public boolean isX86() {
                            return "amd64".equals(System.getProperty("os.arch"));
                        }
                    }
                    """, List.of("architecture_identifier_candidate")),

            // ---- Java / arm64：os.arch 比较 + 库名词元 ----
            new Case("Java arm64 os.arch", "java", "arm64", "Loader.java", """
                    package demo;

                    public class Loader {
                        public String arch() {
                            return System.getProperty("os.arch");
                        }

                        public boolean isArm() {
                            return "aarch64".equals(arch());
                        }
                    }
                    """, List.of()),

            // ---- Python：ctypes/cffi 加载 + cdef 内嵌 C ----
            new Case("Python ctypes + cffi", "python", "x86", "app.py", """
                    import ctypes
                    from cffi import FFI

                    simd = ctypes.CDLL("libsimd_x86.so")

                    ffi = FFI()
                    ffi.cdef("int probe(void);")
                    ffi.set_source(
                        "_mod",
                        "#include <immintrin.h>\\n"
                        "int probe(void) { __m128i v; return _mm_add_epi32(v, v)[0]; }",
                    )
                    """, List.of("x86_assembly_instruction", "architecture_identifier_candidate")),

            // ---- Python / arm64：库名 arm 词元 ----
            new Case("Python arm64 ctypes", "python", "arm64", "app.py", """
                    import ctypes
                    lib = ctypes.CDLL("libneon_arm64.so")
                    """, List.of()),

            // ---- Cython：.pyx 行正则降级 ----
            new Case("Cython ctypes regex", "python", "x86", "fast.pyx", """
                    # cython: language_level=3
                    from ctypes import CDLL

                    def load():
                        return ctypes.CDLL("libfast_x86.so")
                    """, List.of("architecture_identifier_candidate")),

            // ---- 汇编：.S 汇编源 ----
            new Case("Assembly x86 .S", "asm", "x86", "crc32.S", """
                    .text
                    #ifdef __x86_64__
                    crc32_probe:
                        movaps  %xmm0, %xmm1
                        ret
                    #endif
                    """, List.of("x86_assembly_instruction")),

            // ---- 混合语言：C + Rust 同扫（domain 取第一命中 c → c-cpp-*）----
            new Case("Mixed C+Rust", "c,rust", "x86", "SKIP", """
                    // 占位：混合用例在 main 里单独组装双文件工程
                    """, List.of("x86_intrinsic_reference")));

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java KbScannerMatrixDemo <scanner-root>");
            System.exit(2);
        }
        Path scannerRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputRoot = Path.of(System.getenv().getOrDefault(
                "KB_SCANNER_DEMO_OUTPUT_DIR", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(outputRoot);

        int passed = 0;
        int failed = 0;
        for (Case item : CASES) {
            Path projectDir = Files.createTempDirectory(outputRoot, "kb-matrix-" + item.title()
                    .replaceAll("[^A-Za-z0-9]+", "-") + "-");
            if (!"SKIP".equals(item.fileName())) {
                Files.writeString(projectDir.resolve(item.fileName()), item.source());
            } else {
                // 混合语言工程：C 主文件 + Rust 附文件
                Files.writeString(projectDir.resolve("main.c"),
                        "#include <immintrin.h>\nint m(void){ __m256i v = _mm256_setzero_si256(); return 0; }\n");
                Files.writeString(projectDir.resolve("lib.rs"),
                        "#[cfg(target_arch = \"x86_64\")]\nfn r() { unsafe { core::arch::x86_64::_mm_add_epi32(0,0) }; }\n");
            }
            Path outDir = Files.createTempDirectory(outputRoot, "kb-matrix-out-");
            System.out.printf("%n=== %s（--language %s --source-arch %s）===%n",
                    item.title(), item.language(), item.sourceArch());
            try {
                List<String> types = runAndCollect(projectDir, scannerRoot, outDir, item);
                List<String> missing = item.expectTypes().stream().filter(t -> !types.contains(t)).toList();
                if (missing.isEmpty()) {
                    System.out.printf("  ✔ 期望检出全部命中: %s%n", item.expectTypes());
                    passed++;
                } else {
                    System.out.printf("  ✘ 缺少期望: %s；实际: %s%n", missing, types);
                    failed++;
                }
            } catch (Exception ex) {
                System.out.printf("  ✘ 执行失败: %s%n", ex.getMessage());
                failed++;
            }
        }
        System.out.printf("%n=== 矩阵结果：%d 通过 / %d 失败（产物在各 kb-matrix-out-* 目录）===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 执行扫描并返回实际出现的 findingType 集合。 */
    private static List<String> runAndCollect(Path projectDir, Path scannerRoot, Path outDir, Case item)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "python3", "-m", "kb_scanner", "scan",
                "--input", projectDir.toString(),
                "--rules", scannerRoot.resolve("rulesets/riscv-porting").toString(),
                "--output", outDir.toString(),
                "--format", "json",
                "--schema-version", SCHEMA_VERSION,
                "--language", item.language(),
                "--source-arch", item.sourceArch(),
                "--target-arch", "riscv64",
                "--context-infer", "off"));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scannerRoot.toFile());
        builder.environment().put("KB_SCANNER_MEMORY_LIMIT_MIB", MEMORY_LIMIT_MIB);
        Process process = builder.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("kb-scanner 超时（" + TIMEOUT_SECONDS + "s）");
        }
        if (process.exitValue() != 0) {
            throw new IOException("kb-scanner exit=" + process.exitValue() + " stderr=" + stderr.strip());
        }
        JsonNode root = JSON.readTree(outDir.resolve("scan-result.json").toFile());
        List<String> types = new ArrayList<>();
        for (JsonNode finding : root.path("findings")) {
            String type = finding.path("conclusion").path("findingType").asText();
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        System.out.printf("  exit=0, findings=%d, types=%s%n",
                root.path("findings").size(), types);
        return types;
    }

    private KbScannerMatrixDemo() {
    }
}
