# email-provider-smtp

Email provider for any SMTP server (Gmail, Microsoft 365, Postfix and others), built on Jakarta Mail.
Provider name `smtp` on the `EMAIL` channel.

## Testing your integration

`SmtpEmailProvider.withSender(sender)` builds a provider that hands every finished `MimeMessage` to your `SmtpSender` instead of `Transport.send`, so a test can capture the message or simulate a server reply without an SMTP server.
`host` is optional for such an instance; configure the sender address as usual.

```java
List<MimeMessage> sent = new ArrayList<>();
SmtpEmailProvider provider = SmtpEmailProvider.withSender(sent::add);
provider.configure(Map.of("from-address", "noreply@example.com"));
provider.init();

SendResult result = provider.send(request, RenderedContent.email("Subject", "<p>Hi</p>", "Hi"));
// sent.get(0) is the message that would have gone to the server

SmtpEmailProvider rejecting = SmtpEmailProvider.withSender(message -> {
    throw new SendFailedException("550 5.1.1 Recipient address rejected");
});
```
