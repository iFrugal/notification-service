# Decision 05: Channel & Provider Registration

## Status: DECIDED

## Context
Need a mechanism to:
1. Register available channels and providers
2. Configure which provider to use per tenant per channel
3. Support external providers (from other repos/jars)
4. Avoid naming collisions when multiple people create providers
5. Leverage existing `InitDTO` pattern from `persistence-utils`

## Decision
**Option C: Hybrid with `beanName` and `fqcn` support**

Use the existing `InitDTO` pattern from `persistence-utils` which already supports both `beanName` (Spring bean lookup) and `fqcn` (class instantiation via reflection).

### Provider Resolution Order

1. **If `beanName` specified** → Look up Spring bean by name
2. **Else if `fqcn` specified** → Instantiate class using `ClassUtils.loadClass()` and `ReflectionUtils`
3. **Else (built-in only)** → Look up by provider name from internal registry
4. **If none found** → Fail startup with clear error message

### YAML Configuration Structure

```yaml
notification:
  default-tenant: default

  tenants:
    default:
      channels:
        email:
          enabled: true
          config:                           # Channel-level config (shared by all providers)
            from-address: noreply@example.com
            reply-to: support@example.com
          providers:
            # Single provider: auto-default (no need to mark as default)
            smtp:
              properties:
                host: smtp.gmail.com
                port: 587
                username: ${SMTP_USER}
                password: ${SMTP_PASS}

        sms:
          enabled: true
          providers:
            # Single provider: auto-default
            twilio:
              properties:
                account-sid: ${TWILIO_SID}
                auth-token: ${TWILIO_TOKEN}
                from: +1234567890

    # Tenant with MULTIPLE email providers
    tenant-a:
      channels:
        email:
          enabled: true
          config:
            from-address: tenant-a@example.com
          providers:
            # When multiple providers, ONE must have default: true
            smtp:
              # No default=true, so this is NOT the default
              properties:
                host: smtp.internal
                port: 587

            ses:
              default: true                  # SES is the default provider
              properties:
                region: eu-west-1

            # External provider via Spring bean
            acme-mailer:
              beanName: acmeMailerProvider
              properties:
                api-key: ${ACME_API_KEY}

            # External provider via FQCN (not a Spring bean)
            custom-smtp:
              fqcn: com.mycompany.notification.CustomSmtpProvider
              properties:
                host: custom-smtp.internal
                port: 2525
```

### Default Provider Selection Logic

The provider to use is determined by the following rules:

1. **If provider specified in request** → Use that provider
2. **If only 1 provider configured for channel** → Use that (auto-default)
3. **If multiple providers and one has `default: true`** → Use that provider
4. **If multiple providers and none has `default: true`** → **Fail** with `PROVIDER_REQUIRED` error

**Startup Validation:**
- If multiple providers are configured for a channel and MORE than one has `default: true` → **Fail startup**
- If multiple providers and none has `default: true` → **Warning log** (valid but provider required in every request)

### Provider Configuration Class (Similar to InitDTO)

```java
@Getter @Setter
public class ProviderConfig {

    /**
     * Spring bean name. If specified, looks up bean from ApplicationContext.
     * Takes precedence over fqcn.
     */
    private String beanName;

    /**
     * Fully Qualified Class Name. If specified (and no beanName),
     * instantiates class via ClassUtils.loadClass() and reflection.
     */
    private String fqcn;

    /**
     * Whether this is the default provider for the channel.
     * Only ONE provider per channel can have default=true.
     * If only one provider is configured, it's automatically the default.
     */
    private boolean isDefault = false;

    /**
     * Provider-specific configuration properties.
     * Passed to provider via configure(Map) method.
     */
    private Map<String, Object> properties = new LinkedHashMap<>();

    /**
     * Constructor arguments (for FQCN instantiation).
     * Uses InitDTO.ArgDTO pattern from persistence-utils.
     */
    private List<InitDTO.ArgDTO> constructorArgs;

    /**
     * Field injection after construction.
     * Uses InitDTO.NamedArgDTO pattern from persistence-utils.
     */
    private List<InitDTO.NamedArgDTO> attributes;
}
```

### Provider Resolution Logic

```java
@Component
public class ProviderResolver {

    private final ApplicationContext applicationContext;
    private final Map<String, Class<? extends NotificationProvider>> builtInProviders;

    /**
     * Resolve provider instance from configuration.
     *
     * Resolution order:
     * 1. beanName → Spring bean lookup
     * 2. fqcn → Class instantiation via ClassUtils
     * 3. providerName → Built-in registry lookup
     * 4. None found → Fail with clear error
     */
    public <T extends NotificationProvider> T resolve(
            String providerName,
            ProviderConfig config,
            Class<T> providerInterface) {

        // 1. Try Spring bean lookup
        if (StringUtils.hasText(config.getBeanName())) {
            return resolveByBeanName(config.getBeanName(), providerInterface);
        }

        // 2. Try FQCN instantiation
        if (StringUtils.hasText(config.getFqcn())) {
            return resolveByFqcn(config, providerInterface);
        }

        // 3. Try built-in provider lookup
        Class<? extends NotificationProvider> builtInClass =
            builtInProviders.get(providerInterface.getSimpleName() + ":" + providerName);

        if (builtInClass != null) {
            return instantiateAndConfigure(builtInClass, config, providerInterface);
        }

        // 4. Fail with clear error
        throw new ProviderNotFoundException(
            String.format(
                "Provider '%s' not found. For external providers, specify either " +
                "'beanName' (for Spring beans) or 'fqcn' (for class instantiation). " +
                "Available built-in providers: %s",
                providerName,
                getAvailableBuiltInProviders(providerInterface)
            )
        );
    }

    private <T> T resolveByBeanName(String beanName, Class<T> providerInterface) {
        try {
            Object bean = applicationContext.getBean(beanName);
            if (providerInterface.isInstance(bean)) {
                return providerInterface.cast(bean);
            }
            throw new ProviderConfigurationException(
                String.format("Bean '%s' is not an instance of %s",
                    beanName, providerInterface.getName())
            );
        } catch (NoSuchBeanDefinitionException e) {
            throw new ProviderNotFoundException(
                String.format("Spring bean '%s' not found in ApplicationContext", beanName)
            );
        }
    }

    private <T> T resolveByFqcn(ProviderConfig config, Class<T> providerInterface) {
        try {
            // Use ClassUtils from persistence-utils
            Class<T> clazz = ClassUtils.loadClassAssignableFrom(
                config.getFqcn(),
                providerInterface
            );
            return instantiateAndConfigure(clazz, config, providerInterface);
        } catch (IllegalArgumentException e) {
            throw new ProviderNotFoundException(
                String.format("Class '%s' not found in classpath or does not implement %s: %s",
                    config.getFqcn(), providerInterface.getName(), e.getMessage())
            );
        }
    }

    private <T> T instantiateAndConfigure(
            Class<?> clazz,
            ProviderConfig config,
            Class<T> providerInterface) {

        // Build InitDTO from config
        InitDTO initDTO = new InitDTO();
        initDTO.setFqcn(clazz.getName());
        initDTO.setConstructorArgs(config.getConstructorArgs());
        initDTO.setAttributes(config.getAttributes());

        // Use ReflectionUtils from persistence-utils
        Function<String, Object> beanSupplier = applicationContext::getBean;
        T provider = ReflectionUtils.getInterfaceReference(initDTO, providerInterface, beanSupplier);

        // Call configure() with properties
        if (provider instanceof ConfigurableProvider) {
            ((ConfigurableProvider) provider).configure(config.getProperties());
        }

        return provider;
    }
}
```

### Fail-Fast Validation at Startup

```java
@Component
public class ProviderValidationPostProcessor implements ApplicationRunner {

    private final NotificationProperties properties;
    private final ProviderResolver resolver;

    @Override
    public void run(ApplicationArguments args) {
        // Validate all configured providers at startup
        properties.getTenants().forEach((tenantId, tenantConfig) -> {
            tenantConfig.getChannels().forEach((channelName, channelConfig) -> {
                if (channelConfig.isEnabled()) {
                    channelConfig.getProviders().forEach((providerName, providerConfig) -> {
                        validateProvider(tenantId, channelName, providerName, providerConfig);
                    });
                }
            });
        });

        log.info("All notification providers validated successfully");
    }

    private void validateProvider(String tenantId, String channel,
                                   String providerName, ProviderConfig config) {
        try {
            Class<?> providerInterface = getProviderInterface(channel);
            resolver.resolve(providerName, config, providerInterface);
            log.debug("Validated provider: tenant={}, channel={}, provider={}",
                tenantId, channel, providerName);
        } catch (Exception e) {
            throw new ProviderConfigurationException(
                String.format("Failed to initialize provider '%s' for channel '%s' in tenant '%s': %s",
                    providerName, channel, tenantId, e.getMessage()),
                e
            );
        }
    }
}
```

## Reasoning

### Why `beanName` + `fqcn` (not just `class`)
1. **Follows existing pattern**: `InitDTO` in `persistence-utils` already uses this
2. **`beanName`**: For Spring-managed beans with DI, lifecycle, AOP
3. **`fqcn`**: For non-Spring instantiation (library mode, external jars)
4. **Field name `fqcn`**: More explicit than `class` (which is a reserved word)

### Why fail at startup
1. **Fail-fast principle**: Don't discover missing provider during first notification
2. **Clear error messages**: Help developers identify misconfiguration
3. **Consistent with Spring Boot**: Similar to datasource validation

### Why support both built-in names and explicit fqcn
1. **Simple for common cases**: `smtp`, `ses`, `twilio` just work
2. **Explicit for external**: Forces FQCN, prevents naming collision
3. **Override capability**: Can replace built-in with custom implementation

## Alternatives Considered

### Alternative 1: Always require FQCN
- **Rejected**: Too verbose for simple/common providers

### Alternative 2: Name-only with collision risk
- **Rejected**: Two external jars could use same name

### Alternative 3: Namespace prefix (`lazydevs:ses`, `acme:custom`)
- **Rejected**: More complex, `beanName`/`fqcn` is clearer

## Consequences

### Positive
- Reuses proven `InitDTO`/`ReflectionUtils` pattern
- Clear contract: beanName for Spring, fqcn for reflection
- Fail-fast with helpful error messages
- Works in both library and standalone modes

### Negative
- Need documentation for external provider developers
- Two ways to configure (beanName vs fqcn) may confuse initially

## Related Decisions
- [06-spi-discovery.md](./06-spi-discovery.md) - Discovery mechanism
- [01-module-structure.md](./01-module-structure.md) - Where providers live
