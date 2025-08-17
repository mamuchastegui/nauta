package com.nauta.takehome.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.net.URI
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient

@Configuration
@EnableScheduling
class ApplicationConfig {
    @Bean
    fun objectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            registerModule(kotlinModule())
            findAndRegisterModules()
        }
    }

    @Bean
    fun sqsClient(
        @Value("\${app.sqs.endpoint:}") sqsEndpoint: String,
        @Value("\${app.sqs.ingest-queue-url:}") queueUrl: String,
        @Value("\${app.aws.region:us-east-1}") region: String,
        @Value("\${spring.profiles.active:}") activeProfile: String,
    ): SqsClient {
        val builder =
            SqsClient.builder()
                .region(Region.of(region))

        // Determine endpoint: use explicit endpoint or extract from queue URL
        val effectiveEndpoint = when {
            sqsEndpoint.isNotBlank() -> sqsEndpoint
            queueUrl.contains("localhost") -> {
                // Extract endpoint from LocalStack queue URL (domain or path strategy)
                val regex = """(https?://[^/]+)""".toRegex()
                regex.find(queueUrl)?.groupValues?.get(1) ?: "http://localhost:4566"
            }
            else -> ""
        }

        if (effectiveEndpoint.isNotBlank()) {
            builder.endpointOverride(URI.create(effectiveEndpoint))
        }

        // For local profile, use dummy credentials to avoid AWS SSO conflicts
        if (activeProfile.contains("local") && effectiveEndpoint.contains("localhost")) {
            builder.credentialsProvider {
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")
            }
        }

        return builder.build()
    }
}
