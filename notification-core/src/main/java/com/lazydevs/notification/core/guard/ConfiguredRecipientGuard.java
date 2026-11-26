package com.lazydevs.notification.core.guard;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.guard.RecipientGuard;
import com.lazydevs.notification.api.model.EmailRecipient;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.model.Recipient;
import com.lazydevs.notification.api.model.SmsRecipient;
import com.lazydevs.notification.api.model.WhatsAppRecipient;
import com.lazydevs.notification.core.config.NotificationProperties.RecipientGuardChannel;
import com.lazydevs.notification.core.config.NotificationProperties.RecipientGuardProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The configuration-driven {@link RecipientGuard} (DD-34), registered with
 * {@code notification.recipient-guard.enabled=true}.
 *
 * <p>Per channel, an address is allowed when it matches one
 * {@code allowed} pattern: {@code @domain} matches every email address in
 * exactly that domain, case-insensitively; anything else matches one email
 * address case-insensitively, or one phone number on its digits alone
 * ({@code +1 555-0100} equals {@code 15550100}).
 *
 * <p>An address that is not allowed is replaced by {@code redirect-to}, or
 * dropped when there is none. Email addresses are then de-duplicated across
 * to, cc and bcc (case-insensitive, {@code to} first, then cc, then bcc).
 * When {@code to} itself is dropped, the first remaining cc address, else
 * bcc address, takes its place. Push recipients and channels without rules
 * pass unchanged.
 *
 * <p>The configuration is validated when the guard is built: channel names
 * must be known and every {@code redirect-to} must itself be allowed.
 */
@Slf4j
public class ConfiguredRecipientGuard implements RecipientGuard {

    private static final String ORIGINALS = "{originals}";

    private final Map<Channel, Rule> rules = new EnumMap<>(Channel.class);

    public ConfiguredRecipientGuard(RecipientGuardProperties properties) {
        properties.getChannels().forEach((name, config) -> {
            Channel channel = channel(name);
            if (channel == Channel.PUSH) {
                log.warn("notification.recipient-guard.channels.{} is ignored: push recipients are not guarded", name);
                return;
            }
            rules.put(channel, Rule.of(name, config));
        });
        log.info("Recipient guard enabled for channels {}", rules.keySet());
    }

    @Override
    public Decision check(NotificationRequest request) {
        Rule rule = request.getChannel() == null ? null : rules.get(request.getChannel());
        Recipient recipient = request.getRecipient();
        if (rule == null) {
            return Decision.unchanged(recipient);
        }
        return switch (recipient) {
            case EmailRecipient email -> email(email, rule);
            case SmsRecipient sms -> phone(sms.phoneNumber(), rule, sms,
                    number -> new SmsRecipient(sms.id(), number));
            case WhatsAppRecipient wa -> phone(wa.phoneNumber(), rule, wa,
                    number -> new WhatsAppRecipient(wa.id(), number, wa.whatsappTemplateName(), wa.languageCode(),
                            wa.headerParameters(), wa.bodyParameters(), wa.buttonParameters()));
            default -> Decision.unchanged(recipient);
        };
    }

    private static Decision phone(String number, Rule rule, Recipient original,
                                  java.util.function.Function<String, Recipient> withNumber) {
        if (rule.allowsPhone(number)) {
            return Decision.unchanged(original);
        }
        if (rule.redirectTo == null) {
            return new Decision(null, List.of(), 1, null);
        }
        return new Decision(withNumber.apply(rule.redirectTo), List.of(number), 0, null);
    }

    private static Decision email(EmailRecipient email, Rule rule) {
        EmailRewrite rewrite = new EmailRewrite(rule);
        String to = rewrite.single(email.to());
        List<String> cc = rewrite.list(email.cc());
        List<String> bcc = rewrite.list(email.bcc());
        if (to == null) {
            to = promote(cc, bcc);
        }
        if (to == null) {
            return new Decision(null, rewrite.redirected, rewrite.dropped, null);
        }
        if (rewrite.redirected.isEmpty() && rewrite.dropped == 0) {
            return Decision.unchanged(email);
        }
        EmailRecipient guarded = new EmailRecipient(email.id(), to, cc, bcc, email.replyTo(), email.subject());
        return new Decision(guarded, rewrite.redirected, rewrite.dropped, rule.subjectPrefix(rewrite.redirected));
    }

    private static String promote(List<String> cc, List<String> bcc) {
        if (!cc.isEmpty()) {
            return cc.removeFirst();
        }
        return bcc.isEmpty() ? null : bcc.removeFirst();
    }

    private static Channel channel(String name) {
        try {
            return Channel.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("notification.recipient-guard.channels." + name
                    + " is not a channel; use email, sms or whatsapp", e);
        }
    }

    /** Rewrites the email addresses of one send, in to, cc, bcc order. */
    private static final class EmailRewrite {
        private final Rule rule;
        private final Set<String> seen = new HashSet<>();
        private final List<String> redirected = new ArrayList<>();
        private int dropped;

        EmailRewrite(Rule rule) {
            this.rule = rule;
        }

        String single(String address) {
            return address == null || address.isBlank() ? null : keep(address);
        }

        List<String> list(List<String> addresses) {
            List<String> out = new ArrayList<>();
            if (addresses != null) {
                for (String address : addresses) {
                    String kept = single(address);
                    if (kept != null) {
                        out.add(kept);
                    }
                }
            }
            return out;
        }

        private String keep(String address) {
            String target = address.trim();
            if (!rule.allowsEmail(target)) {
                if (rule.redirectTo == null) {
                    dropped++;
                    return null;
                }
                redirected.add(target);
                target = rule.redirectTo;
            }
            if (!seen.add(target.toLowerCase(Locale.ROOT))) {
                // A duplicate of an address already kept: folded, not counted as dropped
                // when it is the redirect address standing in for another original.
                dropped += target.equalsIgnoreCase(rule.redirectTo) ? 0 : 1;
                return null;
            }
            return target;
        }
    }

    /** The validated rules of one channel. */
    private record Rule(Set<String> domains, Set<String> emails, Set<String> phones, String redirectTo,
                        String subjectPrefix) {

        static Rule of(String channel, RecipientGuardChannel config) {
            Set<String> domains = new HashSet<>();
            Set<String> emails = new HashSet<>();
            Set<String> phones = new HashSet<>();
            for (String pattern : config.getAllowed()) {
                if (pattern == null || pattern.isBlank()) {
                    continue;
                }
                String p = pattern.trim().toLowerCase(Locale.ROOT);
                if (p.startsWith("@")) {
                    domains.add(p.substring(1));
                } else if (p.contains("@")) {
                    emails.add(p);
                } else {
                    phones.add(digits(p));
                }
            }
            String redirect = config.getRedirectTo() == null || config.getRedirectTo().isBlank()
                    ? null : config.getRedirectTo().trim();
            Rule rule = new Rule(domains, emails, phones, redirect, config.getSubjectPrefix());
            if (redirect != null && !rule.allowsEmail(redirect) && !rule.allowsPhone(redirect)) {
                throw new IllegalStateException("notification.recipient-guard.channels." + channel
                        + ".redirect-to must itself be allowed by .allowed");
            }
            return rule;
        }

        boolean allowsEmail(String address) {
            String a = address.trim().toLowerCase(Locale.ROOT);
            int at = a.lastIndexOf('@');
            return at > 0 && (emails.contains(a) || domains.contains(a.substring(at + 1)));
        }

        boolean allowsPhone(String number) {
            String d = digits(number);
            return !d.isEmpty() && phones.contains(d);
        }

        String subjectPrefix(List<String> originals) {
            if (originals.isEmpty() || subjectPrefix == null || subjectPrefix.isEmpty()) {
                return null;
            }
            return subjectPrefix.replace(ORIGINALS, String.join(", ", originals));
        }

        private static String digits(String value) {
            StringBuilder out = new StringBuilder();
            for (char c : value.toCharArray()) {
                if (c >= '0' && c <= '9') {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}
