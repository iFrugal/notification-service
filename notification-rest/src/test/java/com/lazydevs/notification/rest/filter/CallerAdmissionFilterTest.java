package com.lazydevs.notification.rest.filter;

import com.lazydevs.notification.core.caller.CallerRegistry;
import com.lazydevs.notification.core.caller.CallerRegistry.Decision;
import jakarta.servlet.FilterChain;
import lazydevs.services.basic.filter.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link CallerAdmissionFilter} short-circuits with HTTP 403 when
 * the registry rejects, and otherwise passes the chain through unchanged.
 *
 * <p>Uses Mockito for the {@link CallerRegistry} so each test pins exactly
 * the decision under exercise — keeps the filter contract independent of
 * registry behaviour (which has its own
 * {@code CallerRegistryTest} coverage).
 */
class CallerAdmissionFilterTest {

    private CallerRegistry registry;
    private CallerAdmissionFilter filter;
    @BeforeEach
    void setUp() {
        registry = mock(CallerRegistry.class);
        // Filter builds its own ObjectMapper internally — the
        // dependency-injection variant was simplified after Spring Boot 4
        // split JacksonAutoConfiguration into its own module.
        filter = new CallerAdmissionFilter(registry);
        // Per-test RequestContext, cleared in @AfterEach.
        RequestContext.current().set(TenantFilter.CALLER_ID_ATTRIBUTE, "billing-svc");
    }

    @AfterEach
    void tearDown() {
        RequestContext.reset();
    }

    @Test
    void registryDisabled_passesThrough_withoutCheck() throws Exception {
        when(registry.isEnabled()).thenReturn(false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        // admit() must NOT be invoked when the registry is off — the
        // filter's whole point is to be free in that case.
        verify(registry, never()).admit(any());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void accept_passesChain() throws Exception {
        when(registry.isEnabled()).thenReturn(true);
        when(registry.admit("billing-svc")).thenReturn(Decision.ACCEPT);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void acceptWithWarning_stillPassesChain() throws Exception {
        when(registry.isEnabled()).thenReturn(true);
        when(registry.admit("billing-svc")).thenReturn(Decision.ACCEPT_WITH_WARNING);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void reject_returns403_andDoesNotInvokeChain() throws Exception {
        when(registry.isEnabled()).thenReturn(true);
        when(registry.admit("billing-svc")).thenReturn(Decision.REJECT);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/notifications");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        // Body contract from DD-11 — error code + caller id echo.
        String body = res.getContentAsString();
        assertThat(body).contains("\"error\":\"unknown_caller\"");
        assertThat(body).contains("\"callerId\":\"billing-svc\"");
    }
}
