package com.nauta.takehome.domain

@JvmInline
value class PurchaseRef(val value: String) {
    init {
        require(value.isNotBlank()) { "PurchaseRef cannot be blank" }
    }
}
