package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.modelprofile.CodexProbeDispatcher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class CodexProbeWorkerListenerTests {
    @Test void onlyValidIdsAreDispatchedAndPoisonMessagesAreNotRequeued() {
        var dispatcher=mock(CodexProbeDispatcher.class);
        var listener=new CodexProbeWorkerListener(dispatcher);
        var id=UUID.randomUUID();
        listener.onMessage(id.toString());
        verify(dispatcher).process(id);
        assertThatThrownBy(() -> listener.onMessage("not-a-job")).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoMoreInteractions(dispatcher);
    }
}
