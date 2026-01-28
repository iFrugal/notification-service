package com.lazydevs.notification.core.service;

import com.lazydevs.notification.api.NotificationStatus;
import com.lazydevs.notification.api.model.NotificationAudit;
import com.lazydevs.notification.api.model.NotificationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * No-op implementation of NotificationAuditService.
 * Used when audit is disabled.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "notification.audit", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAuditService implements NotificationAuditService {

    public NoOpAuditService() {
        log.info("Notification audit is DISABLED. Using NoOpAuditService.");
    }

    @Override
    public NotificationAudit recordReceived(NotificationRequest request) {
        // No-op
        return null;
    }

    @Override
    public NotificationAudit updateStatus(String requestId, NotificationStatus status,
                                           String providerMessageId, String errorCode, String errorMessage) {
        // No-op
        return null;
    }

    @Override
    public Optional<NotificationAudit> findByRequestId(String requestId) {
        return Optional.empty();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
