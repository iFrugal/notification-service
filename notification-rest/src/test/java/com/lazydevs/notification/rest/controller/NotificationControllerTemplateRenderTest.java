package com.lazydevs.notification.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DD-27: a {@link TemplateRenderException} from the send path (validate-before-dispatch)
 * is an HTTP 422 whose body names the error code, reason, template and variable path.
 */
class NotificationControllerTemplateRenderTest {

    private NotificationService notificationService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResultActions send() throws Exception {
        NotificationRequest body = NotificationRequest.builder()
                .notificationType("WELCOME")
                .channel(Channel.EMAIL)
                .recipient(new EmailRecipient(null, "user@example.com", null, null, null, null))
                .build();
        return mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "acme")
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void modelError_returns422WithCodeReasonAndPath() throws Exception {
        when(notificationService.send(any())).thenThrow(new TemplateRenderException(Reason.MODEL_INVALID,
                "acme", "WELCOME", "user.address.city", "Template WELCOME for tenant acme could not be rendered:\n"
                        + "missing", null));

        send().andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("TEMPLATE_MODEL_INVALID"))
                .andExpect(jsonPath("$.reason").value("MODEL_INVALID"))
                .andExpect(jsonPath("$.templateId").value("WELCOME"))
                .andExpect(jsonPath("$.path").value("user.address.city"))
                .andExpect(jsonPath("$.message").value(
                        "Template WELCOME for tenant acme could not be rendered:\nmissing"));
    }

    @Test
    void emptyOutput_returns422WithoutPath() throws Exception {
        when(notificationService.send(any())).thenThrow(new TemplateRenderException(Reason.EMPTY_OUTPUT,
                "acme", "WELCOME", null, "Template WELCOME rendered an email whose subject is blank", null));

        send().andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("TEMPLATE_EMPTY_OUTPUT"))
                .andExpect(jsonPath("$.reason").value("EMPTY_OUTPUT"))
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    void templateNotFound_isAlso422() throws Exception {
        when(notificationService.send(any())).thenThrow(new TemplateNotFoundException("acme", "email", "WELCOME"));

        send().andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.templateId").value("WELCOME"));
    }
}
