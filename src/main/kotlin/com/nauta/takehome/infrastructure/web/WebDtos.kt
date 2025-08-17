package com.nauta.takehome.infrastructure.web

// Response DTOs (for API responses)
data class OrderDto(
    val id: Long?,
    val purchaseRef: String,
    val tenantId: String,
    val bookingRef: String?,
    val containerRef: String?, // Kept for backward compatibility, but will be null in M:N model
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

// Request DTOs (for incoming data - matching challenge format exactly)
data class EmailIngestRequest(
    val booking: String?, // "BK123" - direct string, not object
    val containers: List<ContainerRequest>?,
    val orders: List<OrderRequest>?,
)

data class ContainerRequest(
    val container: String, // "MEDU1234567" - direct string, not object
)

data class OrderRequest(
    val purchase: String, // "PO123" - direct string, not object
    val invoices: List<InvoiceRequest>?,
)

data class InvoiceRequest(
    val invoice: String, // "IN123" - direct string, not object
)
