package com.lazydevs.notification.kafka.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.kafka.NotificationKafkaListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring for async notification requests, active when
 * {@code notification.kafka.enabled=true}.
 *
 * <p>Ordered before Boot's {@link KafkaAutoConfiguration} so its own
 * {@code kafkaConsumerFactory} and {@code kafkaListenerContainerFactory}
 * back off in favour of the beans defined here.
 * Boot's Kafka auto-configuration still supplies {@link KafkaProperties}
 * and the {@code @EnableKafka} listener infrastructure.
 */
@Slf4j
@AutoConfiguration(before = KafkaAutoConfiguration.class)
@ConditionalOnClass(EnableKafka.class)
@ConditionalOnBooleanProperty("notification.kafka.enabled")
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationKafkaAutoConfiguration {

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

    @Bean
    public NotificationKafkaListener notificationKafkaListener(NotificationService notificationService,
                                                               NotificationProperties properties) {
        return new NotificationKafkaListener(notificationService, properties);
    }
}
