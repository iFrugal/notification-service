package com.lazydevs.notification.core.provider;

import com.lazydevs.notification.api.Channel;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog of the provider names notification-service knows about, and the
 * bean-name convention their modules register them under.
 *
 * <p>Each implemented provider module ships an auto-configuration that
 * registers a prototype bean named {@link #beanName(Channel, String)}, for
 * example {@code smtpEmailProvider}.
 * {@link ProviderResolver} looks that bean up when a tenant names the
 * provider without {@code beanName} or {@code fqcn}; the catalog only
 * serves to explain a miss (module absent, or provider not implemented yet)
 * and to feed the GraalVM hints in {@link ProviderRuntimeHints}.
 * Nothing here loads a class.
 */
public final class BuiltInProviders {

    /**
     * A known provider.
     *
     * @param channel     the channel it serves
     * @param name        the provider name used under {@code notification.tenants.*.channels.*.providers}
     * @param className   the implementation class, present only when the module is on the classpath
     * @param artifactId  the Maven artifactId (groupId {@code com.github.ifrugal}) of its module
     * @param implemented whether this release ships an implementation
     */
    public record Entry(Channel channel, String name, String className, String artifactId, boolean implemented) {

        /** @return the conventional bean name, see {@link BuiltInProviders#beanName(Channel, String)} */
        public String beanName() {
            return BuiltInProviders.beanName(channel, name);
        }
    }

    private static final Map<String, Entry> CATALOG = catalog(
            new Entry(Channel.EMAIL, "smtp",
                    "com.lazydevs.notification.channel.email.smtp.SmtpEmailProvider", "email-provider-smtp", true),
            new Entry(Channel.EMAIL, "ses",
                    "com.lazydevs.notification.channel.email.ses.SesEmailProvider", "email-provider-ses", true),
            new Entry(Channel.EMAIL, "acs",
                    "com.lazydevs.notification.channel.email.acs.AcsEmailProvider", "email-provider-acs", true),
            new Entry(Channel.SMS, "twilio",
                    "com.lazydevs.notification.channel.sms.twilio.TwilioSmsProvider", "sms-provider-twilio", true),
            new Entry(Channel.SMS, "sns",
                    "com.lazydevs.notification.channel.sms.sns.SnsSmsProvider", "sms-provider-sns", false),
            new Entry(Channel.WHATSAPP, "twilio",
                    "com.lazydevs.notification.channel.whatsapp.twilio.TwilioWhatsAppProvider",
                    "whatsapp-provider-twilio", false),
            new Entry(Channel.WHATSAPP, "meta",
                    "com.lazydevs.notification.channel.whatsapp.meta.MetaWhatsAppProvider",
                    "whatsapp-provider-meta", false),
            new Entry(Channel.PUSH, "fcm",
                    "com.lazydevs.notification.channel.push.fcm.FcmPushProvider", "push-provider-fcm", true),
            new Entry(Channel.PUSH, "apns",
                    "com.lazydevs.notification.channel.push.apns.ApnsPushProvider", "push-provider-apns", false));

    private BuiltInProviders() {
    }

    /**
     * The bean name a built-in provider module registers its provider under:
     * the provider name, then the channel capitalised, then {@code Provider},
     * for example {@code smtpEmailProvider} or {@code twilioWhatsappProvider}.
     */
    public static String beanName(Channel channel, String name) {
        String channelName = channel.name().toLowerCase(Locale.ROOT);
        return name + Character.toUpperCase(channelName.charAt(0)) + channelName.substring(1) + "Provider";
    }

    /** @return the catalog entry for the provider name on the channel, if it is a known built-in */
    public static Optional<Entry> find(Channel channel, String name) {
        return Optional.ofNullable(CATALOG.get(key(channel, name)));
    }

    /** @return every catalog entry, in declaration order */
    public static Collection<Entry> all() {
        return CATALOG.values();
    }

    /** @return the catalog entries for one channel, in declaration order */
    public static List<Entry> forChannel(Channel channel) {
        return CATALOG.values().stream().filter(entry -> entry.channel() == channel).toList();
    }

    private static String key(Channel channel, String name) {
        return channel.name() + ":" + name;
    }

    private static Map<String, Entry> catalog(Entry... entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : entries) {
            map.put(key(entry.channel(), entry.name()), entry);
        }
        return Collections.unmodifiableMap(map);
    }
}
