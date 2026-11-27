package com.lazydevs.notification.core.push;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.exception.TemplateRenderException;
import com.lazydevs.notification.api.exception.TemplateRenderException.Reason;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.api.push.PushContent;
import com.lazydevs.notification.core.template.ChannelContentRenderer;
import com.lazydevs.notification.core.template.NotificationTemplateEngine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders push templates with optional {@code [TITLE]}, {@code [BODY]} and
 * {@code [DATA key="..."]} sections (DD-37):
 *
 * <pre>
 * [TITLE]Order ${orderId} shipped[/TITLE]
 * [BODY]It arrives on ${eta}.[/BODY]
 * [DATA key="orderId"]${orderId}[/DATA]
 * [DATA key="deepLink"]app://orders/${orderId}[/DATA]
 * </pre>
 *
 * <ul>
 *   <li>The template is found and rendered by the {@link NotificationTemplateEngine}
 *       ({@link NotificationTemplateEngine#renderRaw}), with its lookup, cache, helpers and
 *       FreeMarker configuration; this class never loads a template itself.</li>
 *   <li>Before rendering, every section marker in the template source is swapped for a
 *       token built from a fresh random UUID, and the output is split on those tokens only.
 *       A marker that arrives in template data is plain text and cannot open, close or
 *       move a section.</li>
 *   <li>Without any section the output is the body, exactly as before 1.4.0: the title
 *       then comes from {@code PushRecipient.title}.</li>
 *   <li>With sections, the body is the {@code [BODY]} section, else the text outside the
 *       sections; title, body and data values are trimmed. Text is plain: nothing is
 *       HTML-escaped.</li>
 *   <li>The result carries the title as {@code subject}, the body as {@code textBody} and a
 *       {@link PushContent} payload with the data; the provider merges that data under the
 *       recipient's own (the caller's key wins).</li>
 *   <li>A section opened inside another, a close without an open, a section left open, a
 *       repeated section or data key, or a data key that is not 1-128 letters, digits,
 *       {@code .}, {@code _} or {@code -} fails with
 *       {@link Reason#INVALID_TEMPLATE}: the template source is at fault.</li>
 *   <li>Sections are supported only in the top-level template. Markers in a file it pulls in
 *       with {@code <#include>} or {@code <#import>} are not swapped for tokens, so when the
 *       top-level template includes or imports anything, output that still contains a literal
 *       marker ({@code [TITLE]}, {@code [/TITLE]}, {@code [BODY]}, {@code [/BODY]},
 *       {@code [DATA ...]}, {@code [/DATA]}) fails with {@link Reason#INVALID_TEMPLATE} instead
 *       of being sent as text. In such a template a marker that arrives in template data fails
 *       the same way; the two cannot be told apart after rendering.</li>
 * </ul>
 *
 * <p>Registered for {@link Channel#PUSH} unless
 * {@code notification.push.template-sections.enabled=false}.
 *
 * @since 1.4.0
 */
public class PushContentRenderer implements ChannelContentRenderer {

    private static final Pattern DATA_OPEN = Pattern.compile("\\[DATA key=\"([^\"\\]]*)\"]");
    private static final Pattern DATA_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String TITLE_OPEN = "[TITLE]";
    private static final String TITLE_CLOSE = "[/TITLE]";
    private static final String BODY_OPEN = "[BODY]";
    private static final String BODY_CLOSE = "[/BODY]";
    private static final String DATA_CLOSE = "[/DATA]";
    /** A section marker left as text in the output. */
    private static final Pattern LITERAL_MARKER =
            Pattern.compile("\\[(?:/?TITLE|/?BODY|/DATA)]|\\[DATA\\s[^\\]]*]");
    /** A directive that pulls another file into the template, in either tag syntax. */
    private static final Pattern PULLS_IN_FILES = Pattern.compile("[<\\[]#\\s*(?:include|import)\\b");

    private final NotificationTemplateEngine engine;

    /**
     * @param engine the engine that finds and renders the template
     */
    public PushContentRenderer(NotificationTemplateEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public RenderedContent render(NotificationRequest request) {
        Tokens tokens = new Tokens(UUID.randomUUID().toString());
        Source source = new Source(tokens);
        NotificationTemplateEngine.RawOutput raw = engine.renderRaw(request, source::tokenize);
        if (source.invalidKey != null) {
            throw invalid(raw, "data key '" + shortened(source.invalidKey)
                    + "' must be 1-128 letters, digits, '.', '_' or '-'");
        }
        String output = raw.output();
        if (source.pullsInFiles && LITERAL_MARKER.matcher(output).find()) {
            throw invalid(raw, "a section marker is left in the output; sections are only supported in the"
                    + " top-level template, not in a file it includes or imports");
        }
        if (!output.contains(tokens.prefix)) {
            return RenderedContent.text(output.trim());
        }
        return parse(raw, output, tokens);
    }

    private static RenderedContent parse(NotificationTemplateEngine.RawOutput raw, String output, Tokens tokens) {
        String title = null;
        String body = null;
        Map<String, String> data = new LinkedHashMap<>();
        StringBuilder outside = new StringBuilder();

        int pos = 0;
        while (pos < output.length()) {
            int start = output.indexOf(tokens.prefix, pos);
            if (start < 0) {
                outside.append(output, pos, output.length());
                break;
            }
            outside.append(output, pos, start);
            Token open = tokens.read(output, start);
            if (open.kind() == Kind.CLOSE) {
                throw invalid(raw, open.marker() + " has no matching opening marker");
            }
            int contentStart = start + open.length();
            int end = output.indexOf(tokens.prefix, contentStart);
            Token close = end < 0 ? null : tokens.read(output, end);
            if (close == null || close.kind() != Kind.CLOSE) {
                throw invalid(raw, open.marker() + (close == null ? " is never closed"
                        : " is followed by " + close.marker() + " before it is closed"));
            }
            if (!close.closes(open)) {
                throw invalid(raw, open.marker() + " is closed by " + close.marker());
            }
            String value = output.substring(contentStart, end).trim();
            switch (open.section()) {
                case TITLE -> {
                    requireFirst(raw, title, TITLE_OPEN);
                    title = value;
                }
                case BODY -> {
                    requireFirst(raw, body, BODY_OPEN);
                    body = value;
                }
                default -> {
                    if (data.containsKey(open.key())) {
                        throw invalid(raw, "data key '" + open.key() + "' appears twice");
                    }
                    data.put(open.key(), value);
                }
            }
            pos = end + close.length();
        }
        if (body == null) {
            String rest = outside.toString().trim();
            body = rest.isEmpty() ? null : rest;
        }
        title = title == null || title.isEmpty() ? null : title;
        return new RenderedContent(title, body, null, raw.templateId(), new PushContent(title, body, data));
    }

    /** At most 32 characters of a key, without control characters, for an error message. */
    private static String shortened(String key) {
        String cut = key.length() > 32 ? key.substring(0, 32) + "..." : key;
        return cut.replaceAll("\\p{Cntrl}", "?");
    }

    private static void requireFirst(NotificationTemplateEngine.RawOutput raw, String existing, String marker) {
        if (existing != null) {
            throw invalid(raw, marker + " appears twice");
        }
    }

    private static TemplateRenderException invalid(NotificationTemplateEngine.RawOutput raw, String problem) {
        return new TemplateRenderException(Reason.INVALID_TEMPLATE, raw.tenantId(), raw.templateId(), null,
                "Push template " + raw.templateId() + " for tenant " + raw.tenantId() + " has invalid sections: "
                        + problem, null);
    }

    private enum Section { TITLE, BODY, DATA }

    private enum Kind { OPEN, CLOSE }

    /** A token found in the output. */
    private record Token(Kind kind, Section section, String key, int length) {

        boolean closes(Token open) {
            return section == open.section;
        }

        String marker() {
            if (section == Section.DATA) {
                return kind == Kind.OPEN ? "[DATA key=\"" + key + "\"]" : DATA_CLOSE;
            }
            String name = section.name();
            return kind == Kind.OPEN ? "[" + name + "]" : "[/" + name + "]";
        }
    }

    /**
     * The per-render tokens. Each is {@code NUL + nonce + ':' + kind [+ ':' + key] + NUL}; the
     * nonce is a fresh random UUID, so template data cannot contain one.
     */
    private static final class Tokens {

        private final String prefix;

        Tokens(String nonce) {
            this.prefix = "\u0000" + nonce + ":";
        }

        String open(Section section) {
            return prefix + "+" + section.name() + "\u0000";
        }

        String close(Section section) {
            return prefix + "-" + section.name() + "\u0000";
        }

        String openData(String key) {
            return prefix + "+" + Section.DATA.name() + ":" + key + "\u0000";
        }

        /** The token at {@code start}, which begins with {@link #prefix}. */
        Token read(String output, int start) {
            int end = output.indexOf('\u0000', start + prefix.length());
            String body = output.substring(start + prefix.length(), end);
            Kind kind = body.charAt(0) == '+' ? Kind.OPEN : Kind.CLOSE;
            String rest = body.substring(1);
            int colon = rest.indexOf(':');
            Section section = Section.valueOf(colon < 0 ? rest : rest.substring(0, colon));
            String key = colon < 0 ? null : rest.substring(colon + 1);
            return new Token(kind, section, key, end + 1 - start);
        }
    }

    /** Swaps the markers of a template source for tokens, remembering an invalid data key. */
    private static final class Source {

        private final Tokens tokens;
        private String invalidKey;
        private boolean pullsInFiles;

        Source(Tokens tokens) {
            this.tokens = tokens;
        }

        String tokenize(String source) {
            pullsInFiles = PULLS_IN_FILES.matcher(source).find();
            String result = source
                    .replace(TITLE_OPEN, tokens.open(Section.TITLE))
                    .replace(TITLE_CLOSE, tokens.close(Section.TITLE))
                    .replace(BODY_OPEN, tokens.open(Section.BODY))
                    .replace(BODY_CLOSE, tokens.close(Section.BODY))
                    .replace(DATA_CLOSE, tokens.close(Section.DATA));
            Matcher matcher = DATA_OPEN.matcher(result);
            StringBuilder out = new StringBuilder(result.length());
            while (matcher.find()) {
                String key = matcher.group(1);
                if (!DATA_KEY.matcher(key).matches() && invalidKey == null) {
                    invalidKey = key;
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(tokens.openData(key)));
            }
            matcher.appendTail(out);
            return out.toString();
        }
    }
}
