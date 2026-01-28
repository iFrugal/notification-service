# Decision 01: Maven Module Structure

## Status: DECIDED

## Context
The notification service needs a modular architecture that:
1. Separates concerns (API, channels, providers, server)
2. Allows external developers to add channels/providers in separate repositories
3. Follows the pattern established in `all-about-persistence`
4. Avoids unnecessary API modules where not needed

## Decision
Adopt a granular module structure with SPI support for external extensibility.

```
notification-service/
├── pom.xml                              # Parent POM with BOM
├── notification-api/                    # Core interfaces, DTOs, annotations
├── notification-core/                   # Core logic, routing, template wrapper
├── notification-audit/                  # Optional audit persistence (uses persistence-api)
├── notification-kafka/                  # Kafka listener module
├── notification-rest/                   # REST API controllers
├── notification-channels/
│   ├── pom.xml                          # Parent for all channels
│   ├── notification-channel-email/
│   │   ├── pom.xml                      # Email channel common logic
│   │   ├── email-provider-ses/          # AWS SES implementation
│   │   ├── email-provider-smtp/         # Generic SMTP (Gmail, etc.)
│   │   └── email-provider-sendgrid/     # SendGrid implementation
│   ├── notification-channel-sms/
│   │   ├── pom.xml
│   │   ├── sms-provider-twilio/
│   │   └── sms-provider-sns/
│   ├── notification-channel-whatsapp/
│   │   ├── pom.xml
│   │   ├── whatsapp-provider-twilio/
│   │   └── whatsapp-provider-meta/
│   └── notification-channel-push/
│       ├── pom.xml
│       ├── push-provider-fcm/
│       └── push-provider-apns/
├── notification-spring-boot-starter/    # Auto-configuration for library use
└── notification-server/                 # Standalone Docker deployment
```

## Reasoning

### Why this structure:
1. **Follows `all-about-persistence` pattern**: `persistence-api` + `persistence-impls/{impl}` mirrors `notification-api` + `notification-channels/{channel}/{provider}`

2. **No unnecessary API modules per channel**: Channel-level API is only created if:
   - Multiple providers exist for that channel
   - External developers need to implement providers
   - Otherwise, interfaces live in `notification-api`

3. **External extensibility via SPI**:
   - Developers can create providers in separate repos
   - Just implement interfaces from `notification-api`
   - Add jar to classpath (`-cp`) or Maven dependency
   - Service discovers via Java SPI or Spring auto-configuration

4. **Clear dependency hierarchy**:
   ```
   notification-api (no dependencies on other modules)
        ↑
   notification-core (depends on api)
        ↑
   notification-channels/* (depends on api, optionally core)
        ↑
   notification-server/starter (assembles everything)
   ```

## Alternatives Considered

### Alternative 1: Flat structure (all channels in one module)
- **Rejected**: Too coupled, can't include only needed channels

### Alternative 2: API module per channel
- **Rejected**: Over-engineering when channel has single provider or interfaces are simple

## Consequences

### Positive
- Clean separation of concerns
- Easy to add new channels/providers
- External teams can extend without forking
- Minimal dependencies for consumers

### Negative
- More modules to maintain
- Need clear documentation for external developers

## Related Decisions
- [05-provider-registration.md](./05-provider-registration.md) - How providers are registered
- [06-spi-discovery.md](./06-spi-discovery.md) - How external providers are discovered
