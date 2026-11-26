package com.lazydevs.notification.api.exception;

import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRenderExceptionTest {

    @Test
    void carriesReasonIdsPathAndA422() {
        IllegalStateException cause = new IllegalStateException("engine");
        TemplateRenderException e = new TemplateRenderException(Reason.MODEL_INVALID, "acme", "WELCOME",
                "user.name", "user.name is missing", cause);

        assertThat(e).isInstanceOf(NotificationException.class);
        assertThat(e.getReason()).isEqualTo(Reason.MODEL_INVALID);
        assertThat(e.getErrorCode()).isEqualTo("TEMPLATE_MODEL_INVALID");
        assertThat(e.getStatusCode()).isEqualTo(TemplateRenderException.HTTP_UNPROCESSABLE_ENTITY).isEqualTo(422);
        assertThat(e.getTenantId()).isEqualTo("acme");
        assertThat(e.getTemplateId()).isEqualTo("WELCOME");
        assertThat(e.getVariablePath()).isEqualTo("user.name");
        assertThat(e.getMessage()).isEqualTo("user.name is missing");
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void everyReasonHasItsOwnErrorCode() {
        assertThat(Reason.NOT_FOUND.errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(Reason.INVALID_TEMPLATE.errorCode()).isEqualTo("TEMPLATE_INVALID");
        assertThat(Reason.MODEL_INVALID.errorCode()).isEqualTo("TEMPLATE_MODEL_INVALID");
        assertThat(Reason.EMPTY_OUTPUT.errorCode()).isEqualTo("TEMPLATE_EMPTY_OUTPUT");
    }

    @Test
    void templateNotFoundException_keepsItsCodeStatusAndMessages() {
        TemplateNotFoundException byIds = new TemplateNotFoundException("acme", "email", "WELCOME");
        TemplateNotFoundException byMessage = new TemplateNotFoundException("Template not found: invalid tenantId");

        assertThat(byIds).isInstanceOf(TemplateRenderException.class);
        assertThat(byIds.getErrorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(byIds.getStatusCode()).isEqualTo(404);
        assertThat(byIds.getReason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(byIds.getTenantId()).isEqualTo("acme");
        assertThat(byIds.getTemplateId()).isEqualTo("WELCOME");
        assertThat(byIds.getVariablePath()).isNull();
        assertThat(byIds.getMessage()).isEqualTo("Template not found for tenant 'acme', channel 'email', type 'WELCOME'. "
                + "Expected at: templates/acme/email/WELCOME.ftl or templates/default/email/WELCOME.ftl");

        assertThat(byMessage.getStatusCode()).isEqualTo(404);
        assertThat(byMessage.getErrorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(byMessage.getTenantId()).isNull();
        assertThat(byMessage.getTemplateId()).isNull();
        assertThat(byMessage.getMessage()).isEqualTo("Template not found: invalid tenantId");
    }
}
