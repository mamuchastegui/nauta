package com.nauta.takehome.infrastructure.web

import com.nauta.takehome.application.ContainerRepository
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.Order
import com.nauta.takehome.infrastructure.security.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/containers")
class ContainerController(
    private val containerRepository: ContainerRepository,
    private val orderContainerRepository: OrderContainerRepository,
    private val tenantContext: TenantContext,
) {
    private val logger = LoggerFactory.getLogger(ContainerController::class.java)

    @GetMapping
    fun getAllContainers(): ResponseEntity<List<ContainerDto>> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().build()

        val containers = containerRepository.findAll(tenantId)
        return ResponseEntity.ok(containers.map { it.toDto() })
    }

    @GetMapping("/{containerId}/orders")
    fun getOrdersForContainer(
        @PathVariable containerId: String,
    ): ResponseEntity<*> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().body(mapOf("error" to "Missing tenant context"))

        return try {
            val containerRef = ContainerRef(containerId)
            val orders = orderContainerRepository.findOrdersByContainerRef(tenantId, containerRef)
            ResponseEntity.ok(mapOf("data" to orders.map { it.toDto() }))
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid container ID: $containerId", e)
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid container ID format"))
        }
    }
}

private fun Container.toDto() =
    ContainerDto(
        id = id,
        containerRef = containerRef.value,
        tenantId = tenantId,
        bookingRef = bookingRef?.value,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

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
