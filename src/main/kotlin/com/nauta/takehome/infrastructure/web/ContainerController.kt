package com.nauta.takehome.infrastructure.web

import com.nauta.takehome.application.ContainerRepository
import com.nauta.takehome.application.InvoiceRepository
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.Invoice
import com.nauta.takehome.domain.Order
import com.nauta.takehome.infrastructure.security.TenantContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/containers")
@Tag(name = "Containers")
class ContainerController(
    private val containerRepository: ContainerRepository,
    private val orderContainerRepository: OrderContainerRepository,
    private val invoiceRepository: InvoiceRepository,
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
    @Operation(summary = "Get orders for container")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Container orders retrieved successfully",
                content = [Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "Container with orders",
                            value = """[
                                {
                                    "id": 1001,
                                    "purchase": "PO-NAU-2024-001234",
                                    "tenantId": "nauta-logistics-001",
                                    "booking": "HBLA240801001",
                                    "invoices": [
                                        {
                                            "id": 2001,
                                            "invoice": "INV-NAU-2024-005678",
                                            "tenantId": "nauta-logistics-001",
                                            "createdAt": "2024-08-19T14:15:30.456789Z",
                                            "updatedAt": "2024-08-19T14:15:30.456789Z"
                                        }
                                    ],
                                    "createdAt": "2024-08-19T15:30:00.123456Z",
                                    "updatedAt": "2024-08-19T16:45:30.789012Z"
                                }
                            ]"""
                        )
                    ]
                )]
            )
        ]
    )
    fun getOrdersForContainer(
        @PathVariable containerId: String,
    ): ResponseEntity<*> {
        val tenantId =
            tenantContext.getCurrentTenantId()
                ?: return ResponseEntity.badRequest().body(mapOf("error" to "Missing tenant context"))

        return try {
            val containerRef = ContainerRef(containerId)
            val orders = orderContainerRepository.findOrdersByContainerRef(tenantId, containerRef)
            ResponseEntity.ok(
                orders.map { order ->
                    val invoices = invoiceRepository.findByPurchaseRef(tenantId, order.purchaseRef)
                    order.toDto(invoices)
                },
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid container ID: $containerId", e)
            ResponseEntity.badRequest().body(mapOf("error" to "Invalid container ID format"))
        }
    }
}

private fun Container.toDto() =
    ContainerDto(
        id = id,
        container = containerRef.value,
        tenantId = tenantId,
        booking = bookingRef?.value?.takeUnless { it == "null" },
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

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

private fun Invoice.toDto() =
    InvoiceDto(
        id = id,
        invoice = invoiceRef.value,
        tenantId = tenantId,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )