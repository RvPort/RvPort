package cn.rvport.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.config.TaskCancelQueueProperties;
import cn.rvport.backend.config.WorkerProperties;
import cn.rvport.backend.worker.WorkerInstanceIdProvider;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import static org.mockito.Mockito.mock;

class RabbitQueueConfigurationTests {

    private final RabbitQueueConfiguration configuration = new RabbitQueueConfiguration();
    private final TaskQueueProperties properties = new TaskQueueProperties(
            "rvport.tasks",
            "rvport.tasks.default",
            "task.default",
            "rvport.tasks.dlx",
            "rvport.tasks.default.dlq",
            "task.default.dead",
            3);
    private final TaskCancelQueueProperties cancelProperties = new TaskCancelQueueProperties(
            "rvport.tasks.cancel",
            "rvport.tasks.cancel",
            "worker.cancel",
            "rvport.tasks.cancel.dlx",
            "rvport.tasks.cancel.dlq",
            "worker.cancel.dead",
            3,
            5);

    @Test
    void declaresDurableQuorumQueue() {
        Queue probe = configuration.codexProbeQueue();
        assertThat(probe.getName()).isEqualTo("rvport.codex.profile-probe");
        assertThat(probe.isDurable()).isTrue();
        assertThat(probe.getArguments()).containsEntry("x-queue-type", "quorum");
        Queue queue = configuration.taskQueue(properties);

        assertThat(queue.getName()).isEqualTo("rvport.tasks.default");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "rvport.tasks.dlx");
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-routing-key", "task.default.dead");
    }

    @Test
    void bindsQueueToConfiguredExchangeAndRoutingKey() {
        Queue queue = configuration.taskQueue(properties);
        DirectExchange exchange = configuration.taskExchange(properties);
        Binding binding = configuration.taskQueueBinding(queue, exchange, properties);

        assertThat(exchange.getName()).isEqualTo("rvport.tasks");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(binding.getExchange()).isEqualTo("rvport.tasks");
        assertThat(binding.getRoutingKey()).isEqualTo("task.default");
    }

    @Test
    void declaresAndBindsDeadLetterQueue() {
        Queue queue = configuration.taskDeadLetterQueue(properties);
        DirectExchange exchange = configuration.taskDeadLetterExchange(properties);
        Binding binding = configuration.taskDeadLetterQueueBinding(queue, exchange, properties);

        assertThat(queue.getName()).isEqualTo("rvport.tasks.default.dlq");
        assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(exchange.getName()).isEqualTo("rvport.tasks.dlx");
        assertThat(binding.getExchange()).isEqualTo("rvport.tasks.dlx");
        assertThat(binding.getRoutingKey()).isEqualTo("task.default.dead");
    }

    @Test
    void declaresWorkerSpecificCancelQueueAndDlq() {
        WorkerInstanceIdProvider worker = new WorkerInstanceIdProvider(
                new WorkerProperties(true, "worker-1", null, 1, 8, 10, 60));
        Queue queue = configuration.workerCancelQueue(cancelProperties, worker);
        DirectExchange exchange = configuration.taskCancelExchange(cancelProperties);
        Binding binding = configuration.workerCancelQueueBinding(queue, exchange, cancelProperties, worker);
        Queue dlq = configuration.taskCancelDeadLetterQueue(cancelProperties);

        assertThat(queue.getName()).isEqualTo("rvport.tasks.cancel.worker-1");
        assertThat(queue.isExclusive()).isTrue();
        assertThat(queue.isAutoDelete()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "rvport.tasks.cancel.dlx");
        assertThat(binding.getExchange()).isEqualTo("rvport.tasks.cancel");
        assertThat(binding.getRoutingKey()).isEqualTo("worker.cancel.worker-1");
        assertThat(dlq.getName()).isEqualTo("rvport.tasks.cancel.dlq");
        assertThat(dlq.getArguments()).containsEntry("x-queue-type", "quorum");
    }

    @Test
    void declaresCancelDeadLetterExchangeBindingAndJsonConverter() {
        Queue queue = configuration.taskCancelDeadLetterQueue(cancelProperties);
        DirectExchange exchange = configuration.taskCancelDeadLetterExchange(cancelProperties);
        Binding binding = configuration.taskCancelDeadLetterQueueBinding(queue, exchange, cancelProperties);
        MessageConverter converter = configuration.rabbitJsonMessageConverter();

        assertThat(exchange.getName()).isEqualTo("rvport.tasks.cancel.dlx");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(binding.getDestination()).isEqualTo("rvport.tasks.cancel.dlq");
        assertThat(binding.getRoutingKey()).isEqualTo("worker.cancel.dead");
        assertThat(converter).isInstanceOf(org.springframework.amqp.support.converter.JacksonJsonMessageConverter.class);
    }

    @Test
    void configuresManualAcknowledgementAndSingleMessagePrefetch() {
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        MessageConverter converter = configuration.rabbitJsonMessageConverter();

        SimpleRabbitListenerContainerFactory factory = configuration.rabbitListenerContainerFactory(
                configurer, connectionFactory, converter);
        factory.setConnectionFactory(connectionFactory);
        org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer container =
                factory.createListenerContainer();

        assertThat(container.getAcknowledgeMode())
                .isEqualTo(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(container, "prefetchCount"))
                .isEqualTo(1);
    }
}
