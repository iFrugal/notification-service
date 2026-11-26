package com.lazydevs.notification.channel.whatsapp.meta;

import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage;
import com.lazydevs.notification.channel.whatsapp.WhatsAppTemplateMessage.ButtonParameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON shape of the Cloud API template message.
 */
class MetaMessageMappingTest {

    @Test
    void fullTemplate_hasHeaderBodyAndButtons_inThatOrder() {
        WhatsAppTemplateMessage message = new WhatsAppTemplateMessage("order_shipped", "en_US",
                List.of("Parcel Co"), List.of("Jane", "1042"),
                List.of(new ButtonParameter(0, "url", "abc123"),
                        new ButtonParameter(1, "quick_reply", "STOP"),
                        new ButtonParameter(2, "copy_code", "SAVE10")));

        String json = MetaJson.writeString(MetaMessageMapper.body("15550100001", message, "req-1"));

        assertThat(json).isEqualTo("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\","
                + "\"to\":\"15550100001\",\"type\":\"template\",\"template\":{\"name\":\"order_shipped\","
                + "\"language\":{\"code\":\"en_US\"},\"components\":["
                + "{\"type\":\"header\",\"parameters\":[{\"type\":\"text\",\"text\":\"Parcel Co\"}]},"
                + "{\"type\":\"body\",\"parameters\":[{\"type\":\"text\",\"text\":\"Jane\"},"
                + "{\"type\":\"text\",\"text\":\"1042\"}]},"
                + "{\"type\":\"button\",\"sub_type\":\"url\",\"index\":\"0\","
                + "\"parameters\":[{\"type\":\"text\",\"text\":\"abc123\"}]},"
                + "{\"type\":\"button\",\"sub_type\":\"quick_reply\",\"index\":\"1\","
                + "\"parameters\":[{\"type\":\"payload\",\"payload\":\"STOP\"}]},"
                + "{\"type\":\"button\",\"sub_type\":\"copy_code\",\"index\":\"2\","
                + "\"parameters\":[{\"type\":\"coupon_code\",\"coupon_code\":\"SAVE10\"}]}]},"
                + "\"biz_opaque_callback_data\":\"req-1\"}");
    }

    @Test
    void templateWithoutParameters_hasNoComponents_andNoCallbackDataWhenAbsentOrTooLong() {
        WhatsAppTemplateMessage message = new WhatsAppTemplateMessage("hello_world", "en", null, null, null);

        assertThat(MetaJson.writeString(MetaMessageMapper.body("15550100001", message, null)))
                .isEqualTo("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\","
                        + "\"to\":\"15550100001\",\"type\":\"template\",\"template\":{\"name\":\"hello_world\","
                        + "\"language\":{\"code\":\"en\"}}}");
        assertThat(MetaMessageMapper.body("1", message, "x".repeat(513)).has("biz_opaque_callback_data")).isFalse();
        assertThat(MetaMessageMapper.body("1", message, " ").has("biz_opaque_callback_data")).isFalse();
    }

    @Test
    void messageToString_hidesParameterValues() {
        WhatsAppTemplateMessage message = new WhatsAppTemplateMessage("order_shipped", "en",
                List.of(), List.of("Jane"), List.of(new ButtonParameter(0, "url", "secret-token")));
        assertThat(message.toString()).doesNotContain("Jane", "secret-token").contains("order_shipped");
    }
}
