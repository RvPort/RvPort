package cn.rvport.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/** 单测容器必须实际加载覆盖率代理，不能仅凭 Maven 正常退出认定已采集。 */
class CoverageCollectionTests {
    @Test
    void jacocoAgentAndHeapLimitAreBothAppliedToTheTestJvm() throws Exception {
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertThat(arguments).anyMatch(value -> value.startsWith("-javaagent:") && value.contains("jacoco"));
        assertThat(arguments).contains("-Xmx512m");
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(512L * 1024 * 1024);
        Class<?> runtime = Class.forName("org.jacoco.agent.rt.RT");
        Object agent = runtime.getMethod("getAgent").invoke(null);
        Class<?> api = Class.forName("org.jacoco.agent.rt.IAgent");
        byte[] data = (byte[]) api.getMethod("getExecutionData", boolean.class).invoke(agent, false);
        assertThat(data).hasSizeGreaterThan(5);
    }
}
