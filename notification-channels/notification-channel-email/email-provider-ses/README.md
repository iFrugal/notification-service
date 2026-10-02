# email-provider-ses

Email provider for [Amazon SES](https://docs.aws.amazon.com/ses/) (SES v2 API).
Provider name `ses` on the `EMAIL` channel.

## Testing your integration

`SesEmailProvider.withClient(sesV2Client)` builds a provider that sends through the client you pass, for example a Mockito mock, so a test exercises the real request mapping and error classification without AWS.
`configure(...)` applies the sender settings as usual, `init()` keeps the given client, and `destroy()` does not close it.

```java
SesV2Client ses = mock(SesV2Client.class);
when(ses.sendEmail(any(SendEmailRequest.class)))
        .thenReturn(SendEmailResponse.builder().messageId("ses-1").build());

SesEmailProvider provider = SesEmailProvider.withClient(ses);
provider.configure(Map.of("from-address", "noreply@example.com"));
provider.init();

SendResult result = provider.send(request, RenderedContent.email("Subject", "<p>Hi</p>", "Hi"));
// verify(ses).sendEmail(captor.capture()) to inspect the SendEmailRequest
```

## Timeouts and duplicate sends

A read timeout or an API-call timeout is classified as `AMBIGUOUS`: SES may have accepted the message before the response was lost.
The service does not retry `AMBIGUOUS` failures by default; they go to the dead-letter store when one is configured, so an operator can check SES before replaying.
Connect failures and connect timeouts stay `TRANSIENT`, because the request never reached SES.

The AWS SDK retries failed calls itself before the provider sees an exception, and that includes resending after a read timeout.
So one send can still reach SES twice, even though the service does not retry it.
To leave retry decisions to the service alone, set the SDK's attempts to 1 (no SDK retries), for example with the environment variable `AWS_MAX_ATTEMPTS=1` or the JVM system property `-Daws.maxAttempts=1`.
These settings apply to every AWS SDK client in the JVM.
To limit the change to SES, build your own client with `.overrideConfiguration(c -> c.retryStrategy(AwsRetryStrategy.doNotRetry()))` and pass it to `SesEmailProvider.withClient(...)`.
