package com.evoforge.device

import com.evoforge.core.EvoForgeProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Qualifier

@Configuration
@ConditionalOnProperty(prefix = 'evoforge.deviceAgent', name = 'enabled', havingValue = 'true')
class DeviceAgentMessagingConfig {
    static final String COMMAND_QUEUE_BEAN = 'deviceCommandQueue'
    static final String REQUEST_QUEUE_BEAN = 'deviceRequestQueue'
    static final String EVENT_QUEUE_BEAN = 'deviceEventQueue'
    static final String LISTENER_FACTORY_BEAN = 'deviceAgentListenerContainerFactory'

    private final EvoForgeProperties properties

    DeviceAgentMessagingConfig(EvoForgeProperties properties) {
        this.properties = properties
    }

    @Bean
    MessageConverter deviceAgentMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper)
    }

    @Bean
    DirectExchange deviceCommandExchange() {
        return new DirectExchange(properties.deviceAgent.commandExchange, true, false)
    }

    @Bean
    DirectExchange deviceEventExchange() {
        return new DirectExchange(properties.deviceAgent.eventExchange, true, false)
    }

    @Bean(COMMAND_QUEUE_BEAN)
    Queue deviceCommandQueue() {
        return new Queue(commandQueueName(), true)
    }

    @Bean(REQUEST_QUEUE_BEAN)
    Queue deviceRequestQueue() {
        return new Queue(requestQueueName(), true)
    }

    @Bean(EVENT_QUEUE_BEAN)
    Queue deviceEventQueue() {
        return new Queue(eventQueueName(), true)
    }

    @Bean
    Binding deviceCommandBinding(@Qualifier(COMMAND_QUEUE_BEAN) Queue deviceCommandQueue,
                                 @Qualifier('deviceCommandExchange') DirectExchange deviceCommandExchange) {
        return BindingBuilder
            .bind(deviceCommandQueue)
            .to(deviceCommandExchange)
            .with(commandRoutingKey())
    }

    @Bean
    Binding deviceRequestBinding(@Qualifier(REQUEST_QUEUE_BEAN) Queue deviceRequestQueue,
                                 @Qualifier('deviceCommandExchange') DirectExchange deviceCommandExchange) {
        return BindingBuilder
            .bind(deviceRequestQueue)
            .to(deviceCommandExchange)
            .with(requestRoutingKey())
    }

    @Bean
    Binding deviceEventBinding(@Qualifier(EVENT_QUEUE_BEAN) Queue deviceEventQueue,
                               @Qualifier('deviceEventExchange') DirectExchange deviceEventExchange) {
        return BindingBuilder
            .bind(deviceEventQueue)
            .to(deviceEventExchange)
            .with(eventRoutingKey())
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  @Qualifier('deviceAgentMessageConverter') MessageConverter deviceAgentMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory)
        template.messageConverter = deviceAgentMessageConverter
        return template
    }

    @Bean(LISTENER_FACTORY_BEAN)
    SimpleRabbitListenerContainerFactory deviceAgentListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                             @Qualifier('deviceAgentMessageConverter') MessageConverter deviceAgentMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory()
        factory.connectionFactory = connectionFactory
        factory.messageConverter = deviceAgentMessageConverter
        factory.prefetchCount = Math.max(1, properties.deviceAgent.prefetch)
        factory.concurrentConsumers = Math.max(1, properties.deviceAgent.concurrentConsumers)
        return factory
    }

    private String commandQueueName() {
        return (properties.deviceAgent.commandQueue ?: "evoforge.device.${properties.deviceAgent.deviceId}.commands").toString()
    }

    private String commandRoutingKey() {
        return (properties.deviceAgent.commandRoutingKey ?: "user.${properties.deviceAgent.userId}.device.${properties.deviceAgent.deviceId}.command").toString()
    }

    private String requestQueueName() {
        return (properties.deviceAgent.requestQueue ?: "evoforge.device.${properties.deviceAgent.deviceId}.requests").toString()
    }

    private String requestRoutingKey() {
        return (properties.deviceAgent.requestRoutingKey ?: "user.${properties.deviceAgent.userId}.device.${properties.deviceAgent.deviceId}.request").toString()
    }

    private String eventQueueName() {
        return (properties.deviceAgent.eventQueue ?: "evoforge.device.${properties.deviceAgent.deviceId}.events").toString()
    }

    private String eventRoutingKey() {
        return (properties.deviceAgent.eventRoutingKey ?: "user.${properties.deviceAgent.userId}.device.${properties.deviceAgent.deviceId}.event").toString()
    }
}
