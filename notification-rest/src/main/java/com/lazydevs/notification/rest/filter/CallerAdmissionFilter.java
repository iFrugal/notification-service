package com.lazydevs.notification.rest.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.caller.CallerRegistry.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lazydevs.services.basic.filter.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enforces {@link CallerRegistry} admission decisions for the
 * {@code X-Service-Id} header (DD-11).
 *
 * <p>Runs <strong>after</strong> {@link TenantFilter} so the caller id has
 * already been read from the header into {@link RequestContext}. When the
 * registry returns {@link Decision#REJECT} (strict mode + unknown caller),
 * this filter short-circuits with HTTP 403 and a small JSON body —
 * {@code {"error":"unknown_caller","callerId":"..."}}. In every other case
 * the chain proceeds untouched.
 *
 * <p>Rejection is rare by design: the default configuration leaves the
 * registry off, in which case the filter is effectively a no-op.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CallerAdmissionFilter extends OncePerRequestFilter {

    private final CallerRegistry registry;
    private final ObjectMapper objectMapper;

    public CallerAdmissionFilter(CallerRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // The registry is the cheap path when off — admit() returns ACCEPT
        // immediately. The branch on isEnabled() here just spares the
        // RequestContext lookup in the common case.
        if (!registry.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String callerId = (String) RequestContext.current().get(TenantFilter.CALLER_ID_ATTRIBUTE);
        Decision decision = registry.admit(callerId);

        if (decision == Decision.REJECT) {
            writeForbidden(response, callerId);
            return;
        }
        // ACCEPT and ACCEPT_WITH_WARNING both pass — the registry already
        // logged for the warning case.
        chain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletResponse response, String callerId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "unknown_caller");
        body.put("callerId", callerId);
        body.put("message", "callerId is not in the configured caller-registry known-services list");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
