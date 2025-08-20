package com.nauta.takehome.infrastructure.web

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

// Response DTOs (for API responses)
data class OrderDto(
    val id: Long?,
    val purchase: String,
    val tenantId: String,
    val booking: String?,
    val invoices: List<InvoiceDto>?,
    val createdAt: String,
    val updatedAt: String,
)

data class InvoiceDto(
    val id: Long?,
    val invoice: String,
    val tenantId: String,
    val createdAt: String,
    val updatedAt: String,
)

data class ContainerDto(
    val id: Long?,
    val container: String,
    val tenantId: String,
    val booking: String?,
    val createdAt: String,
    val updatedAt: String,
)

// Request DTOs (for API requests)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
data class EmailIngestRequest(
    val booking: String?,
    @field:Valid
    val containers: List<ContainerRequest>?,
    @field:Valid
    val orders: List<OrderRequest>?,
)

data class ContainerRequest(
    @field:NotBlank(message = "Container ID cannot be blank")
    @field:Pattern(
        regexp = "^[A-Z]{4}[0-9]{7}$",
        message = "Container ID must match ISO 6346 format: 4 letters + 7 digits",
    )
    val container: String,
)

data class OrderRequest(
    @field:NotBlank(message = "Purchase order cannot be blank")
    val purchase: String,
    @field:Valid
    val invoices: List<InvoiceRequest>?,
)

data class InvoiceRequest(
    @field:NotBlank(message = "Invoice ID cannot be blank")
    val invoice: String,
)

// Error Response DTOs
data class ErrorResponse(
    val error: String
)

data class EmailIngestResponse(
    val message: String,
    val idempotencyKey: String
)