package com.lazydevs.notification.store.jdbc;

import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.NotificationResponse;
import com.lazydevs.notification.api.model.PushRecipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native-image hints for the model types the stores read and
 * write as JSON with Jackson. The stores use no other reflection.
 *
 * <p>The {@code Recipient} subtypes are listed explicitly because they are
 * reached through {@code @JsonSubTypes}, which the binding registrar does
 * not follow. {@code DeliveryEvent} attributes are a plain
 * {@code Map<String, String>} and need no hints.
 */
public class JdbcStoreRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(),
                NotificationRequest.class,
                NotificationRequest.Attachment.class,
                NotificationResponse.class,
                EmailRecipient.class,
                SmsRecipient.class,
                WhatsAppRecipient.class,
                PushRecipient.class);
    }
}
