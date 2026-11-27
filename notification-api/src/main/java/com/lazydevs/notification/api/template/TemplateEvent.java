package com.lazydevs.notification.api.template;

import java.time.Instant;

/**
 * One entry of a template's audit trail (DD-38). The change feed also reads these.
 *
 * @param id      store-assigned id, increasing
 * @param key     the template
 * @param action  what happened
 * @param version version the action produced or touched, {@code null} for draft actions
 * @param actor   who did it, as the host named them
 * @param at      when
 * @param note    the actor's note, may be {@code null}
 * @since 1.3.0
 */
public record TemplateEvent(long id, TemplateKey key, TemplateEventAction action, Integer version,
                            String actor, Instant at, String note) {
}
