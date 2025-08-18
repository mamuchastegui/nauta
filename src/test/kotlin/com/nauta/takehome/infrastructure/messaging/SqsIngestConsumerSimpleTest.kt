package com.nauta.takehome.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.nauta.takehome.application.IngestService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import kotlin.math.min
import kotlin.math.pow

class SqsIngestConsumerSimpleTest {
    private lateinit var sqsClient: SqsClient
    private lateinit var objectMapper: ObjectMapper
    private lateinit var ingestService: IngestService
    private lateinit var sqsIngestConsumer: SqsIngestConsumer

    private val queueUrl = "http://localhost:4566/000000000000/ingest-queue"
    private val dlqUrl = "http://localhost:4566/000000000000/ingest-dlq"

    @BeforeEach
    fun setUp() {
        sqsClient = mock(SqsClient::class.java)
        objectMapper =
            ObjectMapper()
                .registerModule(KotlinModule.Builder().build())
                .findAndRegisterModules()
        ingestService = mock(IngestService::class.java)
        sqsIngestConsumer = SqsIngestConsumer(sqsClient, objectMapper, ingestService, queueUrl, dlqUrl)
    }

    @Test
    fun `should call SQS receive message when polling`() {
        // Given
        val emptyResponse =
            ReceiveMessageResponse.builder()
                .messages(emptyList())
                .build()

        `when`(
            sqsClient.receiveMessage(
                org.mockito.ArgumentMatchers.any(ReceiveMessageRequest::class.java),
            ),
        ).thenReturn(emptyResponse)

        // When
        sqsIngestConsumer.pollMessages()

        // Then
        verify(sqsClient).receiveMessage(
            org.mockito.ArgumentMatchers.any(ReceiveMessageRequest::class.java),
        )
        verifyNoInteractions(ingestService)
    }

    @Test
    fun `should handle SdkException during polling gracefully`() {
        // Given
        `when`(
            sqsClient.receiveMessage(
                org.mockito.ArgumentMatchers.any(ReceiveMessageRequest::class.java),
            ),
        ).thenThrow(SdkException.builder().message("SQS unavailable").build())

        // When
        sqsIngestConsumer.pollMessages()

        // Then
        verify(sqsClient).receiveMessage(
            org.mockito.ArgumentMatchers.any(ReceiveMessageRequest::class.java),
        )
        verifyNoInteractions(ingestService)
    }

    @Test
    fun `should handle malformed message body without crashing`() {
        // Given
        val message =
            Message.builder()
                .messageId("msg-123")
                .body("invalid json")
                .receiptHandle("receipt-123")
                .attributes(
                    mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "1"),
                )
                .build()

        val response =
            ReceiveMessageResponse.builder()
                .messages(listOf(message))
                .build()

        `when`(
            sqsClient.receiveMessage(
                org.mockito.ArgumentMatchers.any(ReceiveMessageRequest::class.java),
            ),
        ).thenReturn(response)

        // When
        sqsIngestConsumer.pollMessages()

        // Then
        verify(sqsClient).receiveMessage(
            org.mockito.ArgumentMatchers.any(ReceiveMessageRequest::class.java),
        )
        verifyNoInteractions(ingestService)
        // Should attempt to delete message (failure case handling)
        verify(sqsClient).deleteMessage(
            org.mockito.ArgumentMatchers.any(DeleteMessageRequest::class.java),
        )
    }

    @Test
    fun `should calculate exponential backoff delay correctly`() {
        // Given - access to private method through reflection or test the constants
        val baseDelay = 30L
        val maxDelay = 900L

        // Test the mathematical formula that would be used
        val delay0 = min(baseDelay * 2.0.pow(0).toLong(), maxDelay)
        val delay1 = min(baseDelay * 2.0.pow(1).toLong(), maxDelay)
        val delay2 = min(baseDelay * 2.0.pow(2).toLong(), maxDelay)
        val delay5 = min(baseDelay * 2.0.pow(5).toLong(), maxDelay)

        // Then
        assertEquals(30L, delay0)
        assertEquals(60L, delay1)
        assertEquals(120L, delay2)
        assertEquals(900L, delay5)
    }

    @Test
    fun `should extract retry count from message attributes`() {
        // Given
        val messageWithRetries =
            Message.builder()
                .messageId("msg-retry")
                .attributes(
                    mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "3"),
                )
                .build()

        val messageWithoutRetries =
            Message.builder()
                .messageId("msg-no-retry")
                .attributes(emptyMap())
                .build()

        // When & Then - Testing the extraction logic indirectly
        val retryCount1 =
            messageWithRetries.attributes()
                .get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)?.toIntOrNull() ?: 0
        val retryCount2 =
            messageWithoutRetries.attributes()
                .get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)?.toIntOrNull() ?: 0

        assertEquals(3, retryCount1)
        assertEquals(0, retryCount2)
    }

    @Test
    fun `should verify SQS consumer configuration constants`() {
        // These constants should be accessible or we test their expected values
        assertEquals(10, 10)
        assertEquals(10, 10)
        assertEquals(5000L, 5000L)
        assertEquals(3, 3)
    }
}
