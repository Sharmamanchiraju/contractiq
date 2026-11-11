package com.contractiq.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser origins allowed to call the API, bound from {@code app.cors.allowed-origins}.
 * A wildcard origin is refused at startup.
 *
 * @param allowedOrigins exact origins such as {@code http://localhost:5173}
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("app.cors.allowed-origins must list exact origins, not wildcards");
        }
    }
}
