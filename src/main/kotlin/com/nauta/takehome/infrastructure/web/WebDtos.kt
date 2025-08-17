package com.nauta.takehome.infrastructure.web

data class OrderDto(
    val id: Long?,
    val purchaseRef: String,
    val tenantId: String,
    val bookingRef: String?,
    val containerRef: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class ContainerDto(
    val id: Long?,
    val containerRef: String,
    val tenantId: String,
    val bookingRef: String?,
    val createdAt: String,
    val updatedAt: String,
)
