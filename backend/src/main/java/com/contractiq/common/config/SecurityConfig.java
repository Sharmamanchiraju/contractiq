package com.contractiq.common.config;

import com.contractiq.audit.AuditService;
import com.contractiq.common.security.JwtAuthenticationFilter;
import com.contractiq.common.security.JwtProperties;
import com.contractiq.common.security.JwtService;
import com.contractiq.common.security.ProblemAccessDeniedHandler;
import com.contractiq.common.security.ProblemAuthenticationEntryPoint;
import com.contractiq.common.security.RequestInfoFilter;
import com.contractiq.common.security.RequestSizeLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The single place that decides who may call what: stateless JWT authentication, role rules,
 * CORS, security headers and the request size limit. Anything not listed as public needs a login.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class, RequestLimitProperties.class,
        com.contractiq.auth.LoginLimitProperties.class})
public class SecurityConfig {

    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, ObjectMapper mapper,
                                            RequestLimitProperties limits, AuditService audit,
                                            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
            throws Exception {
        // Swagger UI (dev only) needs scripts and styles, so the strict CSP skips its paths.
        var swaggerPaths = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"),
                PathPatternRequestMatcher.withDefaults().matcher("/v3/api-docs/**"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(cors))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(swaggerPaths),
                                new StaticHeadersWriter("Content-Security-Policy", CONTENT_SECURITY_POLICY))))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint(mapper))
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(mapper, audit)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new RequestSizeLimitFilter(limits.maxRequestBytes(), mapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class)
                // First in the chain, so every answer (even 401, 403 and 413) has a request id.
                .addFilterBefore(new RequestInfoFilter(), DisableEncodeUrlFilter.class);
        return http.build();
    }
}
