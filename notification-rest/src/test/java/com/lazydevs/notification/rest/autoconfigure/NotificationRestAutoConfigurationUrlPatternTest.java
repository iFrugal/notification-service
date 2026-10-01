package com.lazydevs.notification.rest.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant and caller-admission filters are restricted to the REST base
 * path; these cases pin how the base path becomes a servlet URL pattern.
 */
class NotificationRestAutoConfigurationUrlPatternTest {

    @Test
    void defaultBasePath() {
        assertThat(NotificationRestAutoConfiguration.urlPattern("/api/v1")).isEqualTo("/api/v1/*");
    }

    @Test
    void trailingSlashesAreDropped() {
        assertThat(NotificationRestAutoConfiguration.urlPattern("/api/v1/")).isEqualTo("/api/v1/*");
        assertThat(NotificationRestAutoConfiguration.urlPattern("/api/v1//")).isEqualTo("/api/v1/*");
    }

    @Test
    void rootBasePathCoversEverything() {
        assertThat(NotificationRestAutoConfiguration.urlPattern("")).isEqualTo("/*");
        assertThat(NotificationRestAutoConfiguration.urlPattern("/")).isEqualTo("/*");
        assertThat(NotificationRestAutoConfiguration.urlPattern(null)).isEqualTo("/*");
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(NotificationRestAutoConfiguration.urlPattern(" /notify ")).isEqualTo("/notify/*");
    }
}
