package com.nauta.takehome.config

import com.nauta.takehome.infrastructure.messaging.EventBus
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
    fun mockEventBus(): EventBus {
        return object : EventBus {
            override fun publishIngest(tenantId: String, idempotencyKey: String?, rawPayload: String) {
                // Mock implementation - do nothing
            }
        }
    }
}