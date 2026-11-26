package com.lazydevs.notification.channel.whatsapp;

import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
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
                .isInstanceOfSatisfying(TemplateRenderException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.PARAMETER_INVALID);
                    assertThat(e.getErrorCode()).isEqualTo("TEMPLATE_PARAMETER_INVALID");
                    assertThat(e.getVariablePath()).isEqualTo("customerName");
                    assertThat(e.getTemplateId()).isEqualTo("order-shipped");
                    assertThat(e.getStatusCode()).isEqualTo(422);
                })
                .hasMessageContaining("order-shipped")
                .hasMessageContaining("customerName");
    }

    @Test
    void rejectsMissingTooLongAndNonTextValues_withoutEchoingTheValue() {
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", null))
                .isInstanceOfSatisfying(TemplateRenderException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID))
                .hasMessageContaining("missing");
        String secret = "s".repeat(1025);
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", secret))
                .isInstanceOfSatisfying(TemplateRenderException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.PARAMETER_INVALID))
                .hasMessageContaining("1024")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
        List<String> notText = List.of("a");
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", notText))
                .isInstanceOfSatisfying(TemplateRenderException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID))
                .hasMessageContaining("not a text value");
        assertThatThrownBy(() -> WhatsAppParameterRules.check("t", "v", "private value\n"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("private value"));
    }
}
