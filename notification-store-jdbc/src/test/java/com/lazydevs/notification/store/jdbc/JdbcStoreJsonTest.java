package com.lazydevs.notification.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazydevs.notification.api.model.NotificationResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the stored JSON format of the module-owned mapper. */
class JdbcStoreJsonTest {

    private final ObjectMapper json = JdbcStoreJson.create();

    @Test
    void writesIsoDatesAndRoundTrips() throws Exception {
        NotificationResponse response = NotificationResponse.success(
                PostgresTestSupport.request("acme", "req-1"), "smtp", "msg-1");

        String body = json.writeValueAsString(response);

        assertThat(body).contains("\"processedAt\":\"" + response.processedAt() + "\"");
        assertThat(json.readValue(body, NotificationResponse.class)).isEqualTo(response);
    }

    @Test
    void ignoresUnknownPropertiesWrittenByANewerVersion() throws Exception {
        NotificationResponse read = json.readValue(
                "{\"requestId\":\"req-1\",\"status\":\"SENT\",\"addedLater\":42}", NotificationResponse.class);

        assertThat(read.requestId()).isEqualTo("req-1");
    }

    @Test
    void eachCallReturnsAnIndependentMapper() {
        assertThat(JdbcStoreJson.create()).isNotSameAs(JdbcStoreJson.create());
    }
}
