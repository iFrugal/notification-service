package com.lazydevs.notification.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.kafka.NotificationKafkaListener;
import com.lazydevs.notification.kafka.autoconfigure.NotificationKafkaAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Kafka consumer wiring registers only when
 * {@code notification.kafka.enabled=true}, and that Boot's own consumer and
 * listener container factories back off in favour of ours.
 *
 * <p>Listener containers are switched to {@code autoStartup=false} by a test
 * post-processor, so nothing tries to reach a broker.
 */
class NotificationKafkaAutoConfigurationTest {

    private final WebApplicationContextRunner runner = StarterContextRunners.starterRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaTestConfiguration.class);

    @Test
    void kafkaEnabled_registersListenerAndOwnFactories() {
        runner.withPropertyValues("notification.kafka.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NotificationKafkaListener.class);
            assertThat(context).hasBean("notificationKafkaListener");
            assertThat(context).hasSingleBean(ConsumerFactory.class);
            assertThat(context).hasBean("consumerFactory");
            assertThat(context.getBean("kafkaListenerContainerFactory"))
                    .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
            assertThat(context.getBeanFactory().getBeanDefinition("consumerFactory").getFactoryBeanName())
                    .isEqualTo(NotificationKafkaAutoConfiguration.class.getName());
            assertThat(context.getBeanFactory().getBeanDefinition("kafkaListenerContainerFactory").getFactoryBeanName())
                    .isEqualTo(NotificationKafkaAutoConfiguration.class.getName());
        });
    }

    @Test
    void kafkaEnabled_bootKafkaFactoriesBackOff() {
        runner.withPropertyValues("notification.kafka.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            // Boot's KafkaAutoConfiguration still ran (it supplies KafkaProperties)...
            assertThat(context).hasSingleBean(KafkaProperties.class);
            // ...but its own consumer factory backed off.
            assertThat(context).doesNotHaveBean("kafkaConsumerFactory");
        });
    }

    @Test
    void kafkaDisabled_registersNothing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationKafkaAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(NotificationKafkaListener.class);
            assertThat(context).doesNotHaveBean("consumerFactory");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class KafkaTestConfiguration {

        /** The consumer factory deserialises with a Jackson 2 mapper the host application provides. */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        static BeanPostProcessor disableListenerAutoStartup() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                        factory.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }
}
