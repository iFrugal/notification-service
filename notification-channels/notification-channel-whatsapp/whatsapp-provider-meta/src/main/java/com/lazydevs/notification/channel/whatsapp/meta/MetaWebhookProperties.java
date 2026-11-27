package com.lazydevs.notification.channel.whatsapp.meta;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code notification.webhooks.meta.*}: the Meta WhatsApp status webhook
 * (DD-35, DD-36).
 *
 * <pre>
 * notification:
 *   webhooks:
 *     meta:
 *       enabled: true
 *       app-secret: ${META_APP_SECRET}          # signs every POST (X-Hub-Signature-256)
 *       verify-token: ${META_VERIFY_TOKEN}      # your own value, echoed by the GET handshake
 * </pre>
 *
 * Served at {@code <rest base path><webhooks base path>/meta} by the REST
 * module, or by the application's own endpoint through the
 * {@code WebhookDispatcher}.
 *
 * @since 1.2.2
 */
@Data
@ConfigurationProperties(prefix = MetaWebhookProperties.PREFIX)
public class MetaWebhookProperties {

    /** Property prefix. */
    public static final String PREFIX = "notification.webhooks.meta";

    /** Registers the handler. */
    private boolean enabled = false;

    /** Handler id: the path segment under the webhooks base path. */
    private String id = MetaWebhookHandler.DEFAULT_ID;

    /** The Meta app secret; required while signature verification is on. */
    @ToString.Exclude
    private String appSecret;

    /** The verify token entered in the Meta app's webhook settings; without it the GET handshake is refused. */
    @ToString.Exclude
    private String verifyToken;

    /**
     * Verify {@code X-Hub-Signature-256} on every POST. <strong>Keep {@code true}
     * in production</strong>; switching it off logs a WARN on every callback.
     */
    private boolean signatureVerification = true;
}
