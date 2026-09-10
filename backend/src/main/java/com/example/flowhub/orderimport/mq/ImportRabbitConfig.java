package com.example.flowhub.orderimport.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：导入任务 direct 交换机 + 业务队列（带 DLX 死信参数）+ 死信交换机/队列。
 * <p>
 * 与导出拓扑完全对称但独立命名（import.*），保证 two 模块垂直自治、bean 名不冲突。
 * 全部 durable；由 Boot 自动装配的 AmqpAdmin 在首次建连时向 Broker 声明，Broker 未启动不影响应用启动。
 */
@Configuration
public class ImportRabbitConfig {

    public static final String JOB_EXCHANGE = "import.job.exchange";
    public static final String JOB_QUEUE = "import.job.queue";
    public static final String JOB_ROUTING_KEY = "import.job.create";
    public static final String DEAD_LETTER_EXCHANGE = "import.job.dlx";
    public static final String DEAD_LETTER_QUEUE = "import.job.dlq";

    @Bean
    public DirectExchange importJobExchange() {
        return ExchangeBuilder.directExchange(JOB_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange importDeadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** 业务队列：死信转发默认携带原 routing key，故 DLQ 直接绑同一 key。 */
    @Bean
    public Queue importJobQueue() {
        return QueueBuilder.durable(JOB_QUEUE).deadLetterExchange(DEAD_LETTER_EXCHANGE).build();
    }

    @Bean
    public Queue importDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding importJobBinding() {
        return BindingBuilder.bind(importJobQueue()).to(importJobExchange()).with(JOB_ROUTING_KEY);
    }

    @Bean
    public Binding importDeadLetterBinding() {
        return BindingBuilder.bind(importDeadLetterQueue()).to(importDeadLetterExchange()).with(JOB_ROUTING_KEY);
    }
}