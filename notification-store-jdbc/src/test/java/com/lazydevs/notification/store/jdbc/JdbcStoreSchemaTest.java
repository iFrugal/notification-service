package com.lazydevs.notification.store.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcStoreSchemaTest {

    @Test
    void shippedResourceIsTheDefaultRendering() throws IOException {
        String resource;
        try (InputStream in = getClass().getResourceAsStream("/db/postgresql/notification-store.sql")) {
            assertThat(in).as("db/postgresql/notification-store.sql on the classpath").isNotNull();
            resource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(resource).isEqualTo(JdbcStoreSchema.postgresql(JdbcStoreTables.defaults()).ddl());
    }

    @Test
    void readmeReproducesTheReferenceDdl() throws IOException {
        // Tests run with the module directory as the working directory.
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme).contains(JdbcStoreSchema.postgresql(JdbcStoreTables.defaults()).ddl());
    }

    @Test
    void customPrefixAndSchemaQualifyTablesButNotIndexNames() {
        JdbcStoreSchema schema = JdbcStoreSchema.postgresql("ns_", "notify");

        assertThat(schema.statements()).hasSize(10);
        assertThat(schema.ddl())
                .contains("CREATE TABLE IF NOT EXISTS notify.ns_idempotency (")
                .contains("CREATE TABLE IF NOT EXISTS notify.ns_dead_letter (")
                .contains("CREATE TABLE IF NOT EXISTS notify.ns_delivery_event (")
                .contains("CREATE INDEX IF NOT EXISTS ns_dead_letter_expires_at_idx ON notify.ns_dead_letter (expires_at)")
                .contains("CONSTRAINT ns_idempotency_pk PRIMARY KEY")
                .doesNotContainPattern("notification_(idempotency|dead_letter|delivery_event)")
                .doesNotContain("notify.ns_dead_letter_expires_at_idx");
    }

    @Test
    void emptyPrefixAndBlankSchemaAreAllowed() {
        JdbcStoreTables tables = new JdbcStoreTables("  ", "");

        assertThat(tables.schema()).isNull();
        assertThat(tables.deadLetter()).isEqualTo("dead_letter");
    }

    @Test
    void unsafeIdentifiersAreRejected() {
        assertThatThrownBy(() -> new JdbcStoreTables("public; DROP TABLE x", "notification_"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");
        assertThatThrownBy(() -> new JdbcStoreTables(null, "bad-prefix"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("table-prefix");
        assertThatThrownBy(() -> new JdbcStoreTables(null, "1abc_"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("table-prefix");
    }
}
