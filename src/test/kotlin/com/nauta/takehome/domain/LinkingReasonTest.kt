package com.nauta.takehome.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkingReasonTest {
    @Test
    fun `should contain all expected linking reasons`() {
        val expectedReasons =
            setOf(
                LinkingReason.BOOKING_MATCH,
                LinkingReason.MANUAL,
                LinkingReason.AI_INFERENCE,
                LinkingReason.TEMPORAL_CORRELATION,
                LinkingReason.SYSTEM_MIGRATION,
            )

        val actualReasons = LinkingReason.values().toSet()
        assertEquals(expectedReasons, actualReasons)
    }

    @Test
    fun `should have correct enum count`() {
        assertEquals(5, LinkingReason.values().size)
    }

    @Test
    fun `should have BOOKING_MATCH as first enum value`() {
        assertEquals(LinkingReason.BOOKING_MATCH, LinkingReason.values()[0])
    }

    @Test
    fun `should contain business-relevant linking strategies`() {
        val businessReasons =
            listOf(
                // Primary linking by booking reference
                LinkingReason.BOOKING_MATCH,
                // User-initiated linking
                LinkingReason.MANUAL,
                // Machine learning based
                LinkingReason.AI_INFERENCE,
                // Time-based correlation
                LinkingReason.TEMPORAL_CORRELATION,
                // Data migration scenarios
                LinkingReason.SYSTEM_MIGRATION,
            )

        businessReasons.forEach { reason ->
            assertTrue(
                LinkingReason.values().contains(reason),
                "Should contain business reason: $reason",
            )
        }
    }

    @Test
    fun `should support enum comparison and ordering`() {
        val reason1 = LinkingReason.BOOKING_MATCH
        val reason2 = LinkingReason.BOOKING_MATCH
        val reason3 = LinkingReason.MANUAL

        assertEquals(reason1, reason2)
        assertTrue(reason1 != reason3)
        assertEquals(reason1.ordinal, reason2.ordinal)
        assertTrue(reason1.ordinal != reason3.ordinal)
    }

    @Test
    fun `should support string conversion`() {
        val reasons = LinkingReason.values()

        reasons.forEach { reason ->
            val stringValue = reason.toString()
            assertTrue(stringValue.isNotBlank(), "String representation should not be blank")
            assertEquals(reason.name, stringValue, "Should match enum name")
        }
    }

    @Test
    fun `should support valueOf conversion`() {
        LinkingReason.values().forEach { originalReason ->
            val convertedReason = LinkingReason.valueOf(originalReason.name)
            assertEquals(originalReason, convertedReason)
        }
    }
}
