package com.nauta.takehome.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.time.Instant

class SqsEventBusSimpleTest {
    private lateinit var sqsClient: SqsClient
    private lateinit var objectMapper: ObjectMapper
    private lateinit var sqsEventBus: SqsEventBus
    private val queueUrl = "http://localhost:4566/000000000000/ingest-queue"

    @BeforeEach
    fun setUp() {
        sqsClient = mock(SqsClient::class.java)
        objectMapper =
            ObjectMapper()
                .registerModule(KotlinModule.Builder().build())
                .findAndRegisterModules()
        sqsEventBus = SqsEventBus(sqsClient, objectMapper, queueUrl)
    }

    @Test
    fun `should call SQS client when publishing message`() {
        // Given
        val tenantId = "tenant-123"
        val rawPayload = """{"test": "data"}"""

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-789")
                .build()

        `when`(
            sqsClient.sendMessage(
                org.mockito.ArgumentMatchers.any(SendMessageRequest::class.java),
            ),
        ).thenReturn(mockResponse)

        // When
        sqsEventBus.publishIngest(tenantId, null, rawPayload)

        // Then
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(sqsClient).sendMessage(captor.capture())

        val sentRequest = captor.value
        assertEquals(queueUrl, sentRequest.queueUrl())
        assertNotNull(sentRequest.messageBody())
    }

    @Test
    fun `should handle SdkException and rethrow`() {
        // Given
        val tenantId = "tenant-123"
        val rawPayload = """{"test": "data"}"""

        val sdkException = SdkException.builder().message("SQS unavailable").build()
        `when`(
            sqsClient.sendMessage(
                org.mockito.ArgumentMatchers.any(SendMessageRequest::class.java),
            ),
        ).thenThrow(sdkException)

        // When & Then
        val thrownException =
            assertThrows(SdkException::class.java) {
                sqsEventBus.publishIngest(tenantId, null, rawPayload)
            }

        assertEquals("SQS unavailable", thrownException.message)
    }

    @Test
    fun `should create IngestQueueMessage with correct properties`() {
        // Given
        val messageId = "msg-123"
        val tenantId = "tenant-456"
        val idempotencyKey = "key-789"
        val rawPayload = """{"order": "data"}"""
        val timestamp = Instant.now()

        // When
        val message =
            IngestQueueMessage(
                messageId = messageId,
                tenantId = tenantId,
                idempotencyKey = idempotencyKey,
                rawPayload = rawPayload,
                timestamp = timestamp,
            )

        // Then
        assertEquals(messageId, message.messageId)
        assertEquals(tenantId, message.tenantId)
        assertEquals(idempotencyKey, message.idempotencyKey)
        assertEquals(rawPayload, message.rawPayload)
        assertEquals(timestamp, message.timestamp)
    }

    @Test
    fun `should create SqsCircuitOpenException with correct properties`() {
        // Given
        val message = "Circuit breaker is open"
        val cause = RuntimeException("Original error")

        // When
        val exception = SqsCircuitOpenException(message, cause)

        // Then
        assertEquals(message, exception.message)
        assertEquals(cause, exception.cause)
    }
}
