package com.nauta.takehome.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PurchaseRefTest {
    @Test
    fun `should create valid purchase ref with any non-blank value`() {
        val validPurchaseRefs =
            listOf(
                "PO123456",
                "ORDER-2023-001",
                "purchase_ref_123",
                "PO/2023/ABC",
                "123",
                "A",
                "purchase-order-with-very-long-name-2023",
            )

        validPurchaseRefs.forEach { ref ->
            val purchaseRef = PurchaseRef(ref)
            assertEquals(ref, purchaseRef.value)
        }
    }

    @Test
    fun `should reject blank purchase ref`() {
        val blankValues = listOf("", " ", "   ", "\t", "\n", "\r\n")

        blankValues.forEach { blank ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    PurchaseRef(blank)
                }
            assertEquals("PurchaseRef cannot be blank", exception.message)
        }
    }

    @Test
    fun `should accept special characters and mixed formats`() {
        val validSpecialRefs =
            listOf(
                "PO-123",
                "PO_123",
                "PO.123",
                "PO/123",
                "PO@123",
                "PO#123",
                "PO$123",
                "PO%123",
                "123-ABC-XYZ",
                "ORDER(2023)001",
            )

        validSpecialRefs.forEach { ref ->
            val purchaseRef = PurchaseRef(ref)
            assertEquals(ref, purchaseRef.value)
        }
    }

    @Test
    fun `should accept numbers, letters and unicode`() {
        val validRefs =
            listOf(
                "123456",
                "ABCDEF",
                "abcdef",
                "Purchase123",
                // Chinese characters
                "订单123",
                // Cyrillic characters
                "Заказ123",
                // Emoji
                "🏷️PO123",
            )

        validRefs.forEach { ref ->
            val purchaseRef = PurchaseRef(ref)
            assertEquals(ref, purchaseRef.value)
        }
    }

    @Test
    fun `should preserve original value exactly`() {
        val testValues =
            listOf(
                // with spaces
                "  PO123  ",
                // with tab
                "PO123\t",
                // lowercase
                "po123",
                // uppercase
                "PO123",
                // mixed case
                "Po123",
            )

        testValues.forEach { value ->
            val purchaseRef = PurchaseRef(value)
            assertEquals(value, purchaseRef.value, "Should preserve exact input: '$value'")
        }
    }
}
