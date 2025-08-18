package com.nauta.takehome.integration

import com.nauta.takehome.application.BookingData
import com.nauta.takehome.application.ContainerData
import com.nauta.takehome.application.ContainerRepository
import com.nauta.takehome.application.IngestMessage
import com.nauta.takehome.application.IngestService
import com.nauta.takehome.application.InvoiceData
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.application.OrderData
import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.domain.LinkingReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProgressiveLinkingTest {
    @Autowired
    private lateinit var ingestService: IngestService

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var containerRepository: ContainerRepository

    @Autowired
    private lateinit var orderContainerRepository: OrderContainerRepository

    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:15")
                .withDatabaseName("nauta_test")
                .withUsername("test")
                .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        private const val TENANT_ID = "tenant-linking-test"
    }

    @BeforeEach
    fun setUp() {
        // Clean up data before each test
        orderContainerRepository.findAllRelationships(TENANT_ID).forEach { relationship ->
            orderContainerRepository.unlinkOrderAndContainer(
                TENANT_ID,
                relationship.orderId,
                relationship.containerId,
            )
        }
    }

    @Test
    fun `should link orders and containers with same booking reference`() {
        // Given: Message with booking, orders, and containers
        val message =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-LINK-001"),
                orders =
                    listOf(
                        OrderData("PO-LINK-001", listOf(InvoiceData("INV-001"))),
                        OrderData("PO-LINK-002", listOf(InvoiceData("INV-002"))),
                    ),
                containers =
                    listOf(
                        ContainerData("LINK1234567"),
                        ContainerData("LINK7654321"),
                    ),
            )

        // When: Process the message
        ingestService.processIngestMessage(message)

        // Then: All orders should be linked to all containers (same booking)
        val order1 = orderRepository.findByPurchaseRef(TENANT_ID, com.nauta.takehome.domain.PurchaseRef("PO-LINK-001"))
        val order2 = orderRepository.findByPurchaseRef(TENANT_ID, com.nauta.takehome.domain.PurchaseRef("PO-LINK-002"))
        assertNotNull(order1)
        assertNotNull(order2)

        val container1 =
            containerRepository.findByContainerRef(
                TENANT_ID,
                com.nauta.takehome.domain.ContainerRef("LINK1234567"),
            )
        val container2 =
            containerRepository.findByContainerRef(
                TENANT_ID,
                com.nauta.takehome.domain.ContainerRef("LINK7654321"),
            )
        assertNotNull(container1)
        assertNotNull(container2)

        // Verify: Order 1 is linked to both containers
        val containersForOrder1 = orderContainerRepository.findContainersByOrderId(TENANT_ID, order1!!.id!!)
        assertEquals(2, containersForOrder1.size)
        assertTrue(containersForOrder1.any { it.id == container1!!.id })
        assertTrue(containersForOrder1.any { it.id == container2!!.id })

        // Verify: Order 2 is linked to both containers
        val containersForOrder2 = orderContainerRepository.findContainersByOrderId(TENANT_ID, order2!!.id!!)
        assertEquals(2, containersForOrder2.size)
        assertTrue(containersForOrder2.any { it.id == container1!!.id })
        assertTrue(containersForOrder2.any { it.id == container2!!.id })

        // Verify: Linking reason is BOOKING_MATCH
        val relationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertEquals(4, relationships.size) // 2 orders × 2 containers
        assertTrue(relationships.all { it.linkingReason == LinkingReason.BOOKING_MATCH })
    }

    @Test
    fun `should use fallback linking when no booking reference provided`() {
        // Given: Message without booking reference
        val message =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = null,
                orders =
                    listOf(
                        OrderData("PO-FALLBACK-001", emptyList()),
                    ),
                containers =
                    listOf(
                        ContainerData("FALL1234567"),
                    ),
            )

        // When: Process the message
        ingestService.processIngestMessage(message)

        // Then: Fallback linking should occur
        val order =
            orderRepository.findByPurchaseRef(
                TENANT_ID,
                com.nauta.takehome.domain.PurchaseRef("PO-FALLBACK-001"),
            )
        val container =
            containerRepository.findByContainerRef(
                TENANT_ID,
                com.nauta.takehome.domain.ContainerRef("FALL1234567"),
            )
        assertNotNull(order)
        assertNotNull(container)

        val relationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertEquals(1, relationships.size)
        assertEquals(LinkingReason.SYSTEM_MIGRATION, relationships[0].linkingReason)
    }

    @Test
    fun `should handle partial linking when only some entities have booking reference`() {
        // Given: Message with mixed booking references
        val message =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-PARTIAL-001"),
                orders =
                    listOf(
                        OrderData("PO-PARTIAL-001", emptyList()),
                    ),
                containers =
                    listOf(
                        ContainerData("PART1234567"),
                    ),
            )

        // When: Process the message
        ingestService.processIngestMessage(message)

        // Then: Entities with matching booking refs should be linked
        val order =
            orderRepository.findByPurchaseRef(
                TENANT_ID,
                com.nauta.takehome.domain.PurchaseRef("PO-PARTIAL-001"),
            )
        val container =
            containerRepository.findByContainerRef(
                TENANT_ID,
                com.nauta.takehome.domain.ContainerRef("PART1234567"),
            )
        assertNotNull(order)
        assertNotNull(container)

        // Both should have the booking reference
        assertEquals("BK-PARTIAL-001", order!!.bookingRef?.value)
        assertEquals("BK-PARTIAL-001", container!!.bookingRef?.value)

        // Should be linked with BOOKING_MATCH reason
        val relationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertEquals(1, relationships.size)
        assertEquals(LinkingReason.BOOKING_MATCH, relationships[0].linkingReason)
    }

    @Test
    fun `should not create duplicate relationships`() {
        // Given: Message processed once
        val message =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-DUPLICATE-001"),
                orders = listOf(OrderData("PO-DUPLICATE-001", emptyList())),
                containers = listOf(ContainerData("DUPL1234567")),
            )

        // When: Process the same message twice
        ingestService.processIngestMessage(message)
        ingestService.processIngestMessage(message)

        // Then: Only one relationship should exist
        val relationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertEquals(1, relationships.size)
    }

    @Test
    fun `should handle empty orders or containers gracefully`() {
        // Given: Message with only orders (no containers)
        val ordersOnlyMessage =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-ORDERS-ONLY"),
                orders = listOf(OrderData("PO-ORDERS-ONLY", emptyList())),
                containers = emptyList(),
            )

        // When: Process orders-only message
        ingestService.processIngestMessage(ordersOnlyMessage)

        // Then: Order created but no relationships
        val order =
            orderRepository.findByPurchaseRef(
                TENANT_ID,
                com.nauta.takehome.domain.PurchaseRef("PO-ORDERS-ONLY"),
            )
        assertNotNull(order)

        val relationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertTrue(relationships.isEmpty())

        // Given: Message with only containers (no orders)
        val containersOnlyMessage =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-CONTAINERS-ONLY"),
                orders = emptyList(),
                containers = listOf(ContainerData("CONT1234567")),
            )

        // When: Process containers-only message
        ingestService.processIngestMessage(containersOnlyMessage)

        // Then: Container created but no new relationships
        val container =
            containerRepository.findByContainerRef(
                TENANT_ID,
                com.nauta.takehome.domain.ContainerRef("CONT1234567"),
            )
        assertNotNull(container)

        val updatedRelationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertTrue(updatedRelationships.isEmpty())
    }

    @Test
    fun `should maintain confidence scoring`() {
        // Given: Message with explicit booking match
        val message =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-CONFIDENCE-001"),
                orders = listOf(OrderData("PO-CONFIDENCE-001", emptyList())),
                containers = listOf(ContainerData("CONF1234567")),
            )

        // When: Process the message
        ingestService.processIngestMessage(message)

        // Then: Relationship should have high confidence score
        val relationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertEquals(1, relationships.size)

        val relationship = relationships[0]
        assertEquals(LinkingReason.BOOKING_MATCH, relationship.linkingReason)
        assertTrue(relationship.confidenceScore.toDouble() >= 1.0, "Booking match should have high confidence")
    }

    @Test
    fun `should handle complex multi-entity scenarios`() {
        // Given: Complex message with multiple entities
        val complexMessage =
            IngestMessage(
                tenantId = TENANT_ID,
                booking = BookingData("BK-COMPLEX-001"),
                orders =
                    listOf(
                        OrderData("PO-COMPLEX-001", listOf(InvoiceData("INV-C-001"), InvoiceData("INV-C-002"))),
                        OrderData("PO-COMPLEX-002", listOf(InvoiceData("INV-C-003"))),
                        OrderData("PO-COMPLEX-003", emptyList()),
                    ),
                containers =
                    listOf(
                        ContainerData("COMP1234567"),
                        ContainerData("COMP7654321"),
                        ContainerData("COMP1111111"),
                        ContainerData("COMP2222222"),
                    ),
            )

        // When: Process the complex message
        ingestService.processIngestMessage(complexMessage)

        // Then: All orders should be linked to all containers
        val allRelationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        assertEquals(12, allRelationships.size) // 3 orders × 4 containers

        // Verify: Each order is linked to all containers
        val order1 =
            orderRepository.findByPurchaseRef(
                TENANT_ID,
                com.nauta.takehome.domain.PurchaseRef("PO-COMPLEX-001"),
            )!!
        val containersForOrder1 = orderContainerRepository.findContainersByOrderId(TENANT_ID, order1.id!!)
        assertEquals(4, containersForOrder1.size)

        // Verify: Each container is linked to all orders
        val container1 =
            containerRepository.findByContainerRef(
                TENANT_ID,
                com.nauta.takehome.domain.ContainerRef("COMP1234567"),
            )!!
        val ordersForContainer1 = orderContainerRepository.findOrdersByContainerId(TENANT_ID, container1.id!!)
        assertEquals(3, ordersForContainer1.size)

        // Verify: All relationships have correct linking reason
        assertTrue(allRelationships.all { it.linkingReason == LinkingReason.BOOKING_MATCH })
    }
}
