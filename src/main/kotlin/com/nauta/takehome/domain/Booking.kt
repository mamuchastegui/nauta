package com.nauta.takehome.domain

import java.time.Instant

data class Booking(
    val id: Long? = null,
    val bookingRef: BookingRef,
    val tenantId: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
