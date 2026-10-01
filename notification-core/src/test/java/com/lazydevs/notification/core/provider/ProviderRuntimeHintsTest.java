package com.lazydevs.notification.core.provider;

import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.ClassNameGenerator;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.javapoet.ClassName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderRuntimeHintsTest {

    @Test
    void catalogClasses_haveConstructorHints() {
        RuntimeHints hints = new RuntimeHints();
        new ProviderRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(BuiltInProviders.all()).isNotEmpty();
        BuiltInProviders.all().forEach(entry -> assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of(entry.className()))
                .withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.ACCESS_DECLARED_FIELDS))
                .as(entry.className())
                .accepts(hints));
    }

    @Test
    void configuredFqcn_hasConstructorHints() {
        DefaultListableBeanFactory beanFactory = beanFactoryWith(Map.of(
                "notification.tenants.acme.channels.email.providers.custom.fqcn",
                "com.example.CustomEmailProvider",
                "notification.tenants.globex.channels.sms.providers.custom.fqcn",
                "com.example.CustomSmsProvider",
                "notification.tenants.globex.channels.sms.providers.twilio.properties.account-sid",
                "AC123"));

        BeanFactoryInitializationAotContribution contribution =
                new ProviderFqcnAotProcessor().processAheadOfTime(beanFactory);
        assertThat(contribution).isNotNull();
        DefaultGenerationContext generationContext = new DefaultGenerationContext(
                new ClassNameGenerator(ClassName.get(getClass())),
                new InMemoryGeneratedFiles());
        contribution.applyTo(generationContext, mock(BeanFactoryInitializationCode.class));

        RuntimeHints hints = generationContext.getRuntimeHints();
        for (String className : new String[] {"com.example.CustomEmailProvider", "com.example.CustomSmsProvider"}) {
            assertThat(RuntimeHintsPredicates.reflection()
                    .onType(TypeReference.of(className))
                    .withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.ACCESS_DECLARED_FIELDS))
                    .as(className)
                    .accepts(hints);
        }
    }

    @Test
    void noConfiguredFqcn_contributesNothing() {
        DefaultListableBeanFactory beanFactory = beanFactoryWith(Map.of(
                "notification.tenants.acme.channels.email.providers.smtp.properties.host", "smtp.example"));

        assertThat(new ProviderFqcnAotProcessor().processAheadOfTime(beanFactory)).isNull();
    }

    /** A bean factory as AOT processing sees it: the environment is registered as a singleton. */
    private static DefaultListableBeanFactory beanFactoryWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME, environment);
        return beanFactory;
    }
}
