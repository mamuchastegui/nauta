package com.nauta.takehome.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/**
 * Unit tests for messaging components focusing on serialization,
 * parsing logic, and data transformation without external dependencies.
 */
class SqsMessagingUnitTest {
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        objectMapper =
            ObjectMapper()
                .registerModule(KotlinModule.Builder().build())
                .findAndRegisterModules()
    }

    @Test
    fun `should serialize and deserialize IngestQueueMessage correctly`() {
        // Given
        val originalMessage =
            IngestQueueMessage(
                messageId = "msg-123",
                tenantId = "tenant-456",
                idempotencyKey = "key-789",
                rawPayload = """{
                "booking": "BK001",
                "orders": [{"purchase": "PO001", "invoices": [{"invoice": "INV001"}]}],
                "containers": [{"container": "MSCU6639871"}]
            }""",
                timestamp = Instant.parse("2023-01-01T10:00:00Z"),
            )

        // When
        val serialized = objectMapper.writeValueAsString(originalMessage)
        val deserialized = objectMapper.readValue(serialized, IngestQueueMessage::class.java)

        // Then
        assertEquals(originalMessage.messageId, deserialized.messageId)
        assertEquals(originalMessage.tenantId, deserialized.tenantId)
        assertEquals(originalMessage.idempotencyKey, deserialized.idempotencyKey)
        assertEquals(
            originalMessage.rawPayload.replace("\\s".toRegex(), ""),
            deserialized.rawPayload.replace("\\s".toRegex(), ""),
        )
        assertEquals(originalMessage.timestamp, deserialized.timestamp)
    }

    @Test
    fun `should handle IngestQueueMessage with null idempotency key`() {
        // Given
        val message =
            IngestQueueMessage(
                messageId = "msg-no-key",
                tenantId = "tenant-123",
                idempotencyKey = null,
                rawPayload = """{"booking": "BK002"}""",
                timestamp = Instant.now(),
            )

        // When
        val serialized = objectMapper.writeValueAsString(message)
        val deserialized = objectMapper.readValue(serialized, IngestQueueMessage::class.java)

        // Then
        assertEquals(message.messageId, deserialized.messageId)
        assertEquals(message.tenantId, deserialized.tenantId)
        assertNull(deserialized.idempotencyKey)
        assertEquals(message.rawPayload, deserialized.rawPayload)
    }

    @Test
    fun `should handle empty rawPayload in IngestQueueMessage`() {
        // Given
        val message =
            IngestQueueMessage(
                messageId = "msg-empty",
                tenantId = "tenant-empty",
                idempotencyKey = "key-empty",
                rawPayload = "",
                timestamp = Instant.now(),
            )

        // When
        val serialized = objectMapper.writeValueAsString(message)
        val deserialized = objectMapper.readValue(serialized, IngestQueueMessage::class.java)

        // Then
        assertEquals("", deserialized.rawPayload)
        assertEquals(message.tenantId, deserialized.tenantId)
    }

    @Test
    fun `should parse rawPayload JSON correctly for booking only`() {
        // Given
        val rawPayload = """{"booking": "BK003"}"""

        // When
        val jsonNode = objectMapper.readTree(rawPayload)
        val bookingValue = jsonNode.get("booking")?.asText()

        // Then
        assertEquals("BK003", bookingValue)
        assertNull(jsonNode.get("orders"))
        assertNull(jsonNode.get("containers"))
    }

    @Test
    fun `should parse rawPayload JSON correctly for orders with invoices`() {
        // Given
        val rawPayload = """{
            "orders": [
                {
                    "purchase": "PO001",
                    "invoices": [
                        {"invoice": "INV001"},
                        {"invoice": "INV002"}
                    ]
                },
                {
                    "purchase": "PO002",
                    "invoices": []
                }
            ]
        }"""

        // When
        val jsonNode = objectMapper.readTree(rawPayload)
        val ordersNode = jsonNode.get("orders")

        // Then
        assertNotNull(ordersNode)
        assertTrue(ordersNode.isArray)
        assertEquals(2, ordersNode.size())

        // First order
        val firstOrder = ordersNode.get(0)
        assertEquals("PO001", firstOrder.get("purchase").asText())
        val firstInvoices = firstOrder.get("invoices")
        assertEquals(2, firstInvoices.size())
        assertEquals("INV001", firstInvoices.get(0).get("invoice").asText())
        assertEquals("INV002", firstInvoices.get(1).get("invoice").asText())

        // Second order
        val secondOrder = ordersNode.get(1)
        assertEquals("PO002", secondOrder.get("purchase").asText())
        val secondInvoices = secondOrder.get("invoices")
        assertEquals(0, secondInvoices.size())
    }

    @Test
    fun `should parse rawPayload JSON correctly for containers`() {
        // Given
        val rawPayload = """{
            "containers": [
                {"container": "MSCU6639871"},
                {"container": "TCLU1234567"},
                {"container": "GESU9876543"}
            ]
        }"""

        // When
        val jsonNode = objectMapper.readTree(rawPayload)
        val containersNode = jsonNode.get("containers")

        // Then
        assertNotNull(containersNode)
        assertTrue(containersNode.isArray)
        assertEquals(3, containersNode.size())
        assertEquals("MSCU6639871", containersNode.get(0).get("container").asText())
        assertEquals("TCLU1234567", containersNode.get(1).get("container").asText())
        assertEquals("GESU9876543", containersNode.get(2).get("container").asText())
    }

    @Test
    fun `should parse complex rawPayload with all elements`() {
        // Given
        val rawPayload = """{
            "booking": "BK004",
            "orders": [
                {
                    "purchase": "PO003",
                    "invoices": [{"invoice": "INV003"}]
                }
            ],
            "containers": [
                {"container": "MSKU1111111"}
            ]
        }"""

        // When
        val jsonNode = objectMapper.readTree(rawPayload)

        // Then
        assertEquals("BK004", jsonNode.get("booking").asText())

        val ordersNode = jsonNode.get("orders")
        assertEquals(1, ordersNode.size())
        assertEquals("PO003", ordersNode.get(0).get("purchase").asText())

        val invoicesNode = ordersNode.get(0).get("invoices")
        assertEquals(1, invoicesNode.size())
        assertEquals("INV003", invoicesNode.get(0).get("invoice").asText())

        val containersNode = jsonNode.get("containers")
        assertEquals(1, containersNode.size())
        assertEquals("MSKU1111111", containersNode.get(0).get("container").asText())
    }

    @Test
    fun `should handle empty JSON object in rawPayload`() {
        // Given
        val rawPayload = "{}"

        // When
        val jsonNode = objectMapper.readTree(rawPayload)

        // Then
        assertNull(jsonNode.get("booking"))
        assertNull(jsonNode.get("orders"))
        assertNull(jsonNode.get("containers"))
        assertTrue(jsonNode.isEmpty)
    }

    @Test
    fun `should handle malformed JSON in rawPayload gracefully`() {
        // Given
        val malformedPayload = """{
            "booking": "BK005",
            "orders": [
                {
                    "purchase": "PO004"
                    // Missing comma - invalid JSON
                    "invoices": []
                }
            ]
        }"""

        // When & Then
        assertThrows(com.fasterxml.jackson.core.JsonParseException::class.java) {
            objectMapper.readTree(malformedPayload)
        }
    }

    @Test
    fun `should handle JSON with missing required fields`() {
        // Given
        val incompletePayload = """{
            "orders": [
                {
                    "invoices": [{"invoice": "INV004"}]
                }
            ]
        }"""

        // When
        val jsonNode = objectMapper.readTree(incompletePayload)
        val ordersNode = jsonNode.get("orders")
        val firstOrder = ordersNode.get(0)

        // Then
        assertNull(firstOrder.get("purchase")) // Missing purchase field
        assertNotNull(firstOrder.get("invoices"))
        assertEquals(1, firstOrder.get("invoices").size())
    }

    @Test
    fun `should demonstrate retry count calculation logic`() {
        // Given - Simulating the logic from SqsIngestConsumer
        val baseDelay = 30L
        val maxDelay = 900L

        // When & Then - Test exponential backoff calculation
        val delay0 = min(baseDelay * 2.0.pow(0).toLong(), maxDelay)
        val delay1 = min(baseDelay * 2.0.pow(1).toLong(), maxDelay)
        val delay2 = min(baseDelay * 2.0.pow(2).toLong(), maxDelay)
        val delay3 = min(baseDelay * 2.0.pow(3).toLong(), maxDelay)
        val delay10 = min(baseDelay * 2.0.pow(10).toLong(), maxDelay)

        assertEquals(30L, delay0) // 30 * 2^0 = 30
        assertEquals(60L, delay1) // 30 * 2^1 = 60
        assertEquals(120L, delay2) // 30 * 2^2 = 120
        assertEquals(240L, delay3) // 30 * 2^3 = 240
        assertEquals(900L, delay10) // 30 * 2^10 = 30720, but capped at 900
    }

    @Test
    fun `should test retry count extraction logic`() {
        // Given - Simulating message attribute parsing
        val attributes =
            mapOf(
                "ApproximateReceiveCount" to "3",
                "SentTimestamp" to "1640995200000",
            )

        // When
        val retryCount = attributes["ApproximateReceiveCount"]?.toIntOrNull() ?: 0

        // Then
        assertEquals(3, retryCount)
    }

    @Test
    fun `should test retry count extraction with invalid data`() {
        // Given
        val attributesWithInvalidCount = mapOf("ApproximateReceiveCount" to "invalid")
        val attributesWithoutCount = emptyMap<String, String>()

        // When & Then
        assertEquals(0, attributesWithInvalidCount["ApproximateReceiveCount"]?.toIntOrNull() ?: 0)
        assertEquals(0, attributesWithoutCount["ApproximateReceiveCount"]?.toIntOrNull() ?: 0)
    }

    @Test
    fun `should test DLQ message structure`() {
        // Given - Simulating DLQ message creation logic
        val originalMessageBody = """{"test": "message"}"""
        val error = RuntimeException("Processing failed")
        val timestamp = System.currentTimeMillis()

        val dlqMessage =
            mapOf(
                "originalMessage" to originalMessageBody,
                "error" to error.message,
                "timestamp" to timestamp,
            )

        // When
        val serializedDlqMessage = objectMapper.writeValueAsString(dlqMessage)
        val deserializedDlqMessage = objectMapper.readValue(serializedDlqMessage, Map::class.java)

        // Then
        assertEquals(originalMessageBody, deserializedDlqMessage["originalMessage"])
        assertEquals("Processing failed", deserializedDlqMessage["error"])
        // Jackson converts numbers to appropriate type - could be Long or Double
        assertTrue(deserializedDlqMessage["timestamp"] is Number)
        assertEquals(timestamp.toDouble(), (deserializedDlqMessage["timestamp"] as Number).toDouble())
    }

    @Test
    fun `should validate JSON payload structure expectations`() {
        // Test various payload structures that the consumer should handle
        val validPayloads =
            listOf(
                """{"booking": "BK001"}""",
                """{"orders": []}""",
                """{"containers": []}""",
                """{"booking": "BK001", "orders": [], "containers": []}""",
                """{}""",
            )

        validPayloads.forEach { payload ->
            // Should not throw exception
            val jsonNode = objectMapper.readTree(payload)
            assertTrue(jsonNode.isObject, "Payload should be valid JSON object: $payload")
        }
    }

    @Test
    fun `should handle SqsCircuitOpenException creation and properties`() {
        // Given
        val errorMessage = "SQS circuit breaker is open - message queuing unavailable"
        val originalCause = RuntimeException("SQS connection timeout")

        // When
        val circuitException = SqsCircuitOpenException(errorMessage, originalCause)

        // Then
        assertEquals(errorMessage, circuitException.message)
        assertEquals(originalCause, circuitException.cause)
        assertTrue(circuitException is RuntimeException)
    }
}
