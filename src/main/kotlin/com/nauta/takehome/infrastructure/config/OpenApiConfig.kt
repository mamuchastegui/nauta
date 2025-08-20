package com.nauta.takehome.infrastructure.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Nauta Logistics API")
                    .description(
                        """
                        REST API for Nauta's logistics management system.
                        
                        This API allows you to:
                        - Query maritime containers and their associated orders
                        - Query purchase orders and their associated containers  
                        - Ingest logistics data received via email
                        
                        ## Authentication
                        All endpoints require JWT authentication via Authorization: Bearer <token> header.
                        The token must include the 'tenant_id' field for multi-tenancy support.
                        
                        ## Formats
                        - Dates: ISO 8601 (2024-08-19T15:30:00.123456Z)
                        - Containers: ISO 6346 (4 letters + 7 digits, e.g: TCLU7654321)
                        - IDs: Alphanumeric strings with descriptive prefixes
                        
                        ## Documentation Structure
                        This API documentation combines:
                        - Basic configuration from code annotations
                        - Detailed examples and schemas from external YAML files
                        - See: src/main/resources/openapi/nauta-logistics-api.yml for complete specification
                        """.trimIndent(),
                    )
                    .version("1.0.0")
                    .contact(
                        Contact()
                            .name("Nauta Engineering Team")
                            .email("engineering@nauta.com")
                            .url("https://nauta.com"),
                    ),
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        "bearerAuth",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("JWT token for authentication. Must include tenant information."),
                    ),
            )
            .addSecurityItem(
                SecurityRequirement().addList("bearerAuth"),
            )
    }
}
