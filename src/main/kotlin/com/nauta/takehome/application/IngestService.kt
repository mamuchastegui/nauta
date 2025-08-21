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
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.math.BigDecimal

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
            // Simplified extraction - real implementation would parse email headers
            require(payload.rawPayload.isNotBlank()) { "Empty payload" }
            val tenantId = "default"
            IngestMessage(
                tenantId = tenantId,
                booking = null,
                orders = emptyList(),
                containers = emptyList(),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid email payload format: ${e.message}", e)
        }
    }

    fun processIngestMessage(message: IngestMessage) {
        val tenantId = message.tenantId

        // Process booking if present
        val booking =
            message.booking?.let { bookingData ->
                val bookingRef = BookingRef(bookingData.bookingRef)
                bookingRepository.upsertByRef(tenantId, bookingRef)
            }

        // Process orders using batch operation
        val orderRequests =
            message.orders.map { orderData ->
                val purchaseRef = PurchaseRef(orderData.purchaseRef)
                val bookingRef = message.booking?.let { BookingRef(it.bookingRef) }
                OrderUpsertRequest(purchaseRef, bookingRef)
            }
        val processedOrders =
            if (orderRequests.isNotEmpty()) {
                orderRepository.batchUpsertByRef(tenantId, orderRequests)
            } else {
                emptyList()
            }

        // Process invoices using batch operation
        val invoiceRequests =
            message.orders.flatMap { orderData ->
                val purchaseRef = PurchaseRef(orderData.purchaseRef)
                orderData.invoices.map { invoiceData ->
                    val invoiceRef = InvoiceRef(invoiceData.invoiceRef)
                    InvoiceUpsertRequest(invoiceRef, purchaseRef)
                }
            }
        if (invoiceRequests.isNotEmpty()) {
            invoiceRepository.batchUpsertByRef(tenantId, invoiceRequests)
        }

        // Process containers using batch operation
        val containerRequests =
            message.containers.map { containerData ->
                val containerRef = ContainerRef(containerData.containerRef)
                val bookingRef = message.booking?.let { BookingRef(it.bookingRef) }
                ContainerUpsertRequest(containerRef, bookingRef)
            }
        val processedContainers =
            if (containerRequests.isNotEmpty()) {
                containerRepository.batchUpsertByRef(tenantId, containerRequests)
            } else {
                emptyList()
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
        // Strategy 1: Link current payload items (if both exist)
        if (orders.isNotEmpty() && containers.isNotEmpty()) {
            if (bookingData != null) {
                val bookingRef = BookingRef(bookingData.bookingRef)
                linkByBookingRef(tenantId, orders, containers, bookingRef)
            } else {
                // Fallback: link all orders to all containers in the same message
                linkAllToAll(tenantId, orders, containers, LinkingReason.SYSTEM_MIGRATION)
            }
        }

        // Strategy 2: Perform reconciliation for existing data (critical for escenario 3)
        if (bookingData != null) {
            val bookingRef = BookingRef(bookingData.bookingRef)
            performBookingReconciliation(tenantId, bookingRef)
        }
    }

    private fun linkByBookingRef(
        tenantId: String,
        orders: List<Order>,
        containers: List<Container>,
        bookingRef: BookingRef,
    ) {
        // Link orders and containers that belong to the same booking
        // Include items that already have this booking OR items that should inherit it
        val eligibleOrders = orders.filter { it.bookingRef == bookingRef || it.bookingRef == null }
        val eligibleContainers =
            containers.filter {
                it.bookingRef == bookingRef || it.bookingRef == null
            }

        linkAllToAll(tenantId, eligibleOrders, eligibleContainers, LinkingReason.BOOKING_MATCH)
    }

    private fun linkAllToAll(
        tenantId: String,
        orders: List<Order>,
        containers: List<Container>,
        linkingReason: LinkingReason,
    ) {
        if (orders.isEmpty() || containers.isEmpty()) {
            return
        }

        val confidenceScore = calculateConfidenceScore(linkingReason)
        val linkRequests =
            orders.flatMap { order ->
                containers.mapNotNull { container ->
                    if (order.id != null && container.id != null) {
                        OrderContainerLinkRequest(order.id, container.id, linkingReason, confidenceScore)
                    } else {
                        logger.warn("Skipping link for order ${order.id} or container ${container.id} with null ID")
                        null
                    }
                }
            }

        if (linkRequests.isNotEmpty()) {
            executeBatchLinking(tenantId, linkRequests)
        }
    }

    private fun executeBatchLinking(
        tenantId: String,
        linkRequests: List<OrderContainerLinkRequest>,
    ) {
        try {
            orderContainerRepository.batchLinkOrdersAndContainers(tenantId, linkRequests)
            logger.debug("Batch linked ${linkRequests.size} order-container relationships for tenant: $tenantId")
        } catch (e: DataAccessException) {
            logger.warn("Batch linking failed, falling back to individual links", e)
            linkRequests.forEach { request ->
                try {
                    orderContainerRepository.linkOrderAndContainer(
                        tenantId,
                        request.orderId,
                        request.containerId,
                        request.linkingReason,
                        request.confidenceScore,
                    )
                } catch (e: DataAccessException) {
                    logger.debug(
                        "Individual link failed for order ${request.orderId} and container ${request.containerId}",
                        e,
                    )
                }
            }
        }
    }

    /**
     * Calculates confidence score based on the linking reason.
     * Higher scores indicate more reliable relationships.
     */
    private fun calculateConfidenceScore(linkingReason: LinkingReason): BigDecimal {
        return when (linkingReason) {
            LinkingReason.BOOKING_MATCH -> BigDecimal("1.00") // Máxima confianza - mismo booking
            LinkingReason.MANUAL -> BigDecimal("0.95") // Alta confianza - admin manual
            LinkingReason.AI_INFERENCE -> BigDecimal("0.70") // Media confianza - ML prediction
            LinkingReason.TEMPORAL_CORRELATION -> BigDecimal("0.60") // Media-baja - misma ventana temporal
            LinkingReason.SYSTEM_MIGRATION -> BigDecimal("0.35") // Baja confianza - datos legacy inciertos
        }
    }

    /**
     * Reconciles all existing orders and containers that share the same booking reference.
     * This solves the "escenario 3" where orders and containers arrive in separate payloads.
     */
    private fun performBookingReconciliation(
        tenantId: String,
        bookingRef: BookingRef,
    ) {
        // Find all existing orders with this booking
        val existingOrders = orderRepository.findByBookingRef(tenantId, bookingRef)

        // Find all existing containers with this booking
        val existingContainers = containerRepository.findByBookingRef(tenantId, bookingRef)

        if (existingOrders.isNotEmpty() && existingContainers.isNotEmpty()) {
            logger.info(
                "Performing reconciliation for booking ${bookingRef.value}: " +
                    "${existingOrders.size} orders × ${existingContainers.size} containers",
            )
            linkAllToAll(tenantId, existingOrders, existingContainers, LinkingReason.BOOKING_MATCH)
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

    fun batchUpsertByRef(
        tenantId: String,
        orderRequests: List<OrderUpsertRequest>,
    ): List<Order>

    fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): Order?

    fun findAll(tenantId: String): List<Order>

    fun findByBookingRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): List<Order>
}

interface ContainerRepository {
    fun upsertByRef(
        tenantId: String,
        containerRef: ContainerRef,
        bookingRef: BookingRef?,
    ): Container

    fun batchUpsertByRef(
        tenantId: String,
        containerRequests: List<ContainerUpsertRequest>,
    ): List<Container>

    fun findByContainerRef(
        tenantId: String,
        containerRef: ContainerRef,
    ): Container?

    fun findAll(tenantId: String): List<Container>

    fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): List<Container>

    fun findByBookingRef(
        tenantId: String,
        bookingRef: BookingRef,
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

    fun batchUpsertByRef(
        tenantId: String,
        invoiceRequests: List<InvoiceUpsertRequest>,
    ): List<Invoice>

    fun findByInvoiceRef(
        tenantId: String,
        invoiceRef: InvoiceRef,
    ): Invoice?

    fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): List<Invoice>
}

interface OrderContainerRepository {
    fun linkOrderAndContainer(
        tenantId: String,
        orderId: Long,
        containerId: Long,
        linkingReason: LinkingReason = LinkingReason.BOOKING_MATCH,
        confidenceScore: BigDecimal = BigDecimal("1.00"),
    ): OrderContainer

    fun batchLinkOrdersAndContainers(
        tenantId: String,
        linkRequests: List<OrderContainerLinkRequest>,
    ): List<OrderContainer>

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

// Batch request data classes
data class OrderUpsertRequest(
    val purchaseRef: PurchaseRef,
    val bookingRef: BookingRef?,
)

data class ContainerUpsertRequest(
    val containerRef: ContainerRef,
    val bookingRef: BookingRef?,
)

data class InvoiceUpsertRequest(
    val invoiceRef: InvoiceRef,
    val purchaseRef: PurchaseRef,
)

data class OrderContainerLinkRequest(
    val orderId: Long,
    val containerId: Long,
    val linkingReason: LinkingReason = LinkingReason.BOOKING_MATCH,
    val confidenceScore: BigDecimal = BigDecimal("1.00"),
)

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
