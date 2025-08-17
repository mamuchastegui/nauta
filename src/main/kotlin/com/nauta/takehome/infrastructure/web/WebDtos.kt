package com.nauta.takehome.infrastructure.web

// Response DTOs (for API responses)
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

data class EmailIngestRequest(
    val booking: String?,
    val containers: List<ContainerRequest>?,
    val orders: List<OrderRequest>?,
)

data class ContainerRequest(
    val container: String,
)

data class OrderRequest(
    val purchase: String,
    val invoices: List<InvoiceRequest>?,
)

data class InvoiceRequest(
    val invoice: String,
)
