package cn.rvport.backend.kb.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.kb.KbScanInput;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KbScanHandlerTests {

    @Test
    void exposesKbScanTaskTypeAndDelegatesToRuntime() {
        KbScanRuntimeService runtimeService = Mockito.mock(KbScanRuntimeService.class);
        TaskExecutionContext context = Mockito.mock(TaskExecutionContext.class);
        KbScanInput input = new KbScanInput(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "rules", "c,cpp", "x86", "riscv64", Map.of("mode", "ALL"), List.of(".git"));
        when(context.input(KbScanInput.class)).thenReturn(input);
        KbScanHandler handler = new KbScanHandler(runtimeService);

        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.KB_SCAN);
        assertThat(handler.handle(context).message()).isEqualTo("KB 扫描完成");
        verify(runtimeService).scan(input, context);
    }

    @Test
    void propagatesScanFailure() {
        KbScanRuntimeService runtimeService = Mockito.mock(KbScanRuntimeService.class);
        TaskExecutionContext context = Mockito.mock(TaskExecutionContext.class);
        KbScanInput input = new KbScanInput(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "rules", "c", "arm64", "riscv64", Map.of(), List.of());
        when(context.input(KbScanInput.class)).thenReturn(input);
        RuntimeException failure = new RuntimeException("scan failed");
        org.mockito.Mockito.doThrow(failure).when(runtimeService).scan(input, context);

        assertThatThrownBy(() -> new KbScanHandler(runtimeService).handle(context)).isSameAs(failure);
    }
}
