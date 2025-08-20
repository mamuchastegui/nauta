package com.nauta.takehome.application

import com.nauta.takehome.domain.Booking
import com.nauta.takehome.domain.BookingRef
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.Invoice
import com.nauta.takehome.domain.InvoiceRef
import com.nauta.takehome.domain.LinkingReason
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.OrderContainer
import com.nauta.takehome.domain.PurchaseRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class IngestServiceSimpleTest {
    private lateinit var ingestService: IngestService

    // Simple in-memory repository implementations for testing
    private lateinit var orderRepository: TestOrderRepository
    private lateinit var containerRepository: TestContainerRepository
    private lateinit var bookingRepository: TestBookingRepository
    private lateinit var invoiceRepository: TestInvoiceRepository
    private lateinit var orderContainerRepository: TestOrderContainerRepository

    private val tenantId = "test-tenant"
    private val now = Instant.now()

    @BeforeEach
    fun setUp() {
        orderRepository = TestOrderRepository()
        containerRepository = TestContainerRepository()
        bookingRepository = TestBookingRepository()
        invoiceRepository = TestInvoiceRepository()
        orderContainerRepository = TestOrderContainerRepository()

        ingestService =
            IngestService(
                orderRepository = orderRepository,
                containerRepository = containerRepository,
                bookingRepository = bookingRepository,
                invoiceRepository = invoiceRepository,
                orderContainerRepository = orderContainerRepository,
            )
    }

    @Test
    fun `should process message with booking, orders and containers`() {
        // Given
        val booking = BookingData("BK123")
        val order = OrderData("PO456", listOf(InvoiceData("INV789")))
        val container = ContainerData("ABCD1234567")

        val message =
            IngestMessage(
                tenantId = tenantId,
                booking = booking,
                orders = listOf(order),
                containers = listOf(container),
            )

        // When
        ingestService.processIngestMessage(message)

        // Then
        assertEquals(1, bookingRepository.bookings.size)
        assertEquals(1, orderRepository.orders.size)
        assertEquals(1, containerRepository.containers.size)
        assertEquals(1, invoiceRepository.invoices.size)
        assertEquals(1, orderContainerRepository.relationships.size)

        val savedOrder = orderRepository.orders[0]
        val savedContainer = containerRepository.containers[0]
        val relationship = orderContainerRepository.relationships[0]

        assertEquals("PO456", savedOrder.purchaseRef.value)
        assertEquals("BK123", savedOrder.bookingRef?.value)
        assertEquals("ABCD1234567", savedContainer.containerRef.value)
        assertEquals("BK123", savedContainer.bookingRef?.value)
        assertEquals(LinkingReason.BOOKING_MATCH, relationship.linkingReason)
    }

    @Test
    fun `should process message with orders only`() {
        // Given
        val order = OrderData("PO123", listOf(InvoiceData("INV456")))
        val message =
            IngestMessage(
                tenantId = tenantId,
                booking = null,
                orders = listOf(order),
                containers = emptyList(),
            )

        // When
        ingestService.processIngestMessage(message)

        // Then
        assertEquals(0, bookingRepository.bookings.size)
        assertEquals(1, orderRepository.orders.size)
        assertEquals(0, containerRepository.containers.size)
        assertEquals(1, invoiceRepository.invoices.size)
        assertEquals(0, orderContainerRepository.relationships.size)

        val savedOrder = orderRepository.orders[0]
        assertEquals("PO123", savedOrder.purchaseRef.value)
        assertEquals(null, savedOrder.bookingRef)
    }

    @Test
    fun `should process message with containers only`() {
        // Given
        val container = ContainerData("MEDU1234567")
        val message =
            IngestMessage(
                tenantId = tenantId,
                booking = null,
                orders = emptyList(),
                containers = listOf(container),
            )

        // When
        ingestService.processIngestMessage(message)

        // Then
        assertEquals(0, bookingRepository.bookings.size)
        assertEquals(0, orderRepository.orders.size)
        assertEquals(1, containerRepository.containers.size)
        assertEquals(0, invoiceRepository.invoices.size)
        assertEquals(0, orderContainerRepository.relationships.size)

        val savedContainer = containerRepository.containers[0]
        assertEquals("MEDU1234567", savedContainer.containerRef.value)
        assertEquals(null, savedContainer.bookingRef)
    }

    @Test
    fun `should link all orders to all containers when no booking present`() {
        // Given
        val orders =
            listOf(
                OrderData("PO001", emptyList()),
                OrderData("PO002", emptyList()),
            )
        val containers =
            listOf(
                ContainerData("ABCD1234567"),
                ContainerData("MEDU1234567"),
            )
        val message =
            IngestMessage(
                tenantId = tenantId,
                booking = null,
                orders = orders,
                containers = containers,
            )

        // When
        ingestService.processIngestMessage(message)

        // Then - Should create 4 relationships (2 orders × 2 containers)
        assertEquals(2, orderRepository.orders.size)
        assertEquals(2, containerRepository.containers.size)
        assertEquals(4, orderContainerRepository.relationships.size)

        // All relationships should have SYSTEM_MIGRATION reason
        orderContainerRepository.relationships.forEach { relationship ->
            assertEquals(LinkingReason.SYSTEM_MIGRATION, relationship.linkingReason)
        }
    }

    @Test
    fun `should handle empty message gracefully`() {
        // Given
        val message =
            IngestMessage(
                tenantId = tenantId,
                booking = null,
                orders = emptyList(),
                containers = emptyList(),
            )

        // When
        ingestService.processIngestMessage(message)

        // Then
        assertEquals(0, bookingRepository.bookings.size)
        assertEquals(0, orderRepository.orders.size)
        assertEquals(0, containerRepository.containers.size)
        assertEquals(0, invoiceRepository.invoices.size)
        assertEquals(0, orderContainerRepository.relationships.size)
    }

    @Test
    fun `should handle multiple invoices per order`() {
        // Given
        val invoices =
            listOf(
                InvoiceData("INV001"),
                InvoiceData("INV002"),
                InvoiceData("INV003"),
            )
        val order = OrderData("PO123", invoices)
        val message =
            IngestMessage(
                tenantId = tenantId,
                booking = null,
                orders = listOf(order),
                containers = emptyList(),
            )

        // When
        ingestService.processIngestMessage(message)

        // Then
        assertEquals(1, orderRepository.orders.size)
        assertEquals(3, invoiceRepository.invoices.size)

        val savedInvoices = invoiceRepository.invoices
        assertEquals("INV001", savedInvoices[0].invoiceRef.value)
        assertEquals("INV002", savedInvoices[1].invoiceRef.value)
        assertEquals("INV003", savedInvoices[2].invoiceRef.value)

        // All invoices should be linked to the same order
        savedInvoices.forEach { invoice ->
            assertEquals("PO123", invoice.purchaseRef.value)
        }
    }

    // Test repository implementations
    class TestOrderRepository : OrderRepository {
        val orders = mutableListOf<Order>()
        private var nextId = 1L

        override fun upsertByRef(
            tenantId: String,
            purchaseRef: PurchaseRef,
            bookingRef: BookingRef?,
        ): Order {
            val existingOrder = orders.find { it.purchaseRef == purchaseRef && it.tenantId == tenantId }
            if (existingOrder != null) {
                val updatedOrder = existingOrder.copy(bookingRef = bookingRef, updatedAt = Instant.now())
                orders.removeIf { it.purchaseRef == purchaseRef && it.tenantId == tenantId }
                orders.add(updatedOrder)
                return updatedOrder
            }

            val newOrder =
                Order(
                    id = nextId++,
                    purchaseRef = purchaseRef,
                    tenantId = tenantId,
                    bookingRef = bookingRef,
                )
            orders.add(newOrder)
            return newOrder
        }

        override fun findByPurchaseRef(
            tenantId: String,
            purchaseRef: PurchaseRef,
        ): Order? = orders.find { it.purchaseRef == purchaseRef && it.tenantId == tenantId }

        override fun findAll(tenantId: String): List<Order> = orders.filter { it.tenantId == tenantId }

        override fun findByBookingRef(
            tenantId: String,
            bookingRef: BookingRef,
        ): List<Order> = orders.filter { it.tenantId == tenantId && it.bookingRef == bookingRef }
    }

    class TestContainerRepository : ContainerRepository {
        val containers = mutableListOf<Container>()
        private var nextId = 1L

        override fun upsertByRef(
            tenantId: String,
            containerRef: ContainerRef,
            bookingRef: BookingRef?,
        ): Container {
            val existingContainer = containers.find { it.containerRef == containerRef && it.tenantId == tenantId }
            if (existingContainer != null) {
                val updatedContainer = existingContainer.copy(bookingRef = bookingRef, updatedAt = Instant.now())
                containers.removeIf { it.containerRef == containerRef && it.tenantId == tenantId }
                containers.add(updatedContainer)
                return updatedContainer
            }

            val newContainer =
                Container(
                    id = nextId++,
                    containerRef = containerRef,
                    tenantId = tenantId,
                    bookingRef = bookingRef,
                )
            containers.add(newContainer)
            return newContainer
        }

        override fun findByContainerRef(
            tenantId: String,
            containerRef: ContainerRef,
        ): Container? = containers.find { it.containerRef == containerRef && it.tenantId == tenantId }

        override fun findAll(tenantId: String): List<Container> = containers.filter { it.tenantId == tenantId }

        override fun findByPurchaseRef(
            tenantId: String,
            purchaseRef: PurchaseRef,
        ): List<Container> {
            // Simple implementation - not used in these tests
            return emptyList()
        }

        override fun findByBookingRef(
            tenantId: String,
            bookingRef: BookingRef,
        ): List<Container> = containers.filter { it.tenantId == tenantId && it.bookingRef == bookingRef }
    }

    class TestBookingRepository : BookingRepository {
        val bookings = mutableListOf<Booking>()
        private var nextId = 1L

        override fun upsertByRef(
            tenantId: String,
            bookingRef: BookingRef,
        ): Booking {
            val existingBooking = bookings.find { it.bookingRef == bookingRef && it.tenantId == tenantId }
            if (existingBooking != null) {
                return existingBooking
            }

            val newBooking =
                Booking(
                    id = nextId++,
                    bookingRef = bookingRef,
                    tenantId = tenantId,
                )
            bookings.add(newBooking)
            return newBooking
        }

        override fun findByBookingRef(
            tenantId: String,
            bookingRef: BookingRef,
        ): Booking? = bookings.find { it.bookingRef == bookingRef && it.tenantId == tenantId }
    }

    class TestInvoiceRepository : InvoiceRepository {
        val invoices = mutableListOf<Invoice>()
        private var nextId = 1L

        override fun upsertByRef(
            tenantId: String,
            invoiceRef: InvoiceRef,
            purchaseRef: PurchaseRef,
        ): Invoice {
            val existingInvoice = invoices.find { it.invoiceRef == invoiceRef && it.tenantId == tenantId }
            if (existingInvoice != null) {
                return existingInvoice
            }

            val newInvoice =
                Invoice(
                    id = nextId++,
                    invoiceRef = invoiceRef,
                    purchaseRef = purchaseRef,
                    tenantId = tenantId,
                )
            invoices.add(newInvoice)
            return newInvoice
        }

        override fun findByInvoiceRef(
            tenantId: String,
            invoiceRef: InvoiceRef,
        ): Invoice? = invoices.find { it.invoiceRef == invoiceRef && it.tenantId == tenantId }

        override fun findByPurchaseRef(
            tenantId: String,
            purchaseRef: PurchaseRef,
        ): List<Invoice> = invoices.filter { it.tenantId == tenantId && it.purchaseRef == purchaseRef }
    }

    class TestOrderContainerRepository : OrderContainerRepository {
        val relationships = mutableListOf<OrderContainer>()
        private var nextId = 1L

        override fun linkOrderAndContainer(
            tenantId: String,
            orderId: Long,
            containerId: Long,
            linkingReason: LinkingReason,
        ): OrderContainer {
            val existing =
                relationships.find {
                    it.orderId == orderId && it.containerId == containerId && it.tenantId == tenantId
                }
            if (existing != null) {
                return existing
            }

            val newRelationship =
                OrderContainer(
                    id = nextId++,
                    orderId = orderId,
                    containerId = containerId,
                    tenantId = tenantId,
                    linkingReason = linkingReason,
                    confidenceScore = BigDecimal("1.00"),
                )
            relationships.add(newRelationship)
            return newRelationship
        }

        override fun findContainersByOrderId(
            tenantId: String,
            orderId: Long,
        ): List<Container> = emptyList()

        override fun findOrdersByContainerId(
            tenantId: String,
            containerId: Long,
        ): List<Order> = emptyList()

        override fun findContainersByPurchaseRef(
            tenantId: String,
            purchaseRef: PurchaseRef,
        ): List<Container> = emptyList()

        override fun findOrdersByContainerRef(
            tenantId: String,
            containerRef: ContainerRef,
        ): List<Order> = emptyList()

        override fun unlinkOrderAndContainer(
            tenantId: String,
            orderId: Long,
            containerId: Long,
        ): Boolean = true

        override fun findAllRelationships(tenantId: String): List<OrderContainer> =
            relationships.filter {
                it.tenantId == tenantId
            }
    }
}
