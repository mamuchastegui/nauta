package com.nauta.takehome.domain

import java.time.Instant

data class Invoice(
    val id: Long? = null,
    val invoiceRef: InvoiceRef,
    val purchaseRef: PurchaseRef,
    val tenantId: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
