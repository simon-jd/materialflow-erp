package com.materialflow.erp.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Applies {@link ConsumerFailureDetectionProperties} to the Boot-managed consumer factory. */
@Configuration
@ConditionalOnProperty(name = "materialflow.kafka.consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(ConsumerFailureDetectionProperties.class)
public class ConsumerFailureDetectionConfiguration {
    @Bean
    DefaultKafkaConsumerFactoryCustomizer consumerFailureDetection(ConsumerFailureDetectionProperties detection) {
        return factory -> factory.updateConfigs(detection.consumerConfigs());
    }
}
