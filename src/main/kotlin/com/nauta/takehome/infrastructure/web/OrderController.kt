package com.nauta.takehome.infrastructure.web

import com.nauta.takehome.application.InvoiceRepository
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.Invoice
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.PurchaseRef
import com.nauta.takehome.infrastructure.security.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderRepository: OrderRepository,
    private val orderContainerRepository: OrderContainerRepository,
    private val invoiceRepository: InvoiceRepository,
    private val tenantContext: TenantContext,
) {
    private val logger = LoggerFactory.getLogger(OrderController::class.java)

    @GetMapping
    fun getAllOrders(): ResponseEntity<List<OrderDto>> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().build()

        val orders = orderRepository.findAll(tenantId)
        return ResponseEntity.ok(
            orders.map { order ->
                val invoices = invoiceRepository.findByPurchaseRef(tenantId, order.purchaseRef)
                order.toDto(invoices)
            },
        )
    }

    @GetMapping("/{purchaseId}/containers")
    fun getContainersForOrder(
        @PathVariable purchaseId: String,
    ): ResponseEntity<*> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().body(mapOf("error" to "Missing tenant context"))

        return try {
            val purchaseRef = PurchaseRef(purchaseId)
            val containers = orderContainerRepository.findContainersByPurchaseRef(tenantId, purchaseRef)
            ResponseEntity.ok(containers.map { it.toDto() })
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid purchase ID: $purchaseId", e)
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid purchase ID format"))
        }
    }
}

private fun Order.toDto(invoices: List<Invoice> = emptyList()) =
    OrderDto(
        id = id,
        purchase = purchaseRef.value,
        tenantId = tenantId,
        booking = bookingRef?.value?.takeUnless { it == "null" },
        invoices = invoices.map { it.toDto() },
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun Container.toDto() =
    ContainerDto(
        id = id,
        container = containerRef.value,
        tenantId = tenantId,
        booking = bookingRef?.value,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun Invoice.toDto() =
    InvoiceDto(
        id = id,
        invoice = invoiceRef.value,
        tenantId = tenantId,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
