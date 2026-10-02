package com.lazydevs.notification.channel.push.fcm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lazydevs.notification.api.delivery.DeliveryEvent;
import com.lazydevs.notification.api.delivery.DeliveryEventPublisher;
import com.lazydevs.notification.api.delivery.DeliveryEvents;
import com.lazydevs.notification.api.delivery.DeliveryStatus;
import com.lazydevs.notification.api.model.FailureType;
import com.lazydevs.notification.api.model.SendResult;
import com.lazydevs.notification.api.util.PiiMasking;
import com.lazydevs.notification.channel.push.fcm.FcmMessageMapper.Target;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Performs the FCM {@code messages:send} calls of one provider instance (one tenant).
 *
 * <ul>
 *   <li>Every call, single or one of many, holds a permit of a per-instance
 *       {@link Semaphore} of {@code concurrency} permits. A call that gets no permit
 *       within {@code timeout} fails {@code TRANSIENT} with {@value #CODE_CONCURRENCY_LIMIT}.</li>
 *   <li>{@code deviceTokens} are sent in parallel on virtual threads, one call per token
 *       (the HTTP v1 API has no multicast). The calling thread waits for all of them,
 *       then aggregates and publishes the invalid-target events itself, so listeners
 *       see the caller's thread-bound state, such as the tenant.</li>
 *   <li>Per-token results go into the provider metadata: {@value #METADATA_RESULTS}
 *       (a list of {@code tokenHash}, {@code status} and {@code name} or {@code errorCode}),
 *       {@value #METADATA_SUCCESS_COUNT} and {@value #METADATA_FAILURE_COUNT}.</li>
 *   <li>Aggregate: every token sent is a success; with {@code multi-token-policy: any}
 *       one sent token is a success; with {@code all} a partial result is
 *       {@code AMBIGUOUS} ({@value #CODE_PARTIAL_FAILURE}), because a retry would
 *       notify the devices that already got it a second time. When no token was
 *       sent: {@code TRANSIENT} if every failure was transient, {@code AMBIGUOUS}
 *       if one of them may have been accepted, else {@code PERMANENT}.</li>
 *   <li>A 401 without an FcmError invalidates the access token and resends the
 *       call once, inline.</li>
 * </ul>
 */
@Slf4j
final class FcmMultiTokenSender {

    static final String CODE_CONCURRENCY_LIMIT = "FCM_CONCURRENCY_LIMIT";
    static final String CODE_PARTIAL_FAILURE = "FCM_PARTIAL_FAILURE";
    static final String CODE_ALL_TOKENS_FAILED = "FCM_ALL_TOKENS_FAILED";
    static final String CODE_INTERRUPTED = "FCM_INTERRUPTED";

    static final String METADATA_RESULTS = "fcm.results";
    static final String METADATA_SUCCESS_COUNT = "fcm.successCount";
    static final String METADATA_FAILURE_COUNT = "fcm.failureCount";
    static final String METADATA_VALIDATE_ONLY = "fcm.validateOnly";

    static final String RESULT_TOKEN_HASH = "tokenHash";
    static final String RESULT_STATUS = "status";
    static final String RESULT_NAME = "name";
    static final String RESULT_ERROR_CODE = "errorCode";
    static final String STATUS_SENT = "SENT";
    static final String STATUS_FAILED = "FAILED";

    /** Prefix of the message id of a failed send, which FCM never named. */
    static final String LOCAL_ID_PREFIX = "fcm-local:";

    private final FcmSettings settings;
    private final FcmHttpTransport transport;
    private final FcmAccessTokenProvider tokens;
    private final URI sendUri;
    private final Clock clock;
    private final Semaphore permits;

    /** The outcome of one call: a message name, or a failure. */
    record Outcome(String targetHash, String name, FcmFailure failure) {
        boolean sent() {
            return failure == null;
        }
    }

    FcmMultiTokenSender(FcmSettings settings, FcmHttpTransport transport, FcmAccessTokenProvider tokens,
                        String projectId, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sendUri = sendUri(settings.endpoint(), projectId);
        this.permits = new Semaphore(settings.concurrency(), true);
    }

    static URI sendUri(URI endpoint, String projectId) {
        return URI.create(endpoint + "/v1/projects/" + projectId + "/messages:send");
    }

    URI sendUri() {
        return sendUri;
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * Send to one target ({@code deviceToken}, {@code fid}, {@code topic} or {@code condition}).
     */
    SendResult sendSingle(ObjectNode message, Target target, DeliveryEventPublisher publisher) {
        String value = target.values().getFirst();
        Outcome outcome = sendOne(message, target, value);
        String where = describe(target, outcome.targetHash());
        if (outcome.sent()) {
            log.debug("Push sent via FCM: {}, name={}", where, outcome.name());
            return SendResult.success(outcome.name(), baseMetadata());
        }
        FcmFailure failure = outcome.failure();
        String messageId = localId();
        log.warn("FCM send failed: {}, code={}, type={}, messageId={}: {}", where, failure.code(), failure.type(),
                messageId, failure.message());
        SendResult result = SendResult.failure(failure.code(), failure.message(), failure.type(), messageId,
                baseMetadata()).withRetryAfter(failure.retryAfter());
        if (failure.invalidTarget()) {
            publish(publisher, messageId, result, target.field(), outcome);
        }
        return result;
    }

    /**
     * Send the same message to every token of {@code deviceTokens}.
     */
    SendResult sendMulti(ObjectNode message, Target target, DeliveryEventPublisher publisher) {
        List<Outcome> outcomes = sendAll(message, target);

        List<Map<String, Object>> results = new ArrayList<>(outcomes.size());
        int sent = 0;
        String firstName = null;
        for (Outcome outcome : outcomes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RESULT_TOKEN_HASH, outcome.targetHash());
            row.put(RESULT_STATUS, outcome.sent() ? STATUS_SENT : STATUS_FAILED);
            if (outcome.sent()) {
                sent++;
                row.put(RESULT_NAME, outcome.name());
                if (firstName == null) {
                    firstName = outcome.name();
                }
            } else {
                row.put(RESULT_ERROR_CODE, outcome.failure().code());
            }
            results.add(Map.copyOf(row));
        }
        int failed = outcomes.size() - sent;
        Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata() == null ? Map.of() : baseMetadata());
        metadata.put(METADATA_RESULTS, List.copyOf(results));
        metadata.put(METADATA_SUCCESS_COUNT, sent);
        metadata.put(METADATA_FAILURE_COUNT, failed);

        String messageId = firstName != null ? firstName : localId();
        SendResult result = aggregate(outcomes, sent, failed, messageId, metadata);
        if (failed > 0) {
            log.warn("FCM multi-token send: {} of {} tokens sent, {} failed, policy={}, result={}, messageId={}",
                    sent, outcomes.size(), failed, settings.multiTokenPolicy(),
                    result.success() ? "success" : result.failureType(), messageId);
        } else {
            log.debug("FCM multi-token send: all {} tokens sent, messageId={}", outcomes.size(), messageId);
        }
        // After the join and on the calling thread: listeners may read thread-bound state.
        for (Outcome outcome : outcomes) {
            if (!outcome.sent() && outcome.failure().invalidTarget()) {
                publish(publisher, messageId, result, target.field(), outcome);
            }
        }
        return result;
    }

    private SendResult aggregate(List<Outcome> outcomes, int sent, int failed, String messageId,
                                 Map<String, Object> metadata) {
        if (failed == 0 || (sent > 0 && settings.multiTokenPolicy() == FcmSettings.MultiTokenPolicy.ANY)) {
            return SendResult.success(messageId, Map.copyOf(metadata));
        }
        List<FcmFailure> failures = outcomes.stream().filter(o -> !o.sent()).map(Outcome::failure).toList();
        Set<String> codes = new LinkedHashSet<>();
        failures.forEach(f -> codes.add(f.code()));
        String summary = failed + " of " + outcomes.size() + " tokens failed " + codes;
        if (sent > 0) {
            return SendResult.failure(CODE_PARTIAL_FAILURE, summary + "; " + sent + " were sent, so the request is"
                    + " not retried (multi-token-policy: all)", FailureType.AMBIGUOUS, messageId, Map.copyOf(metadata));
        }
        String code = codes.size() == 1 ? codes.iterator().next() : CODE_ALL_TOKENS_FAILED;
        if (failures.stream().allMatch(f -> f.type() == FailureType.TRANSIENT)) {
            Duration retryAfter = failures.stream().map(FcmFailure::retryAfter).filter(Objects::nonNull)
                    .max(Duration::compareTo).orElse(null);
            return SendResult.failure(code, summary, FailureType.TRANSIENT, messageId, Map.copyOf(metadata))
                    .withRetryAfter(retryAfter);
        }
        FailureType type = failures.stream().anyMatch(f -> f.type() == FailureType.AMBIGUOUS)
                ? FailureType.AMBIGUOUS
                : FailureType.PERMANENT;
        return SendResult.failure(code, summary, type, messageId, Map.copyOf(metadata));
    }

    private List<Outcome> sendAll(ObjectNode message, Target target) {
        List<Outcome> outcomes = new ArrayList<>(target.values().size());
        boolean interrupted = false;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Outcome>> futures = new ArrayList<>(target.values().size());
            for (String value : target.values()) {
                futures.add(executor.submit(() -> sendOne(message, target, value)));
            }
            for (int i = 0; i < futures.size(); i++) {
                String hash = FcmJson.targetHash(target.values().get(i));
                if (interrupted) {
                    futures.get(i).cancel(true);
                    outcomes.add(new Outcome(hash, null, FcmFailure.of(FailureType.AMBIGUOUS, CODE_INTERRUPTED,
                            "interrupted before the result of this token was known")));
                    continue;
                }
                try {
                    outcomes.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    // Restore the flag now; the rest of the batch is then reported without waiting.
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    outcomes.add(new Outcome(hash, null, FcmFailure.of(FailureType.AMBIGUOUS, CODE_INTERRUPTED,
                            "interrupted before the result of this token was known")));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(hash, null, FcmFailure.of(FailureType.UNKNOWN,
                            FcmErrorClassifier.CODE_TRANSPORT_ERROR,
                            "the send task failed: " + e.getCause().getClass().getSimpleName())));
                }
            }
        }
        return outcomes;
    }

    /** One call under a permit, with the inline resend after a rejected access token. */
    Outcome sendOne(ObjectNode message, Target target, String value) {
        String hash = target.isDevice() ? FcmJson.targetHash(value) : null;
        boolean acquired;
        try {
            acquired = permits.tryAcquire(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(hash, null, FcmFailure.of(FailureType.TRANSIENT, CODE_CONCURRENCY_LIMIT,
                    "interrupted while waiting for one of the " + settings.concurrency() + " FCM send slots"));
        }
        if (!acquired) {
            return new Outcome(hash, null, FcmFailure.of(FailureType.TRANSIENT, CODE_CONCURRENCY_LIMIT,
                    "all " + settings.concurrency() + " FCM send slots of this tenant stayed busy for "
                            + settings.timeout() + " ('" + FcmSettings.CONCURRENCY + "')"));
        }
        try {
            byte[] body = FcmJson.write(FcmMessageMapper.envelope(
                    FcmMessageMapper.addressed(message, target.field(), value), settings.validateOnly()));
            Outcome outcome = call(body, target, value, hash);
            if (!outcome.sent() && outcome.failure().refreshToken()) {
                log.debug("FCM rejected the access token; fetching a new one and resending once");
                tokens.invalidate();
                outcome = call(body, target, value, hash);
            }
            return outcome;
        } finally {
            permits.release();
        }
    }

    private Outcome call(byte[] body, Target target, String value, String hash) {
        FcmAccessToken token;
        try {
            token = tokens.token();
        } catch (FcmAuthenticationException e) {
            return new Outcome(hash, null, FcmFailure.of(e.failureType(), FcmAuthenticationException.ERROR_CODE,
                    scrub(e.getMessage(), value, hash)));
        } catch (RuntimeException e) {
            return new Outcome(hash, null, FcmFailure.of(FailureType.UNKNOWN, FcmAuthenticationException.ERROR_CODE,
                    "the FCM access token provider failed: " + e.getClass().getSimpleName()));
        }
        FcmHttpRequest request = new FcmHttpRequest("POST", sendUri,
                Map.of("Authorization", "Bearer " + token.value(),
                        "Content-Type", "application/json; charset=UTF-8",
                        "Accept", "application/json"),
                body, settings.timeout());
        FcmFailure failure;
        try {
            FcmHttpResponse response = transport.execute(request);
            if (response.isSuccess()) {
                String name = FcmJson.readObject(response.body()).map(b -> FcmJson.text(b, "name")).orElse(null);
                return new Outcome(hash, name != null ? name : localId(), null);
            }
            failure = FcmErrorClassifier.classify(response, target.isDevice(), clock);
            log.debug("FCM answered HTTP {}: {}, code={}, type={}", response.statusCode(),
                    describe(target, hash), failure.code(), failure.type());
        } catch (FcmTransportException e) {
            failure = FcmErrorClassifier.classify(e, settings.timeoutClassification());
        } catch (RuntimeException e) {
            failure = FcmErrorClassifier.classify(e);
        }
        return new Outcome(hash, null, new FcmFailure(failure.type(), failure.code(),
                scrub(failure.message(), value, hash), failure.retryAfter(), failure.invalidTarget(),
                failure.refreshToken()));
    }

    private void publish(DeliveryEventPublisher publisher, String messageId, SendResult result, String field,
                         Outcome outcome) {
        FcmFailure failure = outcome.failure();
        String targetType = FcmMessageMapper.TARGET_FID.equals(field)
                ? DeliveryEvents.TARGET_TYPE_FID
                : DeliveryEvents.TARGET_TYPE_TOKEN;
        DeliveryStatus status = DeliveryStatus.BOUNCED;
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(DeliveryEvents.ATTR_FAILURE, failure.code());
        attributes.put(DeliveryEvents.ATTR_ERROR_CODE, result.errorCode() != null ? result.errorCode() : failure.code());
        attributes.put(DeliveryEvents.ATTR_TARGET_TYPE, targetType);
        attributes.put(DeliveryEvents.ATTR_TOKEN_HASH, outcome.targetHash());
        DeliveryEvent event = new DeliveryEvent(clock.instant(), FcmSettings.PROVIDER_NAME, messageId,
                FcmJson.sha256Hex(messageId + "|" + outcome.targetHash() + "|" + status.name()),
                status, DeliveryEvents.REASON_INVALID_TARGET, attributes);
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("Delivery event publisher failed for FCM message {}: {}", messageId, e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> baseMetadata() {
        return settings.validateOnly() ? Map.of(METADATA_VALIDATE_ONLY, true) : null;
    }

    private String describe(Target target, String hash) {
        return switch (target.field()) {
            case FcmMessageMapper.TARGET_TOPIC -> "topic=" + target.values().getFirst();
            case FcmMessageMapper.TARGET_CONDITION -> "condition=(set)";
            default -> target.field() + "=" + (settings.logTokenHash() ? hash : "(hidden)");
        };
    }

    static String localId() {
        return LOCAL_ID_PREFIX + UUID.randomUUID();
    }

    /** Remove the raw target from text FCM or a transport produced, then mask recipient data. */
    private static String scrub(String text, String value, String hash) {
        if (text == null) {
            return null;
        }
        String out = text;
        if (value != null && !value.isEmpty() && hash != null) {
            out = out.replace(value, hash);
        }
        return PiiMasking.redact(out);
    }
}
