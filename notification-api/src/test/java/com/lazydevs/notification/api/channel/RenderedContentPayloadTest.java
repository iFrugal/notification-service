package com.lazydevs.notification.api.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RenderedContentPayloadTest {

    @Test
    void fourArgConstructorAndFactories_haveNoPayload() {
        assertThat(new RenderedContent("s", "t", "h", "id").payload()).isNull();
        assertThat(RenderedContent.text("t").payload()).isNull();
        assertThat(RenderedContent.email("s", "<p>h</p>", "t").payload()).isNull();
        assertThat(RenderedContent.emailHtml("s", "<p>h</p>").payload(Object.class)).isEmpty();
    }

    @Test
    void fourArgConstructor_equalsFiveArgWithNullPayload() {
        assertThat(new RenderedContent("s", "t", "h", "id"))
                .isEqualTo(new RenderedContent("s", "t", "h", "id", null));
    }

    @Test
    void typedPayloadAccessor_returnsThePayloadOnlyForAMatchingType() {
        RenderedContent content = new RenderedContent(null, null, null, "id", 42);

        assertThat(content.payload(Integer.class)).contains(42);
        assertThat(content.payload(Number.class)).contains(42);
        assertThat(content.payload(String.class)).isEmpty();
        assertThat(content.payload()).isEqualTo(42);
    }
}
