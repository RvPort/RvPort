package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportSandboxProbeTests {
    @Test void startsRealBoundedLocalChild() throws Exception {
        var child=new ReportSandboxProbe().start(new ProcessBuilder("python3","-c","raise SystemExit(0)"));
        assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
    }
    @Test void mapsCapabilityResultsAndCleansTemporaryScript() throws Exception {
        var probe=spy(new ReportSandboxProbe());
        for(int exit:new int[]{0,126,125,1}) {
            doReturn(exit).when(probe).execute(any());
            assertThat(probe.probe()).isEqualTo(exit==0?"AVAILABLE":exit==126?"UNAVAILABLE":"UNKNOWN");
        }
        var path=org.mockito.ArgumentCaptor.forClass(Path.class);verify(probe,times(4)).execute(path.capture());
        for(Path file:path.getAllValues()) assertThat(file).doesNotExist();
        doThrow(new IOException("unavailable")).when(probe).execute(any());assertThat(probe.probe()).isEqualTo("UNKNOWN");
        doThrow(new InterruptedException()).when(probe).execute(any());assertThat(probe.probe()).isEqualTo("UNKNOWN");
        assertThat(Thread.interrupted()).isTrue();
    }
    @Test void subprocessHasNoInheritedCredentialsAndIsKilledOnTimeout() throws Exception {
        for(boolean complete:new boolean[]{true,false}) {
            var child=mock(Process.class);when(child.waitFor(eq(10L),any())).thenReturn(complete);
            when(child.exitValue()).thenReturn(0);when(child.isAlive()).thenReturn(!complete);
            var descendant=mock(ProcessHandle.class);when(child.descendants()).thenReturn(java.util.stream.Stream.of(descendant));
            var probe=spy(new ReportSandboxProbe());doReturn(child).when(probe).start(any());
            assertThat(probe.execute(Path.of("/tmp/probe.py"))).isEqualTo(complete?0:125);
            var builder=org.mockito.ArgumentCaptor.forClass(ProcessBuilder.class);verify(probe).start(builder.capture());
            assertThat(builder.getValue().environment()).containsOnlyKeys("PATH");
            assertThat(builder.getValue().command()).containsExactly("python3","/tmp/probe.py","--self-test");
            if(!complete) { verify(descendant).destroyForcibly();verify(child).destroyForcibly(); }
        }
    }
}
