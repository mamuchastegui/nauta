package com.nauta.takehome.infrastructure.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.nauta.takehome.infrastructure.messaging.EventBus
import com.nauta.takehome.infrastructure.security.TenantContext
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
        @RequestBody request: EmailIngestRequest,
    ): ResponseEntity<Map<String, String>> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "No tenant context"))

        val idempotencyKey = UUID.randomUUID().toString()
        val rawPayload =
            try {
                objectMapper.writeValueAsString(request)
            } catch (e: JsonProcessingException) {
                logger.warn("Failed to serialize email payload to JSON, using toString fallback", e)
                request.toString() // Fallback to toString if JSON serialization fails
            }

        eventBus.publishIngest(tenantId, idempotencyKey, rawPayload)

        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(
                mapOf(
                    "message" to "Email queued for processing",
                    "idempotencyKey" to idempotencyKey,
                ),
            )
    }
}
