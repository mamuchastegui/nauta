package com.nauta.takehome.domain

@JvmInline
value class ContainerRef(val value: String) {
    init {
        require(value.isNotBlank()) { "ContainerRef cannot be blank" }
        require(isValidIso6346Format(value)) { "ContainerRef must follow ISO 6346 format" }
    }

    private fun isValidIso6346Format(value: String): Boolean {
        // Basic ISO 6346 validation: 4 letters + 6 digits + 1 check digit
        val regex = Regex("^[A-Z]{4}[0-9]{6}[0-9]$")
        return regex.matches(value)
    }
}
