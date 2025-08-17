package com.nauta.takehome.application

import com.nauta.takehome.domain.Booking
import com.nauta.takehome.domain.BookingRef
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.Invoice
import com.nauta.takehome.domain.InvoiceRef
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.PurchaseRef
import org.springframework.stereotype.Service

@Service
class IngestService(
    private val orderRepository: OrderRepository,
    private val containerRepository: ContainerRepository,
    private val bookingRepository: BookingRepository,
    private val invoiceRepository: InvoiceRepository,
) {
    fun ingestEmail(payload: EmailPayload): IngestResult {
        return try {
            // Parse email payload and extract structured data
            // This is a simplified implementation - in real scenario would parse email content
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
        // Simplified parsing - real implementation would extract from email content
        // For now, parse basic structure from rawPayload
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
        message.booking?.let { bookingData ->
            val bookingRef = BookingRef(bookingData.bookingRef)
            bookingRepository.upsertByRef(tenantId, bookingRef)
        }

        // Process orders and their invoices
        message.orders.forEach { orderData ->
            val purchaseRef = PurchaseRef(orderData.purchaseRef)
            val bookingRef = message.booking?.let { BookingRef(it.bookingRef) }

            orderRepository.upsertByRef(tenantId, purchaseRef, bookingRef)

            // Process invoices for this order
            orderData.invoices.forEach { invoiceData ->
                val invoiceRef = InvoiceRef(invoiceData.invoiceRef)
                invoiceRepository.upsertByRef(tenantId, invoiceRef, purchaseRef)
            }
        }

        // Process containers
        message.containers.forEach { containerData ->
            val containerRef = ContainerRef(containerData.containerRef)
            val bookingRef = message.booking?.let { BookingRef(it.bookingRef) }

            containerRepository.upsertByRef(tenantId, containerRef, bookingRef)

            // Progressive linking: associate containers with orders through booking reference
            // Future enhancement: implement more sophisticated linking algorithms
        }
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

    fun findByContainerRef(
        tenantId: String,
        containerRef: ContainerRef,
    ): List<Order>
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
