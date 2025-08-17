package com.nauta.takehome.infrastructure.messaging

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nauta.takehome.application.BookingData
import com.nauta.takehome.application.ContainerData
import com.nauta.takehome.application.IngestMessage
import com.nauta.takehome.application.IngestService
import com.nauta.takehome.application.InvoiceData
import com.nauta.takehome.application.OrderData
import kotlin.math.min
import kotlin.math.pow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

@Component
@ConditionalOnProperty(name = ["app.sqs.consumer.enabled"], havingValue = "true", matchIfMissing = true)
class SqsIngestConsumer(
    private val sqsClient: SqsClient,
    private val objectMapper: ObjectMapper,
    private val ingestService: IngestService,
    @Value("\${app.sqs.ingest-queue-url}") private val ingestQueueUrl: String,
    @Value("\${app.sqs.ingest-dlq-url:}") private val dlqUrl: String,
) {
    private val logger = LoggerFactory.getLogger(SqsIngestConsumer::class.java)

    companion object {
        private const val MAX_RETRIES = 3
        private const val POLLING_DELAY_MS = 5000L
        private const val MAX_MESSAGES_PER_POLL = 10
        private const val LONG_POLL_WAIT_TIME_SECONDS = 10
        private const val BASE_RETRY_DELAY_SECONDS = 30L
        private const val MAX_RETRY_DELAY_SECONDS = 900L
    }

    @Scheduled(fixedDelay = POLLING_DELAY_MS)
    fun pollMessages() {
        try {
            val receiveRequest =
                ReceiveMessageRequest.builder()
                    .queueUrl(ingestQueueUrl)
                    .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
                    .waitTimeSeconds(LONG_POLL_WAIT_TIME_SECONDS)
                    .messageAttributeNames("All")
                    .build()

            val response = sqsClient.receiveMessage(receiveRequest)

            response.messages().forEach { message ->
                processMessage(message)
            }
        } catch (e: SdkException) {
            logger.error("AWS SQS error during message polling: ${e.message}", e)
        }
    }

    private fun processMessage(message: Message) {
        try {
            val queueMessage = objectMapper.readValue(message.body(), IngestQueueMessage::class.java)
            logger.info("Processing message: ${queueMessage.messageId} for tenant: ${queueMessage.tenantId}")

            // Parse rawPayload into IngestMessage and process it
            val ingestMessage = parseRawPayload(queueMessage.rawPayload, queueMessage.tenantId)

            // Process the message using IngestService
            ingestService.processIngestMessage(ingestMessage)

            deleteMessage(message)
            logger.info(
                "Successfully processed message: ${queueMessage.messageId} for tenant: ${queueMessage.tenantId}",
            )
        } catch (e: JsonProcessingException) {
            logger.error("Failed to parse message body for message: ${message.messageId()}", e)
            handleFailedMessage(message, e)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid message data for message: ${message.messageId()}", e)
            handleFailedMessage(message, e)
        } catch (e: SdkException) {
            logger.error("AWS SDK error processing message: ${message.messageId()}", e)
            handleFailedMessage(message, e)
        } catch (e: NoSuchElementException) {
            logger.error("Missing required field processing message: ${message.messageId()}", e)
            handleFailedMessage(message, e)
        } catch (e: ClassCastException) {
            logger.error("Type conversion error processing message: ${message.messageId()}", e)
            handleFailedMessage(message, e)
        }
    }

    private fun parseRawPayload(
        rawPayload: String,
        tenantId: String,
    ): IngestMessage {
        try {
            val jsonNode = objectMapper.readTree(rawPayload)
            val booking = parseBookingData(jsonNode)

            // Extract orders data
            val orders =
                jsonNode.get("orders")?.map { orderNode ->
                    val purchaseRef = orderNode.get("purchase").asText()

                    val invoices =
                        orderNode.get("invoices")?.map { invoiceNode ->
                            val invoiceRef = invoiceNode.get("invoice").asText()
                            InvoiceData(invoiceRef)
                        } ?: emptyList()

                    OrderData(
                        purchaseRef = purchaseRef,
                        invoices = invoices,
                    )
                } ?: emptyList()

            // Extract containers data
            val containers =
                jsonNode.get("containers")?.map { containerNode ->
                    val containerRef = containerNode.get("container").asText()
                    ContainerData(containerRef)
                } ?: emptyList()

            return IngestMessage(
                tenantId = tenantId,
                booking = booking,
                orders = orders,
                containers = containers,
            )
        } catch (e: JsonProcessingException) {
            logger.error("Failed to parse rawPayload: $rawPayload", e)
            throw IllegalArgumentException("Invalid rawPayload format: ${e.message}", e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Need broad catch for unknown JSON parsing errors
            logger.error("Error parsing rawPayload: $rawPayload", e)
            throw IllegalArgumentException("Invalid rawPayload format: ${e.message}", e)
        }
    }

    private fun parseBookingData(jsonNode: JsonNode): BookingData? {
        return jsonNode.get("booking")?.let { bookingNode ->
            BookingData(bookingNode.asText())
        }
    }

    private fun handleFailedMessage(
        message: Message,
        error: Exception,
    ) {
        val retryCount = getRetryCount(message)

        if (retryCount < MAX_RETRIES) {
            val delaySeconds = calculateBackoffDelay(retryCount)
            logger.warn("Retrying message ${message.messageId()} in $delaySeconds seconds (attempt ${retryCount + 1})")

            // Delete message to prevent reprocessing in current implementation
            // Future: implement visibility timeout extension for proper retry
            deleteMessage(message)
        } else {
            logger.error("Max retries exceeded for message ${message.messageId()}, sending to DLQ")
            sendToDlq(message, error)
            deleteMessage(message)
        }
    }

    private fun getRetryCount(message: Message): Int {
        return message.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]?.toIntOrNull() ?: 0
    }

    private fun calculateBackoffDelay(retryCount: Int): Long {
        return min(BASE_RETRY_DELAY_SECONDS * 2.0.pow(retryCount).toLong(), MAX_RETRY_DELAY_SECONDS)
    }

    private fun sendToDlq(
        message: Message,
        error: Exception,
    ) {
        try {
            val dlqMessage =
                mapOf(
                    "originalMessage" to message.body(),
                    "error" to error.message,
                    "timestamp" to System.currentTimeMillis(),
                )

            val sendRequest =
                SendMessageRequest.builder()
                    .queueUrl(dlqUrl)
                    .messageBody(objectMapper.writeValueAsString(dlqMessage))
                    .build()

            sqsClient.sendMessage(sendRequest)
            logger.info("Message sent to DLQ: ${message.messageId()}")
        } catch (e: JsonProcessingException) {
            logger.error("Failed to serialize DLQ message for: ${message.messageId()}", e)
        } catch (e: SdkException) {
            logger.error("Failed to send message to DLQ: ${message.messageId()}", e)
        }
    }

    private fun deleteMessage(message: Message) {
        try {
            val deleteRequest =
                DeleteMessageRequest.builder()
                    .queueUrl(ingestQueueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build()

            sqsClient.deleteMessage(deleteRequest)
        } catch (e: SdkException) {
            logger.error("Failed to delete message: ${message.messageId()}", e)
        }
    }
}
