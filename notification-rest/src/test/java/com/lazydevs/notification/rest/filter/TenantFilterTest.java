package com.lazydevs.notification.rest.filter;

import com.lazydevs.notification.core.config.NotificationProperties;
import jakarta.servlet.FilterChain;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TenantFilter}'s header extraction. Covers both the
 * pre-existing tenant header behaviour and the DD-11 {@code X-Service-Id}
 * header extraction added in this PR.
 *
 * <p>{@link RequestContext} and {@link TenantContext} are reset after every
 * test — both are ThreadLocal-backed and would otherwise leak between
 * tests.
 */
class TenantFilterTest {

    private TenantFilter filter;
    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setDefaultTenant("default-tenant");
        filter = new TenantFilter(properties);
    }

    @AfterEach
    void tearDown() {
        RequestContext.reset();
        TenantContext.reset();
    }

    /**
     * BasicRequestFilter populates RequestContext.headers from
     * {@link jakarta.servlet.http.HttpServletRequest#getHeaderNames()} and
     * looks up the resulting map case-sensitively with lowercase keys. Real
     * servlet containers (Tomcat) lowercase header names; Spring's
     * {@link MockHttpServletRequest} preserves the casing of {@code addHeader}.
     * So tests have to add headers in lowercase to mirror the production
     * lookup path. (The TenantFilter constants are already lowercase.)
     */
    @Test
    void tenantHeaderAndCallerHeader_bothPropagated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader("x-tenant-id", "acme");
        req.addHeader("x-service-id", "billing-svc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] capturedTenant = new String[1];
        String[] capturedCaller = new String[1];
        FilterChain chain = (request, response) -> {
            RequestContext ctx = RequestContext.current();
            capturedTenant[0] = ctx.getTenantCode();
            capturedCaller[0] = (String) ctx.get(TenantFilter.CALLER_ID_ATTRIBUTE);
        };

        filter.doFilter(req, res, chain);

        assertThat(capturedTenant[0]).isEqualTo("acme");
        assertThat(capturedCaller[0]).isEqualTo("billing-svc");
    }

    @Test
    void callerHeaderAbsent_callerAttributeNotSet() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader("x-tenant-id", "acme");
        // No X-Service-Id
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] capturedCaller = new String[1];
        boolean[] attributeWasSet = new boolean[1];
        FilterChain chain = (request, response) -> {
            RequestContext ctx = RequestContext.current();
            capturedCaller[0] = (String) ctx.get(TenantFilter.CALLER_ID_ATTRIBUTE);
            attributeWasSet[0] = ctx.containsKey(TenantFilter.CALLER_ID_ATTRIBUTE);
        };

        filter.doFilter(req, res, chain);

        assertThat(capturedCaller[0]).isNull();
        assertThat(attributeWasSet[0])
                .as("Filter should leave the attribute unset rather than write a null value")
                .isFalse();
    }

    @Test
    void callerHeaderBlank_treatedAsAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader("x-tenant-id", "acme");
        req.addHeader("x-service-id", "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean[] attributeWasSet = new boolean[1];
        FilterChain chain = (request, response) -> {
            attributeWasSet[0] = RequestContext.current()
                    .containsKey(TenantFilter.CALLER_ID_ATTRIBUTE);
        };

        filter.doFilter(req, res, chain);

        assertThat(attributeWasSet[0])
                .as("Whitespace-only header should be treated as absent")
                .isFalse();
    }

    @Test
    void tenantHeaderAbsent_fallsBackToDefault() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        // No X-Tenant-Id
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] capturedTenant = new String[1];
        FilterChain chain = (request, response) -> {
            capturedTenant[0] = RequestContext.current().getTenantCode();
        };

        filter.doFilter(req, res, chain);

        assertThat(capturedTenant[0]).isEqualTo("default-tenant");
    }
}
