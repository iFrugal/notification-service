package com.lazydevs.notification.channel.whatsapp.meta;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the Graph API needs to send from one WhatsApp business number (DD-35).
 *
 * <p>{@link #toString()} never prints the access token.
 *
 * @param accessToken   a system user (or user) access token with the
 *                      {@code whatsapp_business_messaging} permission
 * @param phoneNumberId the sender's phone number id (not the phone number)
 * @param wabaId        the WhatsApp Business Account id, optional, informational only
 * @since 1.2.2
 */
public record MetaCredentials(String accessToken, String phoneNumberId, String wabaId) {

    private static final Pattern NUMERIC_ID = Pattern.compile("\\d{1,32}");

    /**
     * Validates the arguments. Ids are numeric, which also keeps them safe in the request path.
     *
     * @throws IllegalArgumentException when the token is blank or an id is not numeric
     */
    public MetaCredentials {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("access token is required");
        }
        accessToken = accessToken.strip();
        phoneNumberId = numericId(Objects.requireNonNullElse(phoneNumberId, ""), "phone number id");
        wabaId = wabaId == null || wabaId.isBlank() ? null : numericId(wabaId, "WABA id");
    }

    /**
     * Credentials without a WABA id.
     *
     * @param accessToken   the access token
     * @param phoneNumberId the sender's phone number id
     */
    public MetaCredentials(String accessToken, String phoneNumberId) {
        this(accessToken, phoneNumberId, null);
    }

    private static String numericId(String value, String what) {
        String id = value.strip();
        if (!NUMERIC_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(what + " must be numeric");
        }
        return id;
    }

    /** Never the access token. */
    @Override
    public String toString() {
        return "MetaCredentials[phoneNumberId=" + phoneNumberId + ", wabaId=" + wabaId + ", accessToken=(hidden)]";
    }
}
