package com.nauta.takehome.infrastructure.web

import com.nauta.takehome.application.InvoiceRepository
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.Invoice
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.PurchaseRef
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
@RequestMapping("/api/orders")
@Tag(name = "Orders")
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
    @Operation(summary = "Get containers for order")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Order containers retrieved successfully",
                content = [Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "Order with containers",
                            value = """[
                                {
                                    "id": 3001,
                                    "container": "TCLU7654321",
                                    "tenantId": "nauta-logistics-001",
                                    "booking": "HBLA240801001",
                                    "createdAt": "2024-08-19T12:00:00.123456Z",
                                    "updatedAt": "2024-08-19T13:22:15.789012Z"
                                },
                                {
                                    "id": 3002,
                                    "container": "MSKU9876543",
                                    "tenantId": "nauta-logistics-001",
                                    "booking": "HBLA240801001",
                                    "createdAt": "2024-08-19T12:05:00.654321Z",
                                    "updatedAt": "2024-08-19T13:25:45.112233Z"
                                }
                            ]"""
                        )
                    ]
                )]
            )
        ]
    )
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