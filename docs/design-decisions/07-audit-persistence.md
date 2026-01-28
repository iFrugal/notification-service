# Decision 07: Audit Persistence

## Status: DECIDED

## Context
Need optional audit trail for all notifications:
1. Track request received, processing, sent, delivered, failed
2. Support multiple storage backends
3. Leverage existing `persistence-api` from `all-about-persistence`
4. Tenant-scoped audit records

## Decision
Use `persistence-api` interfaces with pluggable implementations. The actual storage backend is determined by which `persistence-impl` jar is on the classpath.

### Audit Entity

```java
public class NotificationAudit {
    String id;                     // Unique audit ID
    String requestId;              // Original request ID
    String correlationId;          // Cross-system tracking
    String tenantId;               // Tenant scope

    // Request Details
    String notificationType;
    Channel channel;
    String provider;
    String recipientSummary;       // Masked recipient (e.g., "j***@example.com")

    // Status Tracking
    NotificationStatus status;
    String providerMessageId;
    String errorCode;
    String errorMessage;

    // Timestamps
    Instant receivedAt;
    Instant processedAt;
    Instant sentAt;
    Instant deliveredAt;           // If delivery confirmation available

    // Metadata
    Map<String, String> metadata;  // Custom metadata from request
    String templateId;             // Which template was used

    // Request/Response (optional, configurable)
    String requestPayload;         // Full request (if enabled)
    String responsePayload;        // Provider response (if enabled)
}
```

### Audit Service

```java
public interface NotificationAuditService {

    /**
     * Record initial request receipt
     */
    NotificationAudit recordReceived(NotificationRequest request);

    /**
     * Update status during processing
     */
    NotificationAudit updateStatus(String requestId, NotificationStatus status,
                                    String providerMessageId, String errorCode, String errorMessage);

    /**
     * Query audit records
     */
    Page<NotificationAudit> findByTenant(String tenantId, Instant from, Instant to, Pageable pageable);

    Page<NotificationAudit> findByCorrelationId(String correlationId, Pageable pageable);

    Optional<NotificationAudit> findByRequestId(String requestId);
}
```

### Implementation using persistence-api

```java
@Service
@ConditionalOnProperty(prefix = "notification.audit", name = "enabled", havingValue = "true")
public class PersistenceApiAuditService implements NotificationAuditService {

    private final GeneralAppender<Map<String, Object>> appender;
    private final GeneralReader<Map<String, Object>> reader;
    private final SerDe serDe = SerDe.JSON;

    public PersistenceApiAuditService(
            GeneralAppender<Map<String, Object>> appender,
            GeneralReader<Map<String, Object>> reader) {
        this.appender = appender;
        this.reader = reader;
    }

    @Override
    public NotificationAudit recordReceived(NotificationRequest request) {
        NotificationAudit audit = NotificationAudit.builder()
            .id(UUID.randomUUID().toString())
            .requestId(request.getRequestId())
            .correlationId(request.getCorrelationId())
            .tenantId(TenantContext.getTenantId())
            .notificationType(request.getNotificationType())
            .channel(request.getChannel())
            .recipientSummary(maskRecipient(request.getRecipient()))
            .status(NotificationStatus.ACCEPTED)
            .receivedAt(Instant.now())
            .metadata(request.getMetadata())
            .build();

        appender.append(serDe.toMap(audit));
        return audit;
    }

    @Override
    public NotificationAudit updateStatus(String requestId, NotificationStatus status,
                                           String providerMessageId, String errorCode, String errorMessage) {
        // Use GeneralUpdater to update the record
        // Implementation depends on underlying persistence
    }
}
```

### Configuration

```yaml
notification:
  audit:
    enabled: true                    # Enable/disable audit
    store-request-payload: false     # Store full request (privacy consideration)
    store-response-payload: false    # Store provider response
    retention-days: 90               # For cleanup jobs
    async: true                      # Async write for performance

    # Persistence configuration (uses persistence-api)
    persistence:
      type: mongodb                  # mongodb, jdbc, file, etc.
      collection: notification_audit # For MongoDB
      # table: notification_audit    # For JDBC
```

### Storage Backend Selection

The actual implementation is determined by the `persistence-impl` jar on classpath:

| Backend | Dependency | Use Case |
|---------|-----------|----------|
| MongoDB | `mongodb-persistence` | Flexible schema, high write throughput |
| JDBC | `simple-jdbc-persistence` | Relational DB, SQL queries |
| File | `file-persistence` | Development, debugging |
| Elasticsearch | (future) | Full-text search, analytics |

```xml
<!-- pom.xml - Choose your backend -->
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>mongodb-persistence</artifactId>
    <version>${persistence.version}</version>
</dependency>
```

### No-Op Implementation (Audit Disabled)

```java
@Service
@ConditionalOnProperty(prefix = "notification.audit", name = "enabled",
                       havingValue = "false", matchIfMissing = true)
public class NoOpAuditService implements NotificationAuditService {

    @Override
    public NotificationAudit recordReceived(NotificationRequest request) {
        return null; // No-op
    }

    @Override
    public NotificationAudit updateStatus(...) {
        return null; // No-op
    }

    // ... other methods return empty results
}
```

## Reasoning

### Why persistence-api:
1. **Already exists**: Proven abstraction in `all-about-persistence`
2. **Pluggable**: Swap backends without code changes
3. **Consistent**: Same pattern used across projects
4. **Tested**: MongoDB, JDBC, file implementations ready

### Why Optional:
1. **Not all deployments need audit**: Starter jar use case may skip
2. **Performance**: Audit adds latency if not needed
3. **Privacy**: Some orgs may not want to store notification data

### Why Async Option:
1. **Performance**: Don't block notification sending for audit write
2. **Reliability**: Audit failure shouldn't fail notification
3. **Configurable**: Sync for compliance-heavy environments

### Why Mask Recipient:
1. **Privacy**: Don't store full email/phone in logs
2. **Compliance**: GDPR, HIPAA considerations
3. **Debugging**: Still useful for identifying issues

## Alternatives Considered

### Alternative 1: Direct MongoDB/JPA dependency
- **Rejected**: Couples to specific storage, loses flexibility

### Alternative 2: Event-based (publish audit events)
- **Considered for future**: Could add as additional option
- Current approach is simpler for MVP

### Alternative 3: Separate audit service
- **Rejected for MVP**: Over-engineering for initial version
- Could extract later if needed

## Consequences

### Positive
- Flexible storage backend
- Optional feature (zero overhead when disabled)
- Reuses proven persistence abstraction
- Tenant-scoped queries

### Negative
- Depends on `persistence-api` and at least one impl
- Need to ensure consistency between notification send and audit

## Related Decisions
- [03-multi-tenancy.md](./03-multi-tenancy.md) - Tenant scoping for audit records
