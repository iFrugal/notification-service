package com.lazydevs.notification.rest.filter;

import com.lazydevs.notification.core.config.NotificationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.BasicRequestFilter;
import lazydevs.services.basic.filter.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
 *
 * <p>Header names are matched case-insensitively, as HTTP requires:
 * {@code X-Tenant-Id}, {@code x-tenant-id} and {@code X-TENANT-ID} are the
 * same header. {@link BasicRequestFilter} keys
 * {@link RequestContext#getHeaders()} by the names exactly as the client
 * sent them (Tomcat keeps HTTP/1.1 spelling) and looks its own headers up
 * by lowercase name, so this filter replaces that map with a
 * case-insensitive copy, for itself and for downstream code, and
 * re-resolves the base class's {@code x-user-id}, {@code x-role} and
 * {@code x-request-id}.
 */
@Slf4j
public class TenantFilter extends BasicRequestFilter {

    /** Tenant header (DD-03); matched case-insensitively. */
    public static final String TENANT_HEADER = "x-tenant-id";

    /** Header carrying the calling-service identifier (DD-11); matched case-insensitively. */
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
    public void setRequestContext(HttpServletRequest request, HttpServletResponse response) {
        super.setRequestContext(request, response);
        RequestContext context = RequestContext.current();

        Map<String, String> headers = new LinkedCaseInsensitiveMap<>(context.getHeaders().size(), Locale.ROOT);
        headers.putAll(context.getHeaders());
        context.setHeaders(headers);

        // The base class prefers the header over the query parameter but
        // misses any header not sent in lowercase; apply the same
        // precedence again now that the lookup is case-insensitive.
        String userId = headers.getOrDefault(USER_ID_HEADER, context.getUserId());
        if (!Objects.equals(userId, context.getUserId())) {
            context.setUserId(userId);
            MDC.put(USER_ID_HEADER, userId);
        }
        String role = headers.getOrDefault(ROLE_HEADER, context.getRole());
        if (!Objects.equals(role, context.getRole())) {
            context.setRole(role);
            MDC.put(ROLE_HEADER, role);
        }
        String requestId = headers.get(REQUEST_ID_HEADER);
        if (StringUtils.hasText(requestId) && !requestId.equals(context.getRequestId())) {
            // Replaces the id the base class generated and echoed back.
            context.setRequestId(requestId);
            MDC.put(REQUEST_ID_HEADER, requestId);
            response.setHeader(REQUEST_ID_HEADER, requestId);
        }
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
