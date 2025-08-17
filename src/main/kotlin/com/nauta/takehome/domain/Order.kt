package com.nauta.takehome.domain

import java.time.Instant

data class Order(
    val id: Long? = null,
    val purchaseRef: PurchaseRef,
    val tenantId: String,
    val bookingRef: BookingRef? = null,
    val containerRef: ContainerRef? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
