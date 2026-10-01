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
