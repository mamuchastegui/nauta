package com.nauta.takehome.domain

@JvmInline
value class InvoiceRef(val value: String) {
    init {
        require(value.isNotBlank()) { "InvoiceRef cannot be blank" }
    }
}
