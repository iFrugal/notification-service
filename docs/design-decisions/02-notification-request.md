# Decision 02: Notification Request Structure

## Status: DECIDED

## Context
Need a unified request structure that:
1. Supports all channels (email, SMS, WhatsApp, push)
2. Enables multi-tenancy
3. Provides template data for rendering
4. Supports routing to specific providers
5. Works with both REST and Kafka inputs

## Decision

### Core Request Structure

```java
public class NotificationRequest {

    // === Identification ===
    String requestId;              // Unique request ID (auto-generated if null)
    String correlationId;          // For tracking across systems
    String tenantId;               // Multi-tenancy (from header if not provided)

    // === Routing ===
    String notificationType;       // e.g., "ORDER_CONFIRMATION", "PASSWORD_RESET"
    Channel channel;               // EMAIL, SMS, WHATSAPP, PUSH
    String provider;               // Optional: specific provider, null = use default

    // === Recipient ===
    Recipient recipient;           // Channel-specific recipient info

    // === Content ===
    Map<String, Object> templateData;  // Data for template rendering
    String templateId;             // Optional: explicit template ID (overrides type-based lookup)

    // === Metadata ===
    Map<String, String> metadata;  // Custom metadata for audit/tracking
    Priority priority;             // HIGH, NORMAL, LOW

    // === Scheduling (Future) ===
    Instant scheduledAt;           // Optional: send at specific time (null = immediate)
}

public enum Channel {
    EMAIL, SMS, WHATSAPP, PUSH
}

public enum Priority {
    HIGH, NORMAL, LOW
}
```

### Recipient Structure (Channel-Specific)

```java
public abstract class Recipient {
    String id;                     // Optional recipient ID for tracking
}

public class EmailRecipient extends Recipient {
    String to;                     // Primary recipient
    List<String> cc;               // CC recipients
    List<String> bcc;              // BCC recipients
    String replyTo;                // Reply-to address
}

public class SmsRecipient extends Recipient {
    String phoneNumber;            // E.164 format: +1234567890
}

public class WhatsAppRecipient extends Recipient {
    String phoneNumber;            // E.164 format
    String templateName;           // WhatsApp-approved template name (required by WhatsApp)
}

public class PushRecipient extends Recipient {
    String deviceToken;            // FCM/APNS token
    String topic;                  // Optional: topic for broadcast
    Map<String, String> data;      // Additional data payload
}
```

### Response Structure

```java
public class NotificationResponse {
    String requestId;
    String correlationId;
    NotificationStatus status;     // ACCEPTED, SENT, DELIVERED, FAILED
    String providerMessageId;      // ID from the actual provider
    String errorCode;              // If failed
    String errorMessage;           // If failed
    Instant processedAt;
}

public enum NotificationStatus {
    ACCEPTED,      // Request received, queued for processing
    PROCESSING,    // Being processed
    SENT,          // Sent to provider
    DELIVERED,     // Confirmed delivered (if provider supports)
    FAILED,        // Failed to send
    REJECTED       // Validation failed
}
```

## Reasoning

### Why `notificationType` + `templateId`:
- `notificationType` maps to business events (ORDER_CONFIRMATION)
- Template lookup: `{tenantId}/{channel}/{notificationType}.ftl`
- `templateId` allows explicit override when needed

### Why `provider` is optional:
- Default provider configured per tenant per channel
- Explicit provider for special cases (e.g., high-priority via different provider)

### Why separate Recipient classes:
- Each channel has unique recipient requirements
- Type safety prevents invalid combinations
- Easy to extend for new channels

### Why `priority`:
- Allows different handling (e.g., HIGH = synchronous, NORMAL = async queue)
- Can influence provider selection

## Examples

### Email Request
```json
{
  "notificationType": "ORDER_CONFIRMATION",
  "channel": "EMAIL",
  "recipient": {
    "to": "customer@example.com",
    "cc": ["support@example.com"]
  },
  "templateData": {
    "customerName": "John Doe",
    "orderId": "ORD-12345",
    "items": [
      {"name": "Widget", "qty": 2, "price": 29.99}
    ],
    "total": 59.98
  },
  "priority": "NORMAL"
}
```

### SMS Request
```json
{
  "notificationType": "OTP",
  "channel": "SMS",
  "recipient": {
    "phoneNumber": "+1234567890"
  },
  "templateData": {
    "otp": "123456",
    "expiryMinutes": 5
  },
  "priority": "HIGH"
}
```

## Alternatives Considered

### Alternative 1: Single flat recipient with all fields
- **Rejected**: Leads to nullable fields, validation complexity

### Alternative 2: Generic Map for recipient
- **Rejected**: No type safety, harder to validate

## Consequences

### Positive
- Clear contract for all channels
- Type-safe recipient handling
- Extensible for new channels
- Good for both sync (REST) and async (Kafka) patterns

### Negative
- Need JSON polymorphic deserialization for Recipient
- Slightly more complex than flat structure

## Related Decisions
- [04-template-engine.md](./04-template-engine.md) - How templates are rendered
