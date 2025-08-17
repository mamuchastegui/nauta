package com.nauta.takehome.infrastructure.messaging

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

@Component
class SqsEventBus(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    @Value("\${app.sqs.ingest-queue-url}") private val ingestQueueUrl: String,
) : EventBus {
    private val logger = LoggerFactory.getLogger(SqsEventBus::class.java)

    override fun publishIngest(
        tenantId: String,
        idempotencyKey: String?,
        rawPayload: String,
    ) {
        try {
            val message =
                IngestQueueMessage(
                    messageId = UUID.randomUUID().toString(),
                    tenantId = tenantId,
                    idempotencyKey = idempotencyKey,
                    rawPayload = rawPayload,
                    timestamp = Instant.now(),
                )

            val messageBody = objectMapper.writeValueAsString(message)

            val sendMessageRequest =
                SendMessageRequest.builder()
                    .queueUrl(ingestQueueUrl)
                    .messageBody(messageBody)
                    .build()

            val response = sqsClient.sendMessage(sendMessageRequest)
            logger.info("Message sent to SQS: messageId=${response.messageId()}, tenantId=$tenantId")
        } catch (e: JsonProcessingException) {
            logger.error("Failed to serialize message for tenantId=$tenantId", e)
            throw IllegalArgumentException("Invalid message format", e)
        } catch (e: SdkException) {
            logger.error("AWS SQS error for tenantId=$tenantId: ${e.message}", e)
            throw e
        }
    }
}

data class IngestQueueMessage(
    val messageId: String,
    val tenantId: String,
    val idempotencyKey: String?,
    val rawPayload: String,
    val timestamp: Instant,
)
