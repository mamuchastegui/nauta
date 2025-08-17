package com.nauta.takehome.domain

import java.math.BigDecimal
import java.time.Instant

data class OrderContainer(
    val id: Long? = null,
    val orderId: Long,
    val containerId: Long,
    val tenantId: String,
    val linkedAt: Instant = Instant.now(),
    val linkingReason: LinkingReason = LinkingReason.BOOKING_MATCH,
    val confidenceScore: BigDecimal = BigDecimal("1.00"),
    val createdBy: String = "system",
)

enum class LinkingReason {
    BOOKING_MATCH,
    MANUAL,
    AI_INFERENCE,
    TEMPORAL_CORRELATION,
    SYSTEM_MIGRATION,
}
