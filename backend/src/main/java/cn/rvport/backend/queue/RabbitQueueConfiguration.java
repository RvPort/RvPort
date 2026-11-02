package cn.rvport.backend.queue;

import cn.rvport.backend.config.TaskQueueProperties;
import cn.rvport.backend.config.TaskCancelQueueProperties;
import cn.rvport.backend.worker.WorkerInstanceIdProvider;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitQueueConfiguration {

    @Bean
    Queue codexProbeQueue() {
        return QueueBuilder.durable(cn.rvport.backend.modelprofile.CodexProbeDispatcher.QUEUE).quorum().build();
    }

    @Bean
    DirectExchange taskExchange(TaskQueueProperties properties) {
        return ExchangeBuilder.directExchange(properties.exchange())
                .durable(true)
                .build();
    }

    @Bean
    DirectExchange taskDeadLetterExchange(TaskQueueProperties properties) {
        return ExchangeBuilder.directExchange(properties.deadLetterExchange())
                .durable(true)
                .build();
    }

    @Bean
    Queue taskQueue(TaskQueueProperties properties) {
        return QueueBuilder.durable(properties.queue())
                .quorum()
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterRoutingKey())
                .build();
    }

    @Bean
    Queue taskDeadLetterQueue(TaskQueueProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue())
                .quorum()
                .build();
    }

    @Bean
    DirectExchange taskCancelExchange(TaskCancelQueueProperties properties) {
        return ExchangeBuilder.directExchange(properties.exchange())
                .durable(true)
                .build();
    }

    @Bean
    DirectExchange taskCancelDeadLetterExchange(TaskCancelQueueProperties properties) {
        return ExchangeBuilder.directExchange(properties.deadLetterExchange())
                .durable(true)
                .build();
    }

    @Bean
    Queue workerCancelQueue(TaskCancelQueueProperties properties, WorkerInstanceIdProvider workerInstanceIdProvider) {
        return QueueBuilder.nonDurable(properties.queueName(workerInstanceIdProvider.workerInstanceId()))
                .exclusive()
                .autoDelete()
                .expires(300_000)
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterRoutingKey())
                .build();
    }

    @Bean
    Queue taskCancelDeadLetterQueue(TaskCancelQueueProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue())
                .quorum()
                .build();
    }

    @Bean
    Binding taskQueueBinding(Queue taskQueue, DirectExchange taskExchange, TaskQueueProperties properties) {
        return BindingBuilder.bind(taskQueue)
                .to(taskExchange)
                .with(properties.routingKey());
    }

    @Bean
    Binding taskDeadLetterQueueBinding(Queue taskDeadLetterQueue, DirectExchange taskDeadLetterExchange, TaskQueueProperties properties) {
        return BindingBuilder.bind(taskDeadLetterQueue)
                .to(taskDeadLetterExchange)
                .with(properties.deadLetterRoutingKey());
    }

    @Bean
    Binding workerCancelQueueBinding(
            Queue workerCancelQueue,
            DirectExchange taskCancelExchange,
            TaskCancelQueueProperties properties,
            WorkerInstanceIdProvider workerInstanceIdProvider
    ) {
        return BindingBuilder.bind(workerCancelQueue)
                .to(taskCancelExchange)
                .with(properties.routingKey(workerInstanceIdProvider.workerInstanceId()));
    }

    @Bean
    Binding taskCancelDeadLetterQueueBinding(
            Queue taskCancelDeadLetterQueue,
            DirectExchange taskCancelDeadLetterExchange,
            TaskCancelQueueProperties properties
    ) {
        return BindingBuilder.bind(taskCancelDeadLetterQueue)
                .to(taskCancelDeadLetterExchange)
                .with(properties.deadLetterRoutingKey());
    }

    @Bean
    MessageConverter rabbitJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter rabbitJsonMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(rabbitJsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}
