package com.lazydevs.notification.core.template;

import com.github.benmanes.caffeine.cache.Ticker;
import com.lazydevs.notification.api.Channel;
import com.lazydevs.notification.api.exception.TemplateNotFoundException;
import com.lazydevs.notification.api.model.NotificationRequest;
import com.lazydevs.notification.core.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The template cache: bounded by {@code cache-max-size}, expired after
 * {@code cache-ttl-seconds}, and cleared by the admin endpoints.
 */
class NotificationTemplateEngineCacheTest {

    @TempDir
    Path dir;

    private final NotificationProperties properties = new NotificationProperties();
    private final FakeTicker ticker = new FakeTicker();

    @BeforeEach
    void setUp() {
        properties.getTemplate().setBasePath(dir.toUri().toString());
    }

    private NotificationTemplateEngine engine() {
        return new NotificationTemplateEngine(properties, new DefaultResourceLoader(), ticker);
    }

    private void write(String tenant, String id, String content) throws IOException {
        Path file = dir.resolve(tenant + "/sms/" + id + ".ftl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String render(NotificationTemplateEngine engine, String tenant, String id) {
        return engine.render(NotificationRequest.builder()
                .requestId("r-1")
                .tenantId(tenant)
                .notificationType(id)
                .channel(Channel.SMS)
                .templateData(new HashMap<>(Map.of()))
                .build()).textBody();
    }

    @Test
    void changedTemplate_isPickedUpAfterTtl() throws IOException {
        properties.getTemplate().setCacheTtlSeconds(1);
        write("default", "OTP", "v1");
        NotificationTemplateEngine engine = engine();

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");
        write("default", "OTP", "v2");

        ticker.advance(Duration.ofMillis(999));
        assertThat(render(engine, "acme", "OTP")).as("still within the TTL").isEqualTo("v1");

        ticker.advance(Duration.ofMillis(2));
        assertThat(render(engine, "acme", "OTP")).as("TTL elapsed").isEqualTo("v2");
    }

    @Test
    void zeroTtl_neverExpires() throws IOException {
        properties.getTemplate().setCacheTtlSeconds(0);
        write("default", "OTP", "v1");
        NotificationTemplateEngine engine = engine();

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");
        write("default", "OTP", "v2");
        ticker.advance(Duration.ofDays(30));

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");
    }

    @Test
    void defaultTtl_isAnHour() throws IOException {
        write("default", "OTP", "v1");
        NotificationTemplateEngine engine = engine();

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");
        write("default", "OTP", "v2");
        ticker.advance(Duration.ofMinutes(59));
        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");

        ticker.advance(Duration.ofMinutes(2));
        assertThat(render(engine, "acme", "OTP")).isEqualTo("v2");
    }

    @Test
    void clearCache_reloadsOnlyThatTenant() throws IOException {
        write("default", "OTP", "v1");
        NotificationTemplateEngine engine = engine();
        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");
        assertThat(render(engine, "globex", "OTP")).isEqualTo("v1");

        write("default", "OTP", "v2");
        engine.clearCache("acme");

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v2");
        assertThat(render(engine, "globex", "OTP")).isEqualTo("v1");
    }

    @Test
    void clearAllCache_reloadsEveryTenant() throws IOException {
        write("default", "OTP", "v1");
        NotificationTemplateEngine engine = engine();
        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");
        assertThat(render(engine, "globex", "OTP")).isEqualTo("v1");

        write("default", "OTP", "v2");
        engine.clearAllCache();

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v2");
        assertThat(render(engine, "globex", "OTP")).isEqualTo("v2");
    }

    @Test
    void cacheDisabled_readsEveryTime() throws IOException {
        properties.getTemplate().setCacheEnabled(false);
        write("default", "OTP", "v1");
        NotificationTemplateEngine engine = engine();
        assertThat(render(engine, "acme", "OTP")).isEqualTo("v1");

        write("default", "OTP", "v2");

        assertThat(render(engine, "acme", "OTP")).isEqualTo("v2");
        assertThat(engine.cachedTemplateCount()).isZero();
    }

    @Test
    void cacheIsBoundedByMaxSize() throws IOException {
        properties.getTemplate().setCacheMaxSize(2);
        NotificationTemplateEngine engine = engine();
        for (int i = 0; i < 10; i++) {
            write("default", "T" + i, "t" + i);
            assertThat(render(engine, "acme", "T" + i)).isEqualTo("t" + i);
        }

        assertThat(engine.cachedTemplateCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void defaultMaxSize_isAThousand() {
        assertThat(new NotificationProperties().getTemplate().getCacheMaxSize()).isEqualTo(1000);
    }

    @Test
    void missingTemplate_isNotCached() throws IOException {
        NotificationTemplateEngine engine = engine();
        assertThatThrownBy(() -> render(engine, "acme", "LATE"))
                .isInstanceOf(TemplateNotFoundException.class);

        write("default", "LATE", "now here");

        assertThat(render(engine, "acme", "LATE")).isEqualTo("now here");
    }

    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
