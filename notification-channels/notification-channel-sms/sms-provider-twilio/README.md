# sms-provider-twilio

SMS provider for [Twilio Programmable Messaging](https://www.twilio.com/docs/messaging).
Provider name `twilio` on the `SMS` channel.

The default instance initialises the process-wide Twilio SDK (`Twilio.init`) with its `account-sid` and `auth-token`, so all instances in one JVM share the account configured last.

## Testing your integration

`TwilioSmsProvider.withClient(twilioRestClient)` builds a provider that sends through the client you pass, for example a Mockito mock, and never calls `Twilio.init`.
Only the `from` number needs configuring; the instance can send right away.

```java
TwilioRestClient client = mock(TwilioRestClient.class);
when(client.getAccountSid()).thenReturn("ACtest");
when(client.getObjectMapper()).thenReturn(new ObjectMapper());
when(client.request(any(Request.class)))
        .thenReturn(new Response("{\"sid\":\"SM123\",\"status\":\"queued\"}", 201));

TwilioSmsProvider provider = TwilioSmsProvider.withClient(client);
provider.configure(Map.of("from", "+15557654321"));

SendResult result = provider.send(request, RenderedContent.text("Your code is 4711"));
// verify(client).request(captor.capture()); captor.getValue().getPostParams() holds To, From and Body
```

`withClient` also suits a JVM that needs more than one Twilio account, since it leaves the global SDK state alone.
