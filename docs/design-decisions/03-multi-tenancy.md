# Decision 03: Multi-Tenancy Approach

## Status: DECIDED

## Context
The notification service must support multiple tenants with:
1. Tenant-specific configurations (channels, providers, credentials)
2. Tenant-specific templates
3. Isolated audit trails
4. A default tenant for single-tenant deployments

## Decision
Use **Header-based tenant identification** with `X-Tenant-Id` header.

### REST API
- Gateway/API layer adds `X-Tenant-Id` header
- If header missing, use configured default tenant
- Tenant ID propagated via `TenantContext` (ThreadLocal)

### Kafka Consumer
- Tenant ID in Kafka message header: `X-Tenant-Id`
- Set in `TenantContext` before processing each message
- Clear context after processing

### Implementation

```java
// From persistence-utils - reuse existing implementation
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
```

### REST Filter

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter implements Filter {

    @Value("${notification.default-tenant:default}")
    private String defaultTenant;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String tenantId = httpRequest.getHeader("X-Tenant-Id");

        if (tenantId == null || tenantId.isBlank()) {
            tenantId = defaultTenant;
        }

        try {
            TenantContext.setTenantId(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

### Kafka Listener

```java
@KafkaListener(topics = "${notification.kafka.topic}")
public void handleNotification(
        @Payload NotificationRequest request,
        @Header(value = "X-Tenant-Id", required = false) String tenantId) {

    try {
        TenantContext.setTenantId(tenantId != null ? tenantId : defaultTenant);
        notificationService.process(request);
    } finally {
        TenantContext.clear();
    }
}
```

### Tenant Configuration Structure

```yaml
notification:
  default-tenant: "default"

  tenants:
    default:
      channels:
        email:
          enabled: true
          default-provider: smtp
          providers:
            smtp:
              host: smtp.gmail.com
              # ...

    tenant-a:
      channels:
        email:
          enabled: true
          default-provider: ses
          providers:
            ses:
              region: us-east-1
              # ...
```

## Reasoning

### Why Header-based:
1. **Gateway-friendly**: Most API gateways can inject tenant from JWT/session
2. **Transparent to business logic**: Services don't need to parse auth tokens
3. **Works with Kafka**: Headers are natural for message metadata
4. **Consistent with `persistence-utils`**: Reuses existing `TenantContext`

### Why ThreadLocal:
1. **Already proven**: Used in `persistence-utils`
2. **Simple**: No need for reactive context for this use case
3. **Works with Spring**: Most Spring services are request-scoped

### Why Default Tenant:
1. **Simplifies single-tenant deployments**: No config needed
2. **Backward compatible**: Existing callers without header still work
3. **Development friendly**: Easy local testing

## Alternatives Considered

### Alternative 1: Path-based (`/api/v1/tenants/{tenantId}/notifications`)
- **Rejected**:
  - More verbose URLs
  - Doesn't work naturally with Kafka
  - Requires path rewriting in gateway

### Alternative 2: JWT claim extraction
- **Rejected**:
  - Couples notification service to auth implementation
  - Doesn't work for service-to-service calls without user context
  - Header approach is more flexible (gateway can extract from JWT)

### Alternative 3: Request body field
- **Rejected**:
  - Mixes routing concern with business data
  - Harder to filter/route in API gateway
  - Requires body parsing before routing

## Consequences

### Positive
- Clean separation of tenant identification from business logic
- Reuses existing `TenantContext` from `persistence-utils`
- Works uniformly across REST and Kafka
- Gateway can handle tenant extraction from various sources

### Negative
- Requires all callers to set header (or accept default)
- ThreadLocal requires careful cleanup (handled in filter/listener)

## Related Decisions
- [07-audit-persistence.md](./07-audit-persistence.md) - Tenant-scoped audit records
