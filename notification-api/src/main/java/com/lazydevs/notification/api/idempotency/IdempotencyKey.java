package com.lazydevs.notification.api.idempotency;

import java.util.Objects;

/**
 * Composite key under which the {@link IdempotencyStore} indexes a single
 * caller-supplied idempotency value.
 *
 * <p>Scope per DD-10: deduplication is per-tenant and per-caller, never
 * global. Two tenants — or two services within the same tenant — can use
 * identical {@code idempotencyKey} values without colliding.
 *
 * @param tenantId       tenant identifier from the {@code X-Tenant-Id}
 *                       header or request body. Never {@code null}.
 * @param callerId       calling service identifier from {@code X-Service-Id}.
 *                       May be {@code null} until DD-11 wires the header,
 *                       in which case the scope effectively reduces to
 *                       {@code (tenantId, idempotencyKey)}.
 * @param idempotencyKey the caller-supplied opaque key. Never {@code null}.
 */
public record IdempotencyKey(
        String tenantId,
        String callerId,
        String idempotencyKey) {

    public IdempotencyKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        // callerId is intentionally nullable — see DD-10 §Reasoning.
    }
}
