package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.queue.TaskQueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class TaskDeadLetterRecoveryTests {
    final Channel channel=mock(Channel.class);
    final RabbitTemplate rabbit=mock(RabbitTemplate.class);
    final TaskDeliveryRecoveryService service=mock(TaskDeliveryRecoveryService.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final TaskDeadLetterRecovery recovery=new TaskDeadLetterRecovery(rabbit,new TaskQueueProperties("x","q","r",null,"dead",null,3),service,mapper);
    GetResponse response(byte[] body) { return new GetResponse(new Envelope(7,false,"x","r"),new AMQP.BasicProperties(),body,0); }
    GetResponse message(int attempt) throws Exception { return response(mapper.writeValueAsBytes(new TaskQueueMessage(UUID.randomUUID(),"REPORT_GENERATE",UUID.randomUUID(),null,attempt))); }
    @Test void commitsRecoveryBeforeAcknowledgingAndStopsAtEmptyQueue() throws Exception {
        when(channel.basicGet("dead",false)).thenReturn(message(3),null);when(service.reconcile(any())).thenReturn(true);
        recovery.drain(channel);
        var order=inOrder(service,channel);order.verify(channel).basicGet("dead",false);order.verify(service).reconcile(any());order.verify(channel).basicAck(7,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void retainsDatabaseFailureAndRetriesOnLaterTick() throws Exception {
        when(channel.basicGet("dead",false)).thenReturn(message(3),null);when(service.reconcile(any())).thenThrow(new IllegalStateException("offline"));
        recovery.drain(channel);verify(channel).basicNack(7,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
        verify(channel,times(1)).basicGet("dead",false);
    }
    @Test void quarantinesInvalidOrUnverifiableMessagesOnlyAfterBrokerConfirmation() throws Exception {
        for(GetResponse input:List.of(response("bad-json".getBytes()),response("null".getBytes()),response(new byte[65537]),message(1),message(3))) {
            reset(channel,service);when(channel.basicGet("dead",false)).thenReturn(input,null);
            recovery.drain(channel);
            var order=inOrder(channel);
            order.verify(channel).basicGet("dead",false);order.verify(channel).queueDeclare("dead.invalid",true,false,false,Map.of());
            order.verify(channel).confirmSelect();order.verify(channel).basicPublish(eq(""),eq("dead.invalid"),any(),eq(input.getBody()));
            order.verify(channel).waitForConfirmsOrDie(5000);order.verify(channel).basicAck(7,false);
        }
    }
    @Test void failedQuarantineAndAckLeaveMessagesRecoverable() throws Exception {
        for(Exception failure:List.of(new IOException("io"),new java.util.concurrent.TimeoutException("timeout"),new InterruptedException("interrupted"))) {
            reset(channel);when(channel.basicGet("dead",false)).thenReturn(response(new byte[0]));
            doThrow(failure).when(channel).waitForConfirmsOrDie(5000);
            recovery.drain(channel);verify(channel).basicNack(7,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
            if(failure instanceof InterruptedException) assertThat(Thread.interrupted()).isTrue();
        }
        reset(channel);when(channel.basicGet("dead",false)).thenReturn(message(3));when(service.reconcile(any())).thenReturn(true);
        doThrow(new IOException("ack lost")).when(channel).basicAck(7,false);
        recovery.drain(channel);verify(channel).basicNack(7,false,true);
    }
    @Test void boundsBatchAndHandlesTransportFailureWithoutBusyLoop() throws Exception {
        when(channel.basicGet("dead",false)).thenReturn(message(3));when(service.reconcile(any())).thenReturn(true);
        recovery.drain(channel);verify(channel,times(50)).basicAck(7,false);
        doAnswer(invocation->{ throw new org.springframework.amqp.AmqpConnectException(new IOException("offline")); }).when(rabbit).execute(any());
        recovery.recover();verify(rabbit).execute(any());
        reset(rabbit);doAnswer(invocation->{ org.springframework.amqp.rabbit.core.ChannelCallback<?> callback=invocation.getArgument(0);return callback.doInRabbit(channel); }).when(rabbit).execute(any());
        when(channel.basicGet("dead",false)).thenReturn(null);recovery.recover();verify(rabbit).execute(any());
    }
}
