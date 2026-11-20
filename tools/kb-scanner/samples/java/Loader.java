package demo;

/**
 * Sample: JNI native-library loads across receiver forms, plus an os.arch check.
 */
public class Loader {
    private static final Runtime RUNTIME = Runtime.getRuntime();

    public void loadStandard() {
        System.loadLibrary("simd_x86");
        System.load("/opt/libcrypto_x86_64.so");
    }

    public void loadChained() {
        Runtime.getRuntime().loadLibrary("jpeg_amd64");
    }

    public void loadViaVariable() {
        RUNTIME.load("libz_amd64.so");
    }

    public void notALoad() {
        config.load("settings.yaml");
    }

    public boolean isX86() {
        return "amd64".equals(System.getProperty("os.arch"));
    }

    public boolean isRiscv() {
        return "riscv64".equals(System.getProperty("os.arch"));
    }
}
