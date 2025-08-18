package com.nauta.takehome.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BookingRefTest {
    @Test
    fun `should create valid booking ref with any non-blank value`() {
        val validBookingRefs =
            listOf(
                "BK123456",
                "BOOKING-2023-001",
                "booking_ref_123",
                "BK/2023/ABC",
                "123",
                "B",
                "MAEU-BOOKING-12345",
                "COSCO2023001",
            )

        validBookingRefs.forEach { ref ->
            val bookingRef = BookingRef(ref)
            assertEquals(ref, bookingRef.value)
        }
    }

    @Test
    fun `should reject blank booking ref`() {
        val blankValues = listOf("", " ", "   ", "\t", "\n", "\r\n")

        blankValues.forEach { blank ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    BookingRef(blank)
                }
            assertEquals("BookingRef cannot be blank", exception.message)
        }
    }

    @Test
    fun `should accept typical shipping line formats`() {
        val typicalFormats =
            listOf(
                // Maersk format
                "MAEU123456789",
                // COSCO format
                "COSCO2023001234",
                // MSK with separators
                "MSK-BK-123456",
                // Evergreen format
                "EVERGREEN/BK/2023",
                // CMA CGM format
                "CMA-CGM-123456",
                // Hapag Lloyd format
                "HAPAG-LLOYD-2023",
            )

        typicalFormats.forEach { ref ->
            val bookingRef = BookingRef(ref)
            assertEquals(ref, bookingRef.value)
        }
    }

    @Test
    fun `should accept special characters and mixed formats`() {
        val validSpecialRefs =
            listOf(
                "BK-123",
                "BK_123",
                "BK.123",
                "BK/123",
                "BK@123",
                "BK#123",
                "123-ABC-XYZ",
                "BOOKING(2023)001",
            )

        validSpecialRefs.forEach { ref ->
            val bookingRef = BookingRef(ref)
            assertEquals(ref, bookingRef.value)
        }
    }

    @Test
    fun `should preserve original value exactly`() {
        val testValues =
            listOf(
                // with spaces
                "  BK123  ",
                // with tab
                "BK123\t",
                // lowercase
                "bk123",
                // uppercase
                "BK123",
                // mixed case
                "Bk123",
            )

        testValues.forEach { value ->
            val bookingRef = BookingRef(value)
            assertEquals(value, bookingRef.value, "Should preserve exact input: '$value'")
        }
    }
}
