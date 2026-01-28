# Decision 06: External Provider Discovery

## Status: DECIDED (Clarified)

## Context
External developers should be able to:
1. Create channel providers in separate repositories
2. Add jar to classpath and have it work
3. Configure via YAML without code changes to notification-service

## Clarification of Original Questions

### Question 3: "SPI (Java ServiceLoader) + Spring beans hybrid?"

**What this means:**
- **Java SPI (ServiceLoader)**: A standard Java mechanism where you put a file in `META-INF/services/` listing implementations. Java auto-discovers them at runtime.
- **Spring beans**: Classes annotated with `@Component`/`@Service` that Spring manages.

**Question was:** Should we use both mechanisms to discover providers, or just one?

### Question 4: "Should providers have lifecycle methods (init(), destroy())?"

**What this means:**
- Should provider interface have methods like:
  ```java
  interface NotificationProvider {
      void init();      // Called after construction + configuration
      void destroy();   // Called on shutdown (cleanup connections, etc.)
  }
  ```
- Useful for: connection pools, client initialization, resource cleanup

---

## Decision

**We do NOT need Java SPI**. The `beanName` + `fqcn` approach from Decision 05 is sufficient.

### Why No SPI Needed

| Approach | How it works | Our Use |
|----------|--------------|---------|
| **Java SPI** | Auto-discovers all implementations on classpath | ❌ Not needed - we explicitly configure providers in YAML |
| **beanName** | Look up Spring bean by name | ✅ For Spring-managed providers |
| **fqcn** | Instantiate via reflection | ✅ For non-Spring providers |

With `beanName` + `fqcn`, we have explicit control:
- User specifies exactly which providers to use in YAML
- No "magic" auto-discovery that might load unwanted providers
- Clear fail-fast if provider not found

### Lifecycle Methods: Yes, with Simple Contract

```java
/**
 * Base interface for all notification providers.
 */
public interface NotificationProvider {

    /**
     * Called after construction and configuration.
     * Use for: initializing clients, connection pools, validating config.
     * Default implementation does nothing.
     */
    default void init() {
        // Override if needed
    }

    /**
     * Called on application shutdown.
     * Use for: closing connections, cleanup resources.
     * Default implementation does nothing.
     */
    default void destroy() {
        // Override if needed
    }

    /**
     * Configure provider with properties from YAML.
     * Called before init().
     */
    void configure(Map<String, Object> properties);
}
```

### Provider Lifecycle

```
1. Instantiate (via beanName lookup OR fqcn reflection)
       ↓
2. configure(properties)  ← Pass YAML properties
       ↓
3. init()                 ← Provider initializes resources
       ↓
4. [Provider is ready for use]
       ↓
5. destroy()              ← On shutdown, cleanup
```

### Implementation in ProviderResolver

```java
private <T extends NotificationProvider> T instantiateAndConfigure(
        Class<?> clazz,
        ProviderConfig config,
        Class<T> providerInterface) {

    // 1. Instantiate
    T provider = /* ... instantiation logic ... */;

    // 2. Configure with properties
    provider.configure(config.getProperties());

    // 3. Initialize
    provider.init();

    // 4. Register for shutdown
    registerForDestroy(provider);

    return provider;
}

@PreDestroy
public void shutdownProviders() {
    allProviders.forEach(provider -> {
        try {
            provider.destroy();
        } catch (Exception e) {
            log.warn("Error destroying provider: {}", provider, e);
        }
    });
}
```

---

## How External Developers Add Providers

### Option A: As Spring Bean (Recommended for Spring users)

```java
// In external jar
@Component("acmeMailerProvider")  // beanName
public class AcmeMailerProvider implements EmailProvider {

    private AcmeClient client;

    @Override
    public void configure(Map<String, Object> properties) {
        String apiKey = (String) properties.get("api-key");
        String endpoint = (String) properties.get("endpoint");
        this.client = new AcmeClient(apiKey, endpoint);
    }

    @Override
    public void init() {
        client.connect();
    }

    @Override
    public void destroy() {
        client.disconnect();
    }

    @Override
    public SendResult send(EmailMessage message) {
        return client.sendEmail(message);
    }
}
```

```yaml
# User's application.yml
notification:
  tenants:
    default:
      channels:
        email:
          providers:
            acme:
              beanName: acmeMailerProvider
              api-key: ${ACME_KEY}
              endpoint: https://api.acme.com
```

**Note:** User must add `@ComponentScan(basePackages = "com.acme")` or include in Spring Boot's scan path.

### Option B: Via FQCN (No Spring required)

```java
// In external jar - no Spring annotations needed
public class AcmeMailerProvider implements EmailProvider {

    private AcmeClient client;
    private String apiKey;
    private String endpoint;

    // No-arg constructor required for reflection
    public AcmeMailerProvider() {}

    @Override
    public void configure(Map<String, Object> properties) {
        this.apiKey = (String) properties.get("api-key");
        this.endpoint = (String) properties.get("endpoint");
    }

    @Override
    public void init() {
        this.client = new AcmeClient(apiKey, endpoint);
        client.connect();
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.disconnect();
        }
    }

    @Override
    public SendResult send(EmailMessage message) {
        return client.sendEmail(message);
    }
}
```

```yaml
# User's application.yml
notification:
  tenants:
    default:
      channels:
        email:
          providers:
            acme:
              fqcn: com.acme.notification.AcmeMailerProvider
              api-key: ${ACME_KEY}
              endpoint: https://api.acme.com
```

**Note:** Just add jar to classpath. No Spring scanning needed.

---

## Summary

| Aspect | Decision |
|--------|----------|
| Java SPI | Not used - explicit YAML config is clearer |
| Spring beans | Supported via `beanName` |
| Reflection | Supported via `fqcn` |
| Lifecycle | `configure()` → `init()` → use → `destroy()` |
| Default lifecycle | No-op (providers override if needed) |

## Reasoning

### Why no SPI
1. **Explicit > Implicit**: YAML config makes it clear what's enabled
2. **No surprise providers**: Won't accidentally load a provider from transitive dependency
3. **Simpler debugging**: If provider not found, error message tells you exactly what to do

### Why lifecycle methods
1. **Resource management**: Connection pools, client initialization
2. **Graceful shutdown**: Close connections, flush buffers
3. **Validation**: Check config validity in `init()`, fail fast

### Why default no-op
1. **Simple providers don't need lifecycle**: Just implement `send()`
2. **Backward compatible**: Adding lifecycle doesn't break existing providers

## Related Decisions
- [05-provider-registration.md](./05-provider-registration.md) - beanName/fqcn resolution
