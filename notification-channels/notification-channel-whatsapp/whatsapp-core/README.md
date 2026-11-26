# whatsapp-core

The vendor-neutral part of the `WHATSAPP` channel: it turns a notification request into a WhatsApp template message that a WhatsApp provider sends.
Available since 1.4.0.
See [DD-35](../../../docs/design-decisions/35-whatsapp-channel-and-meta-provider.md).

You normally get it through a provider module, for example [`whatsapp-provider-meta`](../whatsapp-provider-meta/README.md):

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>whatsapp-provider-meta</artifactId>
    <version>${notification-service.version}</version>
</dependency>
```

## Why templates, not FreeMarker

A business-initiated WhatsApp message is a template approved at the provider, addressed by name and language, with positional parameters.
There is nothing to render: the library has to pick the approved template and fill its parameters in the right order.
This module registers a `ChannelContentRenderer` for `WHATSAPP`, so the send pipeline asks it instead of the FreeMarker engine; every other channel is unchanged.

## Template bindings

Map each logical template id your callers use onto the approved template:

```yaml
notification:
  whatsapp:
    templates:
      default:                       # tenant id, or "default" for every tenant
        order-shipped:               # logical id: request.templateId, else request.notificationType
          name: order_shipped        # the name approved at the provider
          language: en               # or en_US, pt_BR, ...
          header: [carrier]          # templateData keys filling the header {{1}}
          body: [customerName, orderNumber]   # templateData keys filling {{1}}, {{2}}, ...
          buttons:
            - index: 0               # the button's position in the template, 0 to 9
              sub-type: url          # url, quick_reply or copy_code
              variable: trackingToken
      acme:                          # a tenant's own entry wins over "default"
        order-shipped:
          name: acme_order_shipped
          language: en_US
          body: [customerName, orderNumber]
```

A request then only carries data:

```json
{
  "channel": "WHATSAPP",
  "notificationType": "order-shipped",
  "recipient": { "type": "WHATSAPP", "phoneNumber": "+15550100001" },
  "templateData": { "carrier": "Parcel Co", "customerName": "Jane", "orderNumber": 1042, "trackingToken": "abc123" }
}
```

An incomplete entry (no `name`, no `language`, a blank variable, a button without `index`) fails the startup, naming the property.
To keep the bindings somewhere else, such as a database, declare a `WhatsAppTemplateRegistry` bean; it replaces the YAML one.

## Resolution order

1. A `WhatsAppRecipient` with `whatsappTemplateName` set is sent as it says: its `languageCode` (required) and its `headerParameters`, `bodyParameters` and `buttonParameters`.
   Each `buttonParameters` entry is a map with `index` (defaults to its position), `sub_type` (defaults to `url`) and the value under `text`, `payload`, `coupon_code` or `value`.
2. Otherwise the binding of `templateId` (or `notificationType`) for the request's tenant, then for `default`.
3. Otherwise the FreeMarker template engine, as before 1.4.0; it fails with `TEMPLATE_NOT_FOUND` when there is no `.ftl` either.

## Parameter rules

Every parameter value must satisfy WhatsApp's rules, checked before the provider is called:

- present and not blank;
- no new line, carriage return or tab;
- no run of 5 or more spaces;
- at most 1024 characters;
- text, a number, a boolean, an enum or a date/time (not a list or a map).

A violation is a `TemplateRenderException`, the exception the template engine throws since 1.3.0:

| Problem | `reason` | `errorCode` |
|---------|----------|-------------|
| The value is missing, or not text (a list or a map) | `MODEL_INVALID` | `TEMPLATE_MODEL_INVALID` |
| The value is blank, has a new line or a tab, a run of spaces, too many characters, or a button index is not 0 to 9 | `PARAMETER_INVALID` | `TEMPLATE_PARAMETER_INVALID` |

Its `variablePath` names the field to fix: the `templateData` key (`customerName`) for a binding, or the recipient field (`recipient.languageCode`, `recipient.bodyParameters[0]`, `recipient.buttonParameters[1].index`) for a recipient that names its template.
The message names the template and the variable, never the value.
With `notification.template.validate-before-dispatch: true` the exception is thrown to the caller before the idempotency claim, the rate limit and the audit record, so nothing is stored: REST answers 422 with `error`, `reason`, `templateId` and `path`, and the Kafka listener logs one WARN and commits the offset.
With it off the send returns a `FAILED` response with that `errorCode`.

## What providers receive

`RenderedContent.payload(WhatsAppTemplateMessage.class)` holds the resolved template name, language, header and body parameters and button parameters.
`WhatsAppTemplateMessage.toString()` shows counts, never values.

## Replacing pieces

| Bean | Replaces |
|------|----------|
| a `WhatsAppTemplateRegistry` | the YAML registry |
| a bean named `whatsAppContentRenderer` | the `WHATSAPP` renderer |

A second `ChannelContentRenderer` for `WHATSAPP` under another name fails the startup, naming both classes.

## GraalVM native image

`WhatsAppRuntimeHints` registers the binding of `WhatsAppProperties`; nothing else here is reflective.
