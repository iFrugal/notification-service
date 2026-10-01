package com.lazydevs.notification.rest.controller;

import com.lazydevs.notification.api.deadletter.DeadLetterEntry;
import com.lazydevs.notification.api.deadletter.DeadLetterStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link DeadLetterStore} that honours the claim / acknowledge /
 * release contract the way a distributed store must (a claimed entry is
 * invisible to every other claim until removed or released) and records
 * every claim, release and removal, so controller tests can assert on the
 * lease handling and on disjointness across concurrent batches.
 *
 * <p>Leases never expire here; tests that need "held by another replica"
 * call {@link #holdElsewhere(String, String)}.
 */
final class RecordingLeasingDeadLetterStore implements DeadLetterStore {

    /** One {@code claim} call and the request ids it returned. */
    record Claim(String tenantId, int limit, Duration lease, List<String> requestIds) {
    }

    private final List<DeadLetterEntry> entries = new ArrayList<>();
    private final Set<String> leased = new HashSet<>();
    private final List<Claim> claims = new CopyOnWriteArrayList<>();
    private final List<String> released = new CopyOnWriteArrayList<>();
    private final List<String> removed = new CopyOnWriteArrayList<>();

    RecordingLeasingDeadLetterStore(DeadLetterEntry... initial) {
        entries.addAll(List.of(initial));
    }

    /** Simulates another replica holding the lease on an entry. */
    synchronized void holdElsewhere(String tenantId, String requestId) {
        leased.add(key(tenantId, requestId));
    }

    List<Claim> claims() {
        return List.copyOf(claims);
    }

    List<String> released() {
        return List.copyOf(released);
    }

    List<String> removed() {
        return List.copyOf(removed);
    }

    @Override
    public synchronized void add(DeadLetterEntry entry) {
        entries.add(entry);
    }

    @Override
    public synchronized Optional<List<DeadLetterEntry>> snapshot() {
        return Optional.of(List.copyOf(entries));
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized Optional<DeadLetterEntry> findByRequestId(String tenantId, String requestId) {
        return entries.stream().filter(e -> matches(e, tenantId, requestId)).findFirst();
    }

    @Override
    public synchronized boolean remove(String tenantId, String requestId) {
        removed.add(requestId);
        leased.remove(key(tenantId, requestId));
        return entries.removeIf(e -> matches(e, tenantId, requestId));
    }

    @Override
    public synchronized List<DeadLetterEntry> claim(String tenantId, int limit, Duration lease) {
        List<DeadLetterEntry> out = entries.stream()
                .filter(e -> Objects.equals(tenantId, e.request().getTenantId()))
                .filter(e -> !leased.contains(key(tenantId, e.request().getRequestId())))
                .limit(Math.max(0, limit))
                .toList();
        out.forEach(e -> leased.add(key(tenantId, e.request().getRequestId())));
        claims.add(new Claim(tenantId, limit, lease, out.stream().map(e -> e.request().getRequestId()).toList()));
        return out;
    }

    @Override
    public synchronized Optional<DeadLetterEntry> claim(String tenantId, String requestId, Duration lease) {
        Optional<DeadLetterEntry> out = findByRequestId(tenantId, requestId)
                .filter(e -> leased.add(key(tenantId, requestId)));
        claims.add(new Claim(tenantId, 1, lease, out.map(e -> List.of(requestId)).orElse(List.of())));
        return out;
    }

    @Override
    public synchronized void release(String tenantId, String requestId) {
        released.add(requestId);
        leased.remove(key(tenantId, requestId));
    }

    private static boolean matches(DeadLetterEntry e, String tenantId, String requestId) {
        return Objects.equals(tenantId, e.request().getTenantId())
                && Objects.equals(requestId, e.request().getRequestId());
    }

    private static String key(String tenantId, String requestId) {
        return tenantId + "\u0000" + requestId;
    }
}
