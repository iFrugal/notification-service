package com.lazydevs.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.model.NotificationRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for notification consumer.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "notification.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, NotificationRequest> consumerFactory(
            KafkaProperties kafkaProperties,
            ObjectMapper objectMapper) {

        // KafkaProperties#buildConsumerProperties() is no-args in Spring Boot 4
        // (was buildConsumerProperties(SslBundles) in 3.x).
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());

        // Configure deserializers
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // JSON deserializer configuration
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.lazydevs.notification.api.model");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, NotificationRequest.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        JsonDeserializer<NotificationRequest> valueDeserializer =
                new JsonDeserializer<>(NotificationRequest.class, objectMapper);
        valueDeserializer.addTrustedPackages("com.lazydevs.notification.api.model");

        return new DefaultKafkaConsumerFactory<>(
                config,
                new StringDeserializer(),
                valueDeserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationRequest> kafkaListenerContainerFactory(
            ConsumerFactory<String, NotificationRequest> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, NotificationRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Configure error handling
        factory.setCommonErrorHandler(new org.springframework.kafka.listener.DefaultErrorHandler());

        log.info("Kafka listener container factory configured");
        return factory;
    }
}
