package com.nauta.takehome.infrastructure.web

import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.domain.Container
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
    private val tenantContext: TenantContext,
) {
    private val logger = LoggerFactory.getLogger(OrderController::class.java)

    @GetMapping
    fun getAllOrders(): ResponseEntity<List<OrderDto>> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().build()

        val orders = orderRepository.findAll(tenantId)
        return ResponseEntity.ok(orders.map { it.toDto() })
    }

    @GetMapping("/{purchaseId}/containers")
    fun getContainersForOrder(
        @PathVariable purchaseId: String,
    ): ResponseEntity<List<ContainerDto>> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().build()

        return try {
            val purchaseRef = PurchaseRef(purchaseId)
            val containers = orderContainerRepository.findContainersByPurchaseRef(tenantId, purchaseRef)
            ResponseEntity.ok(containers.map { it.toDto() })
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid purchase ID: $purchaseId", e)
            ResponseEntity.badRequest().build()
        }
    }
}

private fun Order.toDto() =
    OrderDto(
        id = id,
        purchaseRef = purchaseRef.value,
        tenantId = tenantId,
        bookingRef = bookingRef?.value,
        containerRef = null,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun Container.toDto() =
    ContainerDto(
        id = id,
        containerRef = containerRef.value,
        tenantId = tenantId,
        bookingRef = bookingRef?.value,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
