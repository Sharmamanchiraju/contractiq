package com.contractiq.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Request size limits bound from {@code app.limits.*}.
 *
 * @param maxRequestBytes largest accepted request body, in bytes (default 1 MiB)
 */
@ConfigurationProperties(prefix = "app.limits")
public record RequestLimitProperties(@DefaultValue("1048576") long maxRequestBytes) {
}
