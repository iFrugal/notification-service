package com.lazydevs.notification.rest.filter;

import com.lazydevs.notification.core.config.NotificationProperties;
import jakarta.servlet.FilterChain;
import lazydevs.persistence.connection.multitenant.TenantContext;
import lazydevs.services.basic.filter.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
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
     * {@link MockHttpServletRequest} reports header names exactly as added,
     * like Tomcat does for HTTP/1.1, so these tests exercise the same
     * spelling-preserving path a real client hits.
     */
    @ParameterizedTest
    @ValueSource(strings = {"X-Tenant-Id", "x-tenant-id", "X-TENANT-ID"})
    void tenantHeader_resolvesWhateverTheSpelling(String headerName) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader(headerName, "acme");
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] capturedTenant = new String[2];
        FilterChain chain = (request, response) -> {
            capturedTenant[0] = RequestContext.current().getTenantCode();
            capturedTenant[1] = TenantContext.getTenantId();
        };

        filter.doFilter(req, res, chain);

        assertThat(capturedTenant).containsExactly("acme", "acme");
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Service-Id", "x-service-id", "X-SERVICE-ID"})
    void callerHeader_resolvesWhateverTheSpelling(String headerName) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader(headerName, "billing-svc");
        MockHttpServletResponse res = new MockHttpServletResponse();

        Object[] capturedCaller = new Object[1];
        FilterChain chain = (request, response) ->
                capturedCaller[0] = RequestContext.current().get(TenantFilter.CALLER_ID_ATTRIBUTE);

        filter.doFilter(req, res, chain);

        assertThat(capturedCaller[0]).isEqualTo("billing-svc");
    }

    @Test
    void requestContextHeaders_areCaseInsensitiveForDownstreamCode() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader("X-Custom-Header", "v1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] captured = new String[2];
        FilterChain chain = (request, response) -> {
            captured[0] = RequestContext.current().getHeaders().get("x-custom-header");
            captured[1] = RequestContext.current().getHeaders().get("X-CUSTOM-HEADER");
        };

        filter.doFilter(req, res, chain);

        assertThat(captured).containsExactly("v1", "v1");
    }

    @Test
    void mixedCaseBaseHeaders_userRoleAndRequestId_areResolvedAndRequestIdEchoed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        req.addHeader("X-User-Id", "u-1");
        req.addHeader("X-Role", "admin");
        req.addHeader("X-Request-Id", "rid-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] captured = new String[4];
        FilterChain chain = (request, response) -> {
            RequestContext ctx = RequestContext.current();
            captured[0] = ctx.getUserId();
            captured[1] = ctx.getRole();
            captured[2] = ctx.getRequestId();
            captured[3] = MDC.get("x-request-id");
        };

        filter.doFilter(req, res, chain);

        assertThat(captured).containsExactly("u-1", "admin", "rid-123", "rid-123");
        // The client's id is echoed back, not one the base filter generated.
        assertThat(res.getHeaders("x-request-id")).containsExactly("rid-123");
    }

    @Test
    void requestIdAbsent_generatedIdIsKept() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        MockHttpServletResponse res = new MockHttpServletResponse();

        String[] captured = new String[1];
        FilterChain chain = (request, response) -> captured[0] = RequestContext.current().getRequestId();

        filter.doFilter(req, res, chain);

        assertThat(captured[0]).isNotBlank();
        assertThat(res.getHeaders("x-request-id")).containsExactly(captured[0]);
    }

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
