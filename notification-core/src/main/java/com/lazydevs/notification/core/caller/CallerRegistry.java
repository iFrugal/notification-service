package com.lazydevs.notification.core.caller;

import com.lazydevs.notification.core.config.NotificationProperties;
import com.lazydevs.notification.core.config.NotificationProperties.CallerRegistryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional admission-control / observability surface for the
 * {@code X-Service-Id} header introduced in DD-11.
 *
 * <p>Behavioural matrix per DD-11 §Decision §"Caller registry":
 *
 * <pre>
 *   enabled=false          → {@link #admit(String)} returns ACCEPTED for everything
 *   enabled=true,strict=false → unknown callers logged WARN, ACCEPTED
 *   enabled=true,strict=true  → unknown callers REJECTED
 *   callerId == null       → ACCEPTED in every mode
 * </pre>
 *
 * <p>This component holds <em>no</em> mutable state — the configured set of
 * known services is materialised once at construction so {@link #admit}
 * stays allocation-free in the request path.
 */
@Slf4j
@Component
public class CallerRegistry {

    private final CallerRegistryProperties config;
    private final Set<String> knownServices;

    public CallerRegistry(NotificationProperties properties) {
        this.config = properties.getCallerRegistry();
        // LinkedHashSet preserves the configured ordering (useful for the
        // admin endpoint) while giving O(1) contains() for the hot path.
        // Wrapped with unmodifiableSet rather than Set.copyOf because the
        // latter would discard insertion order — the admin endpoint relies
        // on it.
        List<String> source = config.getKnownServices();
        this.knownServices = source == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    /**
     * Decide whether a request bearing this {@code callerId} should be
     * admitted.
     *
     * <p>The decision is intentionally a tri-state ({@link Decision}) rather
     * than a boolean: {@code REJECT} is only emitted in strict mode, and
     * the filter needs to distinguish "unknown but accepted with a warning"
     * from "unknown and rejected" to produce the right HTTP response.
     *
     * @param callerId the resolved caller id (may be {@code null} when no
     *                 header was sent and the request body didn't override)
     * @return the admission decision
     */
    public Decision admit(String callerId) {
        if (!config.isEnabled() || callerId == null || callerId.isBlank()) {
            // Registry off, OR no caller id supplied → no opinion.
            return Decision.ACCEPT;
        }
        if (knownServices.contains(callerId)) {
            return Decision.ACCEPT;
        }
        if (config.isStrict()) {
            log.warn("Rejecting unknown callerId='{}' (caller-registry strict mode is on)", callerId);
            return Decision.REJECT;
        }
        log.warn("Unknown callerId='{}' — accepted (caller-registry strict mode is off; "
                + "add to notification.caller-registry.known-services to silence)", callerId);
        return Decision.ACCEPT_WITH_WARNING;
    }

    /** Whether the registry is enabled. */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /** Whether strict admission is enabled. Implies {@link #isEnabled()} to mean anything. */
    public boolean isStrict() {
        return config.isStrict();
    }

    /**
     * Read-only view of the configured known services, in configuration
     * order. The returned set is unmodifiable so admin-endpoint
     * serialisers can't accidentally mutate the underlying configuration,
     * and it's a {@link LinkedHashSet} under the hood so iteration order
     * matches what was declared in YAML.
     */
    public Set<String> getKnownServices() {
        return knownServices;
    }

    /**
     * Whether {@code callerId} is in the configured known-services list.
     * Independent of {@link #isEnabled()} — useful for tests.
     */
    public boolean isKnown(String callerId) {
        return callerId != null && knownServices.contains(callerId);
    }

    /**
     * Outcome of an {@link #admit(String)} call. The filter maps these to
     * HTTP responses; the service path simply reads them for logging.
     */
    public enum Decision {
        /** Pass through silently — registry off, null callerId, or known caller. */
        ACCEPT,
        /** Pass through but the registry already logged a WARN for this unknown caller. */
        ACCEPT_WITH_WARNING,
        /** Strict-mode rejection — filter returns HTTP 403. */
        REJECT
    }
}
