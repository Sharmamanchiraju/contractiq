package com.contractiq.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Describes the API in the generated OpenAPI document and adds a Bearer-token "Authorize" button
 * to Swagger UI. Dev profile only, like the Swagger UI itself.
 */
@Configuration
@Profile("dev")
public class OpenApiConfig {

    @Bean
    OpenAPI contractIqOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ContractIQ API").version("v1"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
