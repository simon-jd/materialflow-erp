package com.materialflow.erp.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@ConditionalOnProperty(name = "materialflow.kafka.consumer.enabled", havingValue = "true")
public class KafkaConsumerConfiguration {
    @Bean
    DefaultErrorHandler domainEventErrorHandler(KafkaTemplate<Object, Object> kafka, MeterRegistry meters) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));
        // Also the pre-migration default; make the offset safety contract explicit.
        recoverer.setFailIfSendResultIsError(true);
        var backoff = new ExponentialBackOffWithMaxRetries(4);
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(5000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.setBackOffFunction((record, exception) -> DomainEventFailurePolicy.backOff(exception));
        handler.addNotRetryableExceptions(InvalidEventEnvelopeException.class);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {
                meters.counter("event_consumer_failure_total", "reason",
                        DomainEventFailurePolicy.reason(exception)).increment();
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
                meters.counter("event_consumer_recovered_total", "reason",
                        DomainEventFailurePolicy.reason(exception)).increment();
            }

            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                meters.counter("event_consumer_recovery_failed_total").increment();
            }
        });
        // RECORD commits after successful handling/recovery. commitRecovered applies
        // only to MANUAL_IMMEDIATE; it did not control this factory's offsets.
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler domainEventErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(domainEventErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
