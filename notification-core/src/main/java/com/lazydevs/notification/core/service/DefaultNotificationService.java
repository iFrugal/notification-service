package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationService;
import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.channel.NotificationProvider;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.NotificationException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.provider.ProviderRegistry;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Default implementation of NotificationService.
 */
@Slf4j
@Service
public class DefaultNotificationService implements NotificationService {

    private final NotificationProperties properties;
    private final ProviderRegistry providerRegistry;
    private final NotificationTemplateEngine templateEngine;
    private final NotificationAuditService auditService;
    private final Executor asyncExecutor;

    public DefaultNotificationService(
            NotificationProperties properties,
            ProviderRegistry providerRegistry,
            NotificationTemplateEngine templateEngine,
            NotificationAuditService auditService) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.templateEngine = templateEngine;
        this.auditService = auditService;
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public NotificationResponse send(NotificationRequest request) {
        Instant receivedAt = Instant.now();

        try {
            // Enrich request with defaults
            enrichRequest(request);
            log.debug("Processing notification: requestId={}, channel={}, type={}",
                    request.getRequestId(), request.getChannel(), request.getNotificationType());

            // Record audit (received)
            auditService.recordReceived(request);

            // Render template
            RenderedContent content = templateEngine.render(request);

            // Get provider
            NotificationProvider provider = providerRegistry.getProvider(
                    request.getTenantId(),
                    request.getChannel(),
                    request.getProvider());

            // Send notification
            SendResult result = provider.send(request, content);

            // Build response
            NotificationResponse response;
            if (result.isSuccess()) {
                response = NotificationResponse.builder()
                        .requestId(request.getRequestId())
                        .correlationId(request.getCorrelationId())
                        .tenantId(request.getTenantId())
                        .channel(request.getChannel())
                        .provider(provider.getProviderName())
                        .status(NotificationStatus.SENT)
                        .providerMessageId(result.getMessageId())
                        .receivedAt(receivedAt)
                        .processedAt(Instant.now())
                        .sentAt(result.getTimestamp())
                        .build();

                log.info("Notification sent: requestId={}, provider={}, messageId={}",
                        request.getRequestId(), provider.getProviderName(), result.getMessageId());
            } else {
                response = NotificationResponse.builder()
                        .requestId(request.getRequestId())
                        .correlationId(request.getCorrelationId())
                        .tenantId(request.getTenantId())
                        .channel(request.getChannel())
                        .provider(provider.getProviderName())
                        .status(NotificationStatus.FAILED)
                        .errorCode(result.getErrorCode())
                        .errorMessage(result.getErrorMessage())
                        .receivedAt(receivedAt)
                        .processedAt(Instant.now())
                        .build();

                log.warn("Notification failed: requestId={}, error={}: {}",
                        request.getRequestId(), result.getErrorCode(), result.getErrorMessage());
            }

            // Update audit
            auditService.updateStatus(request.getRequestId(), response.getStatus(),
                    response.getProviderMessageId(), response.getErrorCode(), response.getErrorMessage());

            return response;

        } catch (NotificationException e) {
            log.error("Notification error: requestId={}, error={}: {}",
                    request.getRequestId(), e.getErrorCode(), e.getMessage());

            NotificationResponse response = NotificationResponse.builder()
                    .requestId(request.getRequestId())
                    .correlationId(request.getCorrelationId())
                    .tenantId(request.getTenantId())
                    .channel(request.getChannel())
                    .status(NotificationStatus.FAILED)
                    .errorCode(e.getErrorCode())
                    .errorMessage(e.getMessage())
                    .receivedAt(receivedAt)
                    .processedAt(Instant.now())
                    .build();

            auditService.updateStatus(request.getRequestId(), response.getStatus(),
                    null, response.getErrorCode(), response.getErrorMessage());

            return response;

        } catch (Exception e) {
            log.error("Unexpected error processing notification: requestId={}", request.getRequestId(), e);

            NotificationResponse response = NotificationResponse.builder()
                    .requestId(request.getRequestId())
                    .correlationId(request.getCorrelationId())
                    .tenantId(request.getTenantId())
                    .channel(request.getChannel())
                    .status(NotificationStatus.FAILED)
                    .errorCode("INTERNAL_ERROR")
                    .errorMessage(e.getMessage())
                    .receivedAt(receivedAt)
                    .processedAt(Instant.now())
                    .build();

            auditService.updateStatus(request.getRequestId(), response.getStatus(),
                    null, response.getErrorCode(), response.getErrorMessage());

            return response;
        }
    }

    @Override
    public CompletableFuture<NotificationResponse> sendAsync(NotificationRequest request) {
        return CompletableFuture.supplyAsync(() -> send(request), asyncExecutor);
    }

    @Override
    public List<NotificationResponse> sendBatch(List<NotificationRequest> requests) {
        List<NotificationResponse> responses = new ArrayList<>(requests.size());
        for (NotificationRequest request : requests) {
            responses.add(send(request));
        }
        return responses;
    }

    @Override
    public CompletableFuture<List<NotificationResponse>> sendBatchAsync(List<NotificationRequest> requests) {
        List<CompletableFuture<NotificationResponse>> futures = requests.stream()
                .map(this::sendAsync)
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .toList());
    }

    /**
     * Enrich request with default values.
     * Uses RequestContext from app-building-commons for requestId and tenant.
     */
    private void enrichRequest(NotificationRequest request) {
        RequestContext context = RequestContext.current();

        // Generate request ID if not provided (use from RequestContext if available)
        if (!StringUtils.hasText(request.getRequestId())) {
            String contextRequestId = context.getRequestId();
            request.setRequestId(StringUtils.hasText(contextRequestId)
                    ? contextRequestId
                    : UUID.randomUUID().toString());
        }

        // Set tenant ID from context if not provided
        if (!StringUtils.hasText(request.getTenantId())) {
            String contextTenant = TenantContext.getTenantId();
            if (!StringUtils.hasText(contextTenant)) {
                contextTenant = context.getTenantCode();
            }
            request.setTenantId(StringUtils.hasText(contextTenant)
                    ? contextTenant
                    : properties.getDefaultTenant());
        }

        // Ensure template data is not null
        if (request.getTemplateData() == null) {
            request.setTemplateData(new java.util.HashMap<>());
        }
    }
}
