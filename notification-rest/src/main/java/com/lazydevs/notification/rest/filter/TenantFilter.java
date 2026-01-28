package com.lazydevs.notification.rest.filter;

import com.lazydevs.notification.core.config.NotificationProperties;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.BasicRequestFilter;
import lazydevs.services.basic.filter.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Filter to extract tenant ID from request header and set in TenantContext.
 * Extends BasicRequestFilter from app-building-commons to get:
 * - RequestContext population (userId, role, requestId, headers, params)
 * - MDC logging context
 * - TenantContext sync
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends BasicRequestFilter {

    public static final String TENANT_HEADER = "x-tenant-id";

    private final NotificationProperties properties;

    public TenantFilter(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void setApplicationSpecificAttributes() {
        RequestContext context = RequestContext.current();

        // Get tenant from header or use default
        String tenantId = context.getHeaders().get(TENANT_HEADER);

        if (!StringUtils.hasText(tenantId)) {
            tenantId = properties.getDefaultTenant();
            log.trace("No {} header, using default tenant: {}", TENANT_HEADER, tenantId);
        } else {
            log.trace("Tenant from header: {}", tenantId);
        }

        // Set in RequestContext and TenantContext
        context.setTenantCode(tenantId);
        TenantContext.setTenantId(tenantId);
    }
}
