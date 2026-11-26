package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.exception.TemplateValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppParameterRulesTest {

    private enum Round { FIRST }

    @Test
    void acceptsTextNumbersBooleansEnumsAndDates() {
        assertThat(WhatsAppParameterRules.check("t", "v", "Jane Doe")).isEqualTo("Jane Doe");
        assertThat(WhatsAppParameterRules.check("t", "v", 42)).isEqualTo("42");
        assertThat(WhatsAppParameterRules.check("t", "v", true)).isEqualTo("true");
        assertThat(WhatsAppParameterRules.check("t", "v", 'x')).isEqualTo("x");
        assertThat(WhatsAppParameterRules.check("t", "v", Round.FIRST)).isEqualTo("FIRST");
        assertThat(WhatsAppParameterRules.check("t", "v", LocalDate.of(2026, 1, 2))).isEqualTo("2026-01-02");
        assertThat(WhatsAppParameterRules.check("t", "v", "four    spaces")).isEqualTo("four    spaces");
        assertThat(WhatsAppParameterRules.check("t", "v", "x".repeat(1024))).hasSize(1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "line\nbreak", "carriage\rreturn", "tab\there", "five     spaces"})
    void rejectsBlankControlCharactersAndSpaceRuns(String value) {
        assertThatThrownBy(() -> WhatsAppParameterRules.check("order-shipped", "customerName", value))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageContaining("order-shipped")
                .hasMessageContaining("customerName");
    }

    @Test
    void rejectsMissingTooLongAndNonTextValues_withoutEchoingTheValue() {
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", null))
                .isInstanceOf(TemplateValidationException.class).hasMessageContaining("missing");
        String secret = "s".repeat(1025);
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", secret))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageContaining("1024")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", List.of("a")))
                .isInstanceOf(TemplateValidationException.class).hasMessageContaining("not a text value");
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", "private value\n"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("private value"));
    }
}
