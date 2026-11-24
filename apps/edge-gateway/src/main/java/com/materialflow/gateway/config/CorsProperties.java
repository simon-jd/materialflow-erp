package com.materialflow.gateway.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exact browser origins allowed to call {@code /api/**} cross-origin, e.g. a local Vite dev server.
 * Same-origin deployments (CloudFront routing {@code /api/*}, the console's nginx proxy) need none.
 * Wildcards and anything other than {@code scheme://host[:port]} are rejected at startup.
 */
@ConfigurationProperties("materialflow.gateway.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedOrigins.forEach(CorsProperties::requireExactOrigin);
    }

    private static void requireExactOrigin(String origin) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw invalid(origin);
        }
        boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        boolean bareOrigin = (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
        if (origin.contains("*") || !httpScheme || uri.getHost() == null || !bareOrigin) {
            throw invalid(origin);
        }
    }

    private static IllegalArgumentException invalid(String origin) {
        return new IllegalArgumentException(
                "materialflow.gateway.cors.allowed-origins entries must be exact scheme://host[:port] origins: "
                        + origin);
    }
}
