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
 * Filter to extract tenant ID and caller ID from request headers and
 * propagate them via {@link RequestContext} / {@link TenantContext}.
 *
 * <p>Extends {@link BasicRequestFilter} from app-building-commons to get:
 * <ul>
 *   <li>{@link RequestContext} population (userId, role, requestId, headers, params)</li>
 *   <li>MDC logging context</li>
 *   <li>{@link TenantContext} sync</li>
 * </ul>
 *
 * <p>The caller-id ({@code X-Service-Id}) extraction is part of DD-11; this
 * filter is the only point where the header is read off the wire. Strict
 * admission (rejecting unknown callers) is delegated to a separate
 * {@code CallerAdmissionFilter} that runs after this one — keeping
 * extraction (always-on) decoupled from enforcement (opt-in).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends BasicRequestFilter {

    public static final String TENANT_HEADER = "x-tenant-id";

    /**
     * Header carrying the calling-service identifier (DD-11). The header
     * name is matched case-insensitively because servlet containers
     * lowercase header keys when populating
     * {@link RequestContext#getHeaders()}.
     */
    public static final String CALLER_HEADER = "x-service-id";

    /**
     * {@link RequestContext} attribute key under which the resolved caller
     * id is stashed for downstream filters / services to read. Public so
     * the admission filter and service can both reference the same key.
     */
    public static final String CALLER_ID_ATTRIBUTE = "notification.callerId";

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

        // Caller id (DD-11) — optional. Stash on the RequestContext map
        // (RequestContext extends ConcurrentHashMap) so the admission
        // filter and the service can read it without re-parsing headers.
        String callerId = context.getHeaders().get(CALLER_HEADER);
        if (StringUtils.hasText(callerId)) {
            log.trace("Caller from header: {}", callerId);
            context.set(CALLER_ID_ATTRIBUTE, callerId);
        }
    }
}
