package com.lazydevs.notification.api.template;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Argument checks every {@link TemplateStore} implementation applies the same way (DD-38).
 *
 * @since 1.3.0
 */
public final class TemplateStoreChecks {

    /** Default limit for the content and for the sample model, in bytes of UTF-8: 256 KiB. */
    public static final int DEFAULT_MAX_CONTENT_BYTES = 256 * 1024;

    private TemplateStoreChecks() {
    }

    /**
     * @throws TemplateStoreException {@code INVALID_REQUEST} when the content type is unknown or
     *                                the content is missing, {@code SIZE_LIMIT} when the content or
     *                                the sample model is larger than {@code maxBytes} in UTF-8
     */
    public static void requireDraft(TemplateKey key, String contentType, String content, String sampleModel,
                                    int maxBytes) {
        if (!TemplateVersion.FREEMARKER.equals(contentType) && !TemplateVersion.JSON.equals(contentType)) {
            throw new TemplateStoreException(TemplateStoreException.Reason.INVALID_REQUEST, key,
                    "contentType must be '" + TemplateVersion.FREEMARKER + "' or '" + TemplateVersion.JSON + "'");
        }
        if (content == null) {
            throw new TemplateStoreException(TemplateStoreException.Reason.INVALID_REQUEST, key, "content is required");
        }
        requireWithin(key, "content", content, maxBytes);
        if (sampleModel != null) {
            requireWithin(key, "sample model", sampleModel, maxBytes);
        }
    }

    /**
     * @throws TemplateStoreException {@code INVALID_REQUEST} when the actor is missing
     */
    public static String requireActor(TemplateKey key, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new TemplateStoreException(TemplateStoreException.Reason.INVALID_REQUEST, key, "actor is required");
        }
        return actor;
    }

    private static void requireWithin(TemplateKey key, String what, String text, int maxBytes) {
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxBytes) {
            String message = "The " + what + " is " + size + " bytes; the limit is " + maxBytes;
            throw new TemplateStoreException(TemplateStoreException.Reason.SIZE_LIMIT, key, message, 0,
                    List.of(TemplateProblem.of(TemplateProblem.Reason.SIZE_LIMIT, message)));
        }
    }

    /**
     * @throws IllegalArgumentException when {@code maxBytes} is less than 1
     */
    public static int requirePositiveLimit(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("notification.template.store.max-content-size must be positive, got "
                    + maxBytes);
        }
        return maxBytes;
    }
}
