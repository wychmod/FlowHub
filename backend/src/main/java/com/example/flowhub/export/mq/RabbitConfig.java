package com.example.flowhub.export.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：导出任务 direct 交换机 + 业务队列（带 DLX 死信参数）+ 死信交换机/队列。
 * <p>
 * 全部 durable；由 Boot 自动装配的 AmqpAdmin 在首次建连时向 Broker 声明，Broker 未启动不影响应用启动。
 */
@Configuration
public class RabbitConfig {

    public static final String JOB_EXCHANGE = "export.job.exchange";
    public static final String JOB_QUEUE = "export.job.queue";
    public static final String JOB_ROUTING_KEY = "export.job.create";
    public static final String DEAD_LETTER_EXCHANGE = "export.job.dlx";
    public static final String DEAD_LETTER_QUEUE = "export.job.dlq";

    @Bean
    public DirectExchange jobExchange() {
        return ExchangeBuilder.directExchange(JOB_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** 业务队列：死信转发默认携带原 routing key，故 DLQ 直接绑同一 key，无需额外 x-dead-letter-routing-key 参数。 */
    @Bean
    public Queue jobQueue() {
        return QueueBuilder.durable(JOB_QUEUE).deadLetterExchange(DEAD_LETTER_EXCHANGE).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding jobBinding() {
        return BindingBuilder.bind(jobQueue()).to(jobExchange()).with(JOB_ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(JOB_ROUTING_KEY);
    }
}
