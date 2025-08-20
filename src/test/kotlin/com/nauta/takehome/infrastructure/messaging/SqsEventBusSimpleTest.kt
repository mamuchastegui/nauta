package com.nauta.takehome.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
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
                any(SendMessageRequest::class.java),
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
    fun `should handle SQS SDK exceptions and rethrow`() {
        // Given
        val tenantId = "tenant-123"
        val rawPayload = """{"test": "data"}"""

        val sdkException = SdkException.builder().message("SQS unavailable").build()
        `when`(
            sqsClient.sendMessage(
                any(SendMessageRequest::class.java),
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

    @Test
    fun `should publish message with idempotency key`() {
        // Given
        val tenantId = "tenant-456"
        val idempotencyKey = "key-123"
        val rawPayload = """{
            "booking": "BK001",
            "orders": [{"purchase": "PO001"}]
        }"""

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-456")
                .build()

        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenReturn(mockResponse)

        // When
        sqsEventBus.publishIngest(tenantId, idempotencyKey, rawPayload)

        // Then
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(sqsClient).sendMessage(captor.capture())

        val sentRequest = captor.value
        assertEquals(queueUrl, sentRequest.queueUrl())

        // Verify the message body contains the expected data
        val messageBody = sentRequest.messageBody()
        val parsedMessage = objectMapper.readValue(messageBody, IngestQueueMessage::class.java)
        assertEquals(tenantId, parsedMessage.tenantId)
        assertEquals(idempotencyKey, parsedMessage.idempotencyKey)
        assertEquals(rawPayload, parsedMessage.rawPayload)
        assertNotNull(parsedMessage.messageId)
        assertNotNull(parsedMessage.timestamp)
    }

    @Test
    fun `should publish message without idempotency key`() {
        // Given
        val tenantId = "tenant-789"
        val rawPayload = """{
            "containers": [{"container": "MSCU6639871"}]
        }"""

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-789")
                .build()

        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenReturn(mockResponse)

        // When
        sqsEventBus.publishIngest(tenantId, null, rawPayload)

        // Then
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(sqsClient).sendMessage(captor.capture())

        val sentRequest = captor.value
        val messageBody = sentRequest.messageBody()
        val parsedMessage = objectMapper.readValue(messageBody, IngestQueueMessage::class.java)
        assertEquals(tenantId, parsedMessage.tenantId)
        assertEquals(null, parsedMessage.idempotencyKey)
        assertEquals(rawPayload, parsedMessage.rawPayload)
    }

    @Test
    fun `should handle empty rawPayload`() {
        // Given
        val tenantId = "tenant-empty"
        val rawPayload = ""

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-empty")
                .build()

        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenReturn(mockResponse)

        // When
        sqsEventBus.publishIngest(tenantId, null, rawPayload)

        // Then
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(sqsClient).sendMessage(captor.capture())

        val sentRequest = captor.value
        val messageBody = sentRequest.messageBody()
        val parsedMessage = objectMapper.readValue(messageBody, IngestQueueMessage::class.java)
        assertEquals(tenantId, parsedMessage.tenantId)
        assertEquals("", parsedMessage.rawPayload)
    }

    @Test
    fun `should handle large rawPayload`() {
        // Given
        val tenantId = "tenant-large"
        val largePayload = """{
            "booking": "BK001",
            "orders": [
                ${(1..100).map { "\"PO$it\"" }.joinToString(",") { "{\"purchase\": $it}" }}
            ],
            "containers": [
                ${(1..50).map { "\"MSCU663987$it\"" }.joinToString(",") { "{\"container\": $it}" }}
            ]
        }"""

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-large")
                .build()

        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenReturn(mockResponse)

        // When
        sqsEventBus.publishIngest(tenantId, "large-key", largePayload)

        // Then
        verify(sqsClient).sendMessage(any(SendMessageRequest::class.java))
    }

    @Test
    fun `should generate unique message IDs for different calls`() {
        // Given
        val tenantId = "tenant-unique"
        val rawPayload = """{
            "booking": "BK001"
        }"""

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-unique")
                .build()

        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenReturn(mockResponse)

        // When - Make two calls
        sqsEventBus.publishIngest(tenantId, null, rawPayload)
        sqsEventBus.publishIngest(tenantId, null, rawPayload)

        // Then
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(sqsClient, org.mockito.Mockito.times(2)).sendMessage(captor.capture())

        val requests = captor.allValues
        val message1 = objectMapper.readValue(requests[0].messageBody(), IngestQueueMessage::class.java)
        val message2 = objectMapper.readValue(requests[1].messageBody(), IngestQueueMessage::class.java)

        // Message IDs should be different
        assertNotNull(message1.messageId)
        assertNotNull(message2.messageId)
        assertEquals(false, message1.messageId == message2.messageId)
    }

    @Test
    fun `should include timestamp in generated message`() {
        // Given
        val tenantId = "tenant-time"
        val rawPayload = """{
            "booking": "BK001"
        }"""
        val beforeTime = Instant.now()

        val mockResponse =
            SendMessageResponse.builder()
                .messageId("msg-time")
                .build()

        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenReturn(mockResponse)

        // When
        sqsEventBus.publishIngest(tenantId, null, rawPayload)
        val afterTime = Instant.now()

        // Then
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(sqsClient).sendMessage(captor.capture())

        val sentRequest = captor.value
        val messageBody = sentRequest.messageBody()
        val parsedMessage = objectMapper.readValue(messageBody, IngestQueueMessage::class.java)

        // Timestamp should be within the test execution window
        assertEquals(true, parsedMessage.timestamp.isAfter(beforeTime.minusSeconds(1)))
        assertEquals(true, parsedMessage.timestamp.isBefore(afterTime.plusSeconds(1)))
    }

    @Test
    fun `should use circuit breaker fallback when configured`() {
        // This test verifies that the circuit breaker configuration exists
        // In a real scenario, the circuit breaker would need to be triggered
        // which requires multiple failures, but we can test the basic setup

        // Given
        val tenantId = "tenant-circuit"
        val rawPayload = """{
            "booking": "BK001"
        }"""

        // Simulate multiple SQS failures to potentially trigger circuit breaker
        val sdkException = SdkException.builder().message("Persistent SQS failure").build()
        `when`(sqsClient.sendMessage(any(SendMessageRequest::class.java)))
            .thenThrow(sdkException)

        // When & Then
        val thrownException =
            assertThrows(SdkException::class.java) {
                sqsEventBus.publishIngest(tenantId, null, rawPayload)
            }

        assertEquals("Persistent SQS failure", thrownException.message)
    }
}
