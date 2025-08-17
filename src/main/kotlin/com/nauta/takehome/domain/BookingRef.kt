package com.nauta.takehome.domain

@JvmInline
value class BookingRef(val value: String) {
    init {
        require(value.isNotBlank()) { "BookingRef cannot be blank" }
    }
}
