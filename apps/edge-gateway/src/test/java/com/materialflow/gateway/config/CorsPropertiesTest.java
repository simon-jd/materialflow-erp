package com.materialflow.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorsPropertiesTest {
    @Test
    void acceptsExactOriginsAndDefaultsToNone() {
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(List.of("http://localhost:5173", "https://console.example.test:8443"))
                .allowedOrigins()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.test", "http://localhost:5173/", "http://localhost:5173/app",
            "localhost:5173", "ftp://localhost", "http://user@localhost:5173", "http://localhost:5173?x=1"})
    void rejectsWildcardsAndAnythingButABareOrigin(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact scheme://host[:port]");
    }
}
