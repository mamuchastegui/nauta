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
import com.nauta.takehome.infrastructure.web.EmailIngestRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class IngestService(
    private val orderRepository: OrderRepository,
    private val containerRepository: ContainerRepository,
    private val bookingRepository: BookingRepository,
    private val invoiceRepository: InvoiceRepository,
    private val orderContainerRepository: OrderContainerRepository,
) {
    private val logger = LoggerFactory.getLogger(IngestService::class.java)

    fun ingestEmail(payload: EmailPayload): IngestResult {
        return try {
            // Parse email payload and extract structured data
            val ingestMessage = parseEmailPayload(payload)
            processIngestMessage(ingestMessage)
            IngestResult.success("Email ingested successfully")
        } catch (e: IllegalArgumentException) {
            IngestResult.error("Invalid email payload format", e)
        } catch (e: SecurityException) {
            IngestResult.error("Security error processing email payload", e)
        }
    }

    private fun parseEmailPayload(payload: EmailPayload): IngestMessage {
        // Parse basic structure from rawPayload
        return try {
            // In a real implementation, this would parse the email content structure
            IngestMessage(
                tenantId = extractTenantIdFromPayload(payload.rawPayload),
                booking = null,
                orders = emptyList(),
                containers = emptyList(),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid email payload format: ${e.message}", e)
        }
    }

    private fun extractTenantIdFromPayload(rawPayload: String): String {
        // Simplified extraction - real implementation would parse email headers
        require(rawPayload.isNotBlank()) { "Empty payload" }
        return "default"
    }

    fun processIngestMessage(message: IngestMessage) {
        val tenantId = message.tenantId

        // Process booking if present
        val booking =
            message.booking?.let { bookingData ->
                val bookingRef = BookingRef(bookingData.bookingRef)
                bookingRepository.upsertByRef(tenantId, bookingRef)
            }

        // Process orders and their invoices
        val processedOrders = mutableListOf<Order>()
        message.orders.forEach { orderData ->
            val purchaseRef = PurchaseRef(orderData.purchaseRef)
            val bookingRef = message.booking?.let { BookingRef(it.bookingRef) }

            val order = orderRepository.upsertByRef(tenantId, purchaseRef, bookingRef)
            processedOrders.add(order)

            // Process invoices for this order
            orderData.invoices.forEach { invoiceData ->
                val invoiceRef = InvoiceRef(invoiceData.invoiceRef)
                invoiceRepository.upsertByRef(tenantId, invoiceRef, purchaseRef)
            }
        }

        // Process containers
        val processedContainers = mutableListOf<Container>()
        message.containers.forEach { containerData ->
            val containerRef = ContainerRef(containerData.containerRef)
            val bookingRef = message.booking?.let { BookingRef(it.bookingRef) }

            val container = containerRepository.upsertByRef(tenantId, containerRef, bookingRef)
            processedContainers.add(container)
        }

        // Progressive linking: Create M:N relationships between orders and containers
        performProgressiveLinking(tenantId, processedOrders, processedContainers, message.booking)
    }

    private fun performProgressiveLinking(
        tenantId: String,
        orders: List<Order>,
        containers: List<Container>,
        bookingData: BookingData?,
    ) {
        if (orders.isEmpty() || containers.isEmpty()) return

        // Strategy 1: Link by booking reference (highest confidence)
        if (bookingData != null) {
            val bookingRef = BookingRef(bookingData.bookingRef)
            linkByBookingRef(tenantId, orders, containers, bookingRef)
        } else {
            // Strategy 2: Link all orders to all containers in the same message
            // This is a fallback when no booking reference is available
            linkAllToAll(tenantId, orders, containers, LinkingReason.SYSTEM_MIGRATION)
        }
    }

    private fun linkByBookingRef(
        tenantId: String,
        orders: List<Order>,
        containers: List<Container>,
        bookingRef: BookingRef,
    ) {
        // Link orders and containers that belong to the same booking
        val eligibleOrders = orders.filter { it.bookingRef == bookingRef }
        val eligibleContainers = containers.filter { it.bookingRef == bookingRef }

        linkAllToAll(tenantId, eligibleOrders, eligibleContainers, LinkingReason.BOOKING_MATCH)
    }

    private fun linkAllToAll(
        tenantId: String,
        orders: List<Order>,
        containers: List<Container>,
        linkingReason: LinkingReason,
    ) {
        orders.forEach { order ->
            containers.forEach { container ->
                linkOrderAndContainerSafely(tenantId, order, container, linkingReason)
            }
        }
    }

    private fun linkOrderAndContainerSafely(
        tenantId: String,
        order: Order,
        container: Container,
        linkingReason: LinkingReason,
    ) {
        if (order.id == null || container.id == null) return

        try {
            orderContainerRepository.linkOrderAndContainer(
                tenantId = tenantId,
                orderId = order.id,
                containerId = container.id,
                linkingReason = linkingReason,
            )
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            logger.debug("Link already exists between order ${order.id} and container ${container.id}", e)
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            logger.warn("Data integrity violation linking order ${order.id} to container ${container.id}", e)
        }
    }

    fun processEmailIngestRequest(
        tenantId: String,
        request: EmailIngestRequest,
    ) {
        val ingestMessage = convertEmailRequestToIngestMessage(tenantId, request)
        processIngestMessage(ingestMessage)
    }

    private fun convertEmailRequestToIngestMessage(
        tenantId: String,
        request: EmailIngestRequest,
    ): IngestMessage {
        val booking = request.booking?.let { BookingData(it) }

        val orders =
            request.orders?.map { orderRequest ->
                val invoices =
                    orderRequest.invoices?.map { invoiceRequest ->
                        InvoiceData(invoiceRequest.invoice)
                    } ?: emptyList()
                OrderData(orderRequest.purchase, invoices)
            } ?: emptyList()

        val containers =
            request.containers?.map { containerRequest ->
                ContainerData(containerRequest.container)
            } ?: emptyList()

        return IngestMessage(
            tenantId = tenantId,
            booking = booking,
            orders = orders,
            containers = containers,
        )
    }
}

// Repository interfaces (ports)
interface OrderRepository {
    fun upsertByRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
        bookingRef: BookingRef?,
    ): Order

    fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): Order?

    fun findAll(tenantId: String): List<Order>
}

interface ContainerRepository {
    fun upsertByRef(
        tenantId: String,
        containerRef: ContainerRef,
        bookingRef: BookingRef?,
    ): Container

    fun findByContainerRef(
        tenantId: String,
        containerRef: ContainerRef,
    ): Container?

    fun findAll(tenantId: String): List<Container>

    fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): List<Container>
}

interface BookingRepository {
    fun upsertByRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): Booking

    fun findByBookingRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): Booking?
}

interface InvoiceRepository {
    fun upsertByRef(
        tenantId: String,
        invoiceRef: InvoiceRef,
        purchaseRef: PurchaseRef,
    ): Invoice

    fun findByInvoiceRef(
        tenantId: String,
        invoiceRef: InvoiceRef,
    ): Invoice?
}

interface OrderContainerRepository {
    fun linkOrderAndContainer(
        tenantId: String,
        orderId: Long,
        containerId: Long,
        linkingReason: LinkingReason = LinkingReason.BOOKING_MATCH,
    ): OrderContainer

    fun findContainersByOrderId(
        tenantId: String,
        orderId: Long,
    ): List<Container>

    fun findOrdersByContainerId(
        tenantId: String,
        containerId: Long,
    ): List<Order>

    fun findContainersByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): List<Container>

    fun findOrdersByContainerRef(
        tenantId: String,
        containerRef: ContainerRef,
    ): List<Order>

    fun unlinkOrderAndContainer(
        tenantId: String,
        orderId: Long,
        containerId: Long,
    ): Boolean

    fun findAllRelationships(tenantId: String): List<OrderContainer>
}

// Data classes for message processing
data class IngestMessage(
    val tenantId: String,
    val booking: BookingData?,
    val orders: List<OrderData>,
    val containers: List<ContainerData>,
)

data class BookingData(val bookingRef: String)

data class OrderData(val purchaseRef: String, val invoices: List<InvoiceData>)

data class InvoiceData(val invoiceRef: String)

data class ContainerData(val containerRef: String)

data class EmailPayload(val rawPayload: String)

sealed class IngestResult {
    data class Success(val message: String) : IngestResult()

    data class Error(val message: String, val cause: Throwable? = null) : IngestResult()

    companion object {
        fun success(message: String) = Success(message)

        fun error(
            message: String,
            cause: Throwable? = null,
        ) = Error(message, cause)
    }
}
