package com.lazydevs.notification.channel.push.fcm;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.context.annotation.ImportRuntimeHints;

import static org.assertj.core.api.Assertions.assertThat;

class FcmRuntimeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    FcmRuntimeHintsTest() {
        new FcmRuntimeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void theServiceFileIsInTheImage() {
        assertThat(RuntimeHintsPredicates.resource().forResource(FcmRuntimeHints.SERVICE_FILE)).accepts(hints);
    }

    @Test
    void everyShippedFactory_isConstructibleReflectively() {
        assertThat(RuntimeHintsPredicates.reflection().onType(ServiceAccountJwtTokenProviderFactory.class)
                .withMemberCategory(org.springframework.aot.hint.MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(
                "com.lazydevs.notification.channel.push.fcm.googleauth.GoogleAuthTokenProviderFactory"))).accepts(hints);
    }

    @Test
    void theServiceFileNamesOnlyShippedFactories() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(FcmRuntimeHints.SERVICE_FILE)) {
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            content.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> assertThat(FcmRuntimeHints.FACTORIES).contains(line));
        }
    }

    @Test
    void theAutoConfigurationImportsTheHints() {
        assertThat(FcmPushProviderAutoConfiguration.class.getAnnotation(ImportRuntimeHints.class).value())
                .contains(FcmRuntimeHints.class);
    }
}
