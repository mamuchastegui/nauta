package com.nauta.takehome.infrastructure.messaging

interface EventBus {
    fun publishIngest(
        tenantId: String,
        idempotencyKey: String?,
        rawPayload: String,
    )
}
