package com.nauta.takehome.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.nauta.takehome.application.IngestService
import com.nauta.takehome.infrastructure.messaging.EventBus
import com.nauta.takehome.infrastructure.web.EmailIngestRequest
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.util.Date
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.services.sqs.SqsClient

@TestConfiguration
@Profile("test")
class TestConfiguration {
    @Bean
    @Primary
    fun mockSqsClient(): SqsClient {
        return Mockito.mock(SqsClient::class.java)
    }

    @Bean
    @Primary
    fun testEventBus(
        ingestService: IngestService,
        objectMapper: ObjectMapper,
    ): EventBus {
        return object : EventBus {
            override fun publishIngest(
                tenantId: String,
                idempotencyKey: String?,
                rawPayload: String,
            ) {
                // For tests, process synchronously instead of queuing
                try {
                    val emailRequest = objectMapper.readValue(rawPayload, EmailIngestRequest::class.java)
                    ingestService.processEmailIngestRequest(tenantId, emailRequest)
                } catch (e: Exception) {
                    // Log and ignore processing errors in tests
                    println("Test EventBus: Failed to process ingest request: ${e.message}")
                }
            }
        }
    }

    @Bean
    fun testJwtTokenGenerator(): TestJwtTokenGenerator {
        return TestJwtTokenGenerator()
    }
}

class TestJwtTokenGenerator {
    private val testSecret = "test-secret-key-for-testing-purposes-minimum-32-chars"
    private val key = Keys.hmacShaKeyFor(testSecret.toByteArray(StandardCharsets.UTF_8))

    fun generateToken(
        tenantId: String,
        userId: String,
    ): String {
        return Jwts.builder()
            .setClaims(mapOf("tenant_id" to tenantId, "sub" to userId))
            .setIssuedAt(Date(System.currentTimeMillis()))
            .setExpiration(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000)) // 24 hours
            .signWith(key)
            .compact()
    }

    fun getTenant123Token(): String = generateToken("tenant-123", "user-123")

    fun getTenantAToken(): String = generateToken("tenant-aaa", "user-aaa")

    fun getTenantBToken(): String = generateToken("tenant-bbb", "user-bbb")
}
