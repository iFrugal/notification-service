# Decision 27: Validate Before Dispatch

## Status: DECIDED

## Context

`DefaultNotificationService.send` renders the template late.
By the time it renders, it has looked up and claimed the idempotency key (DD-10), taken a rate-limit token (DD-12) and written the "received" audit record (DD-07).
A request whose template is missing, or whose data does not fit the template, therefore becomes:

- a stored `FAILED` attempt in the audit and idempotency records;
- a dead-letter entry (DD-13), which an operator cannot usefully replay because the same data fails the same way;
- a consumed rate-limit token;
- an HTTP 200 with status `FAILED` over REST, which clients that only check the status code treat as success.

The failure is deterministic and is the caller's to fix, so it should be refused like any other invalid request, before the service does any work on its behalf.
Changing that unconditionally would change what existing callers see, so it has to be opt-in in 1.x.

## Decision

### `TemplateRenderException`

A new exception in `notification-api`, `TemplateRenderException extends NotificationException`, carries:

| Field | Meaning |
|-------|---------|
| `getReason()` | `NOT_FOUND`, `INVALID_TEMPLATE`, `MODEL_INVALID` or `EMPTY_OUTPUT`; since 1.4.0 also `PARAMETER_INVALID` for a channel's parameter rules (DD-35) |
| `getErrorCode()` | `TEMPLATE_NOT_FOUND`, `TEMPLATE_INVALID`, `TEMPLATE_MODEL_INVALID` or `TEMPLATE_EMPTY_OUTPUT`; since 1.4.0 also `TEMPLATE_PARAMETER_INVALID` |
| `getTenantId()`, `getTemplateId()` | what was rendered; `null` when an id was rejected as unsafe, so it is never echoed |
| `getVariablePath()` | for `MODEL_INVALID`, the data-model path of the missing or invalid value, for example `order.items[2].price`; `null` when not determinable (DD-26) |
| `getStatusCode()` | 422 |

`TemplateNotFoundException` becomes its `NOT_FOUND` subclass and keeps its error code, its 404 status code and its constructors, and is still thrown where it was thrown before, so code that catches it is unaffected.

### `notification.template.validate-before-dispatch`

Default `false`, planned to become `true` in 2.0.

When `true`, `send` renders as its first step after enriching the request (resolving the request id, tenant and caller), before the idempotency lookup:

1. `NotificationTemplateEngine.renderAndValidate(request)` renders the template.
   For email it also refuses a blank subject (including a template without a `[SUBJECT]` section) and a body with neither text nor HTML, as `EMPTY_OUTPUT`.
2. On `TemplateRenderException`, the service increments `notification.template.validation.failed.total{channel, reason}` when metrics are on and rethrows to the caller.
   Nothing else happens: no idempotency lookup or claim, no rate-limit token, no audit record, no dead-letter entry, no `notification.sends.total`.
3. On success, the rendered content is passed down and used for dispatch; the template is not rendered a second time.

A request without a channel skips the step and fails on the dispatch path exactly as in 1.2.

When `false`, `send` does not call the new method at all and the flow is the 1.2 flow.
The only differences are the ones DD-26 brings to every render: a failure is a `TemplateRenderException`, so the `FAILED` response carries `TEMPLATE_INVALID` or `TEMPLATE_MODEL_INVALID` instead of `INTERNAL_ERROR`, and the dead-letter entry is `PERMANENT` instead of `UNKNOWN`.

### REST: HTTP 422

`GlobalExceptionHandler` maps `TemplateRenderException` to `422 Unprocessable Content`, in the style of the 429 body (DD-12):

```json
{
  "error": "TEMPLATE_MODEL_INVALID",
  "reason": "MODEL_INVALID",
  "templateId": "WELCOME",
  "path": "user.address.city",
  "message": "Template WELCOME for tenant acme could not be rendered: The following has evaluated to null or missing: ==> u.address.city [in template \"default/email/WELCOME.ftl\" at line 4, column 7]"
}
```

Every reason is a 422, `NOT_FOUND` included.
The handler logs one WARN with the message and no stack trace; the inherited `RESTExceptionHandler` would otherwise log an ERROR with the full stack trace.

### Kafka: reject, log once, commit

`NotificationKafkaListener` catches `TemplateRenderException`, logs one WARN with the key, partition, offset, error code, path and message (no stack trace), and returns, so the offset commits.
The message is not retried and is not dead-lettered; nothing was stored for it.
With the flag off, the Kafka path is unchanged: the failure is a stored `FAILED` attempt, as in 1.2.

### Batch and async

`sendBatch` calls `send` for each request, so a template failure stops the batch with the exception, as rate-limit and idempotency conflicts already do.
`sendAsync` completes exceptionally with it.

## Reasoning

### Why render before the idempotency lookup

The point is that a request that cannot render leaves no trace: no claim to release, no token to give back, no audit row to explain.
Rendering after the lookup but before the claim would save the render on a replay of a completed key, but would make a replay's outcome depend on whether the template still renders, in two different places in the code.
One step at the top of `send` is simpler to reason about.
The cost is a render for each replay of a completed key, and a replay whose template has since become invalid is refused with 422 rather than answered from the cache.

### Why throw rather than return a `REJECTED` response

A response object means "the service handled your notification"; every response is audited and, for failures, dead-lettered.
An exception is how the service already refuses requests it will not handle (`IdempotencyInProgressException` 409, `RateLimitExceededException` 429), and the REST and Kafka layers already know how to turn those into a status code or a log line.

### Why 422 for every reason, including `NOT_FOUND`

The endpoint exists and the request body is well formed; what it names cannot be rendered with its data.
A 404 from `POST /api/v1/notifications` would read as a wrong URL.
One status lets clients handle all template problems in one place and use `reason` to tell them apart.
`TemplateNotFoundException.getStatusCode()` stays 404 for code that reads it directly.

### Why Kafka commits instead of retrying or dead-lettering

The failure is deterministic: redelivering the same message fails the same way, and blocking the partition on it would stop every message behind it.
The dead-letter store holds attempts that were dispatched and can be replayed; this one never was, and replaying it would fail again.
The producer cannot see a response, so the WARN line and the validation-failure counter are where an operator finds it.

### Why only email is checked for empty output

An email without a subject or body is never intentional and providers handle it inconsistently.
For SMS, WhatsApp and push, what counts as empty depends on the provider (a push can carry only data), so the check would need per-channel rules that belong with the providers.

### Why the default stays `false` in 1.x

With the flag on, callers that send a bad template get an exception or a 422 instead of a 200 with `FAILED`, and nothing is stored for them.
That is the better contract, but it changes what existing callers see.

## Consequences

### Positive

- With the flag on, a broken template or a bad model is the caller's error, reported synchronously with a reason and the variable path, and costs nothing: no rate-limit token, no idempotency key, no audit row, no dead-letter entry.
- Valid requests render once, as before.
- Operators get a counter of refused requests by channel and reason.

### Negative

- With the flag on, a replay of a completed idempotency key renders the template again, and is refused if the template no longer renders.
- With the flag on, one bad request stops a `sendBatch` call.
- Requests refused this way leave no audit record; the WARN log line and the counter are the only trace.
- Empty output is only checked for email.

## Related Decisions

- [26-library-owned-freemarker-configuration.md](./26-library-owned-freemarker-configuration.md) - the engine that throws `TemplateRenderException` and works out the variable path.
- [10-idempotency.md](./10-idempotency.md) - the claim that a refused request no longer takes.
- [12-rate-limiting.md](./12-rate-limiting.md) - the token that a refused request no longer consumes, and the 429 body style.
- [13-retries-and-dlq.md](./13-retries-and-dlq.md) - the dead-letter store that a refused request no longer reaches.
- [22-metrics.md](./22-metrics.md) - the meter catalogue, now with `notification.template.validation.failed.total`.
