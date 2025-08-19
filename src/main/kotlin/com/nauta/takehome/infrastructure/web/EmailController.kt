package com.nauta.takehome.infrastructure.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.nauta.takehome.infrastructure.messaging.EventBus
import com.nauta.takehome.infrastructure.security.TenantContext
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
class EmailController(
    private val eventBus: EventBus,
    private val tenantContext: TenantContext,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(EmailController::class.java)

    @PostMapping("/email")
    fun ingestEmail(
        @Valid @RequestBody request: EmailIngestRequest,
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
