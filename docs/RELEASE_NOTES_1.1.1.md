# notification-service 1.1.1

## Highlights

- **Recipient data is masked in logs.**
  The built-in providers and the core service no longer write raw email addresses, phone numbers or subjects to their log lines, and provider error messages are masked before they are logged or returned.
- **No duplicate ACS email after acceptance.**
  `email-provider-acs` sends a deterministic `Operation-Id` with every message and never resends a message that ACS has already accepted.

## Fixes

- **Recipient data in logs.**
  The SMTP, SES, ACS and Twilio providers logged the full recipient address or number at `DEBUG` and `ERROR`, the SMTP provider also logged the subject, and provider exception messages (which often quote the address) were logged and returned unmasked.
  Every such line now uses the new `PiiMasking` helper: `john@example.com` is logged as `j***@example.com`, `+15551234590` as `+1***90`.
  `SendResult.errorMessage` from the built-in providers is masked at the source, so the response, the audit record and the dead-letter entry carry the masked text.
  `DefaultNotificationService` and `RetryExecutor` mask the provider error text they log.
  `NotificationAudit.recipientSummary` is now filled with `PiiMasking.mask(recipient)` (for example `to=j***@example.com cc=2 bcc=0`) when the audit implementation leaves it empty.
- **ACS: duplicate email after a wait timeout.**
  In `wait` mode a timeout after ACS had accepted the message was a `TRANSIENT` failure, so the `RetryExecutor` sent the message again and the recipient could receive it twice.
  A wait timeout or a failed status poll is now a success with `providerMetadata.acsStatus=UNCONFIRMED` and the operation id as the message id, and is never retried.
  See [Idempotency and ambiguous results](../notification-channels/notification-channel-email/email-provider-acs/README.md#idempotency-and-ambiguous-results).
- **ACS: deterministic operation id.**
  The provider derives the ACS operation id from the tenant id, the request id and a fingerprint of the message, and sends it as the `Operation-Id` header.
  Retries of the same message, by the `RetryExecutor` or the Azure SDK, reuse the same id; batch items that share a request id get different ids.
  `submit` mode no longer makes an extra status call to learn the id.
- **Failed sends keep the provider message id.**
  `NotificationResponse.failed(...)` always dropped the provider message id.
  `DefaultNotificationService` now keeps `SendResult.messageId` on failures, so an ACS failure that already has an operation id shows it in the response, the audit record and the dead-letter entry.

## Compatibility

Fully backward compatible with 1.1.0: no public signature was removed or changed, and no record constructor changed.
Additive public API:

- `com.lazydevs.notification.api.util.PiiMasking` with `maskEmail`, `maskPhone`, `mask(Recipient)` and `redact`.
- `SendResult.failure(String errorCode, String errorMessage, FailureType failureType, String messageId)`.
- `NotificationResponse.failed(NotificationRequest, String provider, String providerMessageId, String errorCode, String errorMessage, Instant receivedAt)`.
- `AcsEmailGateway.send(EmailMessage, UUID operationId)`, a default method that delegates to `send(EmailMessage)`, so existing gateway implementations compile and behave as before.
- `AcsSendOutcome.Status.UNCONFIRMED`.
  `SdkAcsEmailGateway` reports it instead of `TIMED_OUT`, and `AcsEmailProvider` treats both the same way.
  An exhaustive `switch` over `AcsSendOutcome.Status` in your own code needs a branch for it.

Behaviour changes to be aware of:

- Log lines and provider error messages show masked recipients; adjust log searches that matched full addresses.
- An ACS send that timed out in `wait` mode is reported as `SENT`, not `FAILED`, and is not retried.

## Known limitations

- Only the ACS provider reports ambiguous results (`UNCONFIRMED`).
  SES, SMTP and Twilio still classify a timeout after the request was sent as `TRANSIENT`, so a retry can duplicate the message; an `AMBIGUOUS` failure type for them is planned for 1.2.
- Microsoft does not document what ACS does when the same `Operation-Id` is submitted twice.
  The provider never resubmits an accepted message, but if you rely on ACS-side deduplication of a resubmission, verify it on your own Communication Services resource.
- `UNCONFIRMED` is visible in `SendResult.providerMetadata` and the provider's `WARN` log line; `NotificationResponse` reports `SENT`.
  Reconcile with ACS delivery reports or the operation id.
