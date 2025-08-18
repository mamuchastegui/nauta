package com.nauta.takehome.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContainerRefTest {
    @Test
    fun `should create valid container ref with proper ISO 6346 format`() {
        val validContainerRefs =
            listOf(
                "MEDU1234567",
                "TCLU9876543",
                "ABCD1111111",
                "MSKU0000000",
            )

        validContainerRefs.forEach { ref ->
            val containerRef = ContainerRef(ref)
            assertEquals(ref, containerRef.value)
        }
    }

    @Test
    fun `should reject blank container ref`() {
        val blankValues = listOf("", " ", "   ", "\t", "\n")

        blankValues.forEach { blank ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    ContainerRef(blank)
                }
            assertEquals("ContainerRef cannot be blank", exception.message)
        }
    }

    @Test
    fun `should reject invalid ISO 6346 format - wrong letter count`() {
        val invalidRefs =
            listOf(
                // 3 letters instead of 4
                "MED1234567",
                // 5 letters instead of 4
                "MEDUU1234567",
                // no letters
                "1234567890123",
            )

        invalidRefs.forEach { ref ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    ContainerRef(ref)
                }
            assertEquals("ContainerRef must follow ISO 6346 format", exception.message)
        }
    }

    @Test
    fun `should reject invalid ISO 6346 format - wrong digit count`() {
        val invalidRefs =
            listOf(
                // 5 digits instead of 7
                "MEDU12345",
                // 9 digits instead of 7
                "MEDU123456789",
                // no digits
                "MEDU",
                // letters instead of digits
                "MEDUABCDEFG",
            )

        invalidRefs.forEach { ref ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    ContainerRef(ref)
                }
            assertEquals("ContainerRef must follow ISO 6346 format", exception.message)
        }
    }

    @Test
    fun `should reject lowercase letters`() {
        val invalidRefs =
            listOf(
                "medu1234567",
                "Medu1234567",
                "MEDU1234567a",
            )

        invalidRefs.forEach { ref ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    ContainerRef(ref)
                }
            assertEquals("ContainerRef must follow ISO 6346 format", exception.message)
        }
    }

    @Test
    fun `should reject special characters and spaces`() {
        val invalidRefs =
            listOf(
                "MED-1234567",
                "MEDU 1234567",
                "MEDU@1234567",
                "MEDU#1234567",
            )

        invalidRefs.forEach { ref ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    ContainerRef(ref)
                }
            assertEquals("ContainerRef must follow ISO 6346 format", exception.message)
        }
    }

    @Test
    fun `should validate exact length requirement`() {
        val invalidRefs =
            listOf(
                // too short (10 chars instead of 11)
                "MEDU123456",
                // too long (12 chars instead of 11)
                "MEDU12345678",
            )

        invalidRefs.forEach { ref ->
            val exception =
                assertThrows(IllegalArgumentException::class.java) {
                    ContainerRef(ref)
                }
            assertEquals("ContainerRef must follow ISO 6346 format", exception.message)
        }
    }
}
