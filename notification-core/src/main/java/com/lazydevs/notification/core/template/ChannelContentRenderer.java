package com.lazydevs.notification.core.template;

import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.channel.RenderedContent;
import com.lazydevs.notification.api.model.NotificationRequest;

/**
 * Renders the content of every request on one channel, in place of the
 * FreeMarker {@link NotificationTemplateEngine}.
 *
 * <p>A channel whose provider sends structured content rather than text, such
 * as a WhatsApp template message, registers one of these as a Spring bean.
 * The send pipeline uses the renderer registered for the request's channel and
 * falls back to the template engine for every other channel, so a channel
 * without a renderer behaves exactly as before.
 * At most one renderer may be registered per channel; two fail the startup.
 *
 * <p>A renderer runs on the caller's thread before the provider is called. It
 * signals a missing template with
 * {@link com.lazydevs.notification.api.exception.TemplateNotFoundException} and
 * bad input with another
 * {@link com.lazydevs.notification.api.exception.NotificationException}; the
 * provider is not called. With
 * {@code notification.template.validate-before-dispatch} the renderer runs
 * before the idempotency claim, the rate limit and the audit record, and its
 * exception is thrown to the caller with nothing stored; otherwise it runs on
 * the dispatch path and its exception becomes a failed response.
 *
 * @since 1.3.0
 */
public interface ChannelContentRenderer {

    /**
     * @return the channel whose requests this renderer renders
     */
    Channel channel();

    /**
     * Render the content of one request.
     *
     * @param request the enriched request (request id, tenant id and template data are set)
     * @return the content handed to the provider
     */
    RenderedContent render(NotificationRequest request);
}
