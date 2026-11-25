package cn.rvport.backend.worker;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 无凭据自检使用与报告执行相同的 helper，不运行 Agent 或调用模型。 */
@Component
public class ReportSandboxProbe {
    public String probe() {
        Path script=null;
        try {
            script=Files.createTempFile("rvport-sandbox-probe-",".py");
            try(var input=new ClassPathResource("agentruntime/report_review_sandbox.py").getInputStream()) {
                Files.copy(input,script,StandardCopyOption.REPLACE_EXISTING);
            }
            int exit=execute(script);
            return exit==0?"AVAILABLE":exit==126?"UNAVAILABLE":"UNKNOWN";
        } catch(InterruptedException ex) { Thread.currentThread().interrupt();return "UNKNOWN"; }
        catch(IOException ex) { return "UNKNOWN"; }
        finally { if(script!=null) try { Files.deleteIfExists(script); } catch(IOException ignored) { /* 下一次探测仍独立执行。 */ } }
    }
    int execute(Path script) throws IOException,InterruptedException {
        ProcessBuilder builder=new ProcessBuilder("python3",script.toString(),"--self-test");
        builder.environment().clear();builder.environment().put("PATH","/usr/local/bin:/usr/bin:/bin");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process child=start(builder);
        try { return child.waitFor(10,TimeUnit.SECONDS)?child.exitValue():125; }
        finally {
            if(child.isAlive()) { child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly(); }
        }
    }
    Process start(ProcessBuilder builder) throws IOException { return builder.start(); }
}
