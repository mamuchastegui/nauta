package com.nauta.takehome.infrastructure.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.nauta.takehome.infrastructure.messaging.EventBus
import com.nauta.takehome.infrastructure.security.TenantContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
@Tag(name = "Email")
class EmailController(
    private val eventBus: EventBus,
    private val tenantContext: TenantContext,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(EmailController::class.java)

    @PostMapping("/email")
    @Operation(
        summary = "Ingest data via email",
        description = "Processes and queues logistics data (containers and orders) received via email",
        requestBody =
            io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Email data containing containers and orders to process",
                required = true,
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                name = "Complete request",
                                summary = "Full email ingestion with booking, containers, and orders",
                                value = """{
  "booking": "HBLA240801001",
  "containers": [
    {"container": "TCLU7654321"},
    {"container": "MSKU9876543"}
  ],
  "orders": [
    {
      "purchase": "PO-NAU-2024-001234",
      "invoices": [
        {"invoice": "INV-NAU-2024-005678"}
      ]
    }
  ]
}""",
                            ),
                            ExampleObject(
                                name = "Minimal request",
                                summary = "Simple request with only containers",
                                value = """{
  "containers": [
    {"container": "TCLU7654321"}
  ]
}""",
                            ),
                        ],
                    ),
                ],
            ),
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "202",
                description = "Email queued for processing",
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                name = "Success response",
                                value =
                                    """{"message": "Email queued for processing", """ +
                                        """"idempotencyKey": "550e8400-e29b-41d4-a716-446655440000"}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "Unauthorized - missing tenant context",
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                name = "Missing tenant context",
                                value = """{"error": "No tenant context"}""",
                            ),
                            ExampleObject(
                                name = "Invalid JWT token",
                                value = """{"error": "Invalid token"}""",
                            ),
                        ],
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "Bad request - invalid input data",
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                name = "Invalid container format",
                                value =
                                    """{"error": "Container ID must match ISO 6346 format: """ +
                                        """4 letters + 7 digits"}""",
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    fun ingestEmail(
        @Valid @RequestBody request: EmailIngestRequest,
        @Parameter(description = "Idempotency key to prevent duplicate processing")
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Map<String, String>> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "No tenant context"))

        val finalIdempotencyKey = idempotencyKey ?: UUID.randomUUID().toString()
        val rawPayload =
            try {
                objectMapper.writeValueAsString(request)
            } catch (e: JsonProcessingException) {
                logger.warn("Failed to serialize email payload to JSON, using toString fallback", e)
                request.toString()
            }

        eventBus.publishIngest(tenantId, finalIdempotencyKey, rawPayload)

        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(
                mapOf(
                    "message" to "Email queued for processing",
                    "idempotencyKey" to finalIdempotencyKey,
                ),
            )
    }
}
