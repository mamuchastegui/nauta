package com.nauta.takehome.integration

import com.nauta.takehome.config.TestConfiguration
import com.nauta.takehome.config.TestJwtTokenGenerator
import com.nauta.takehome.infrastructure.web.ContainerRequest
import com.nauta.takehome.infrastructure.web.EmailIngestRequest
import com.nauta.takehome.infrastructure.web.InvoiceRequest
import com.nauta.takehome.infrastructure.web.OrderRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Integration tests for all 11 scenarios (0-10) that were manually verified.
 * These tests ensure that the implemented reconciliation, idempotency, validation,
 * and API improvements work correctly across different data ingestion patterns.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureWebMvc
@Import(TestConfiguration::class)
class ScenariosIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var testJwtTokenGenerator: TestJwtTokenGenerator

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
    }

    private fun baseUrl() = "http://localhost:$port"

    private fun authHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(testJwtTokenGenerator.getTenant123Token())
        return headers
    }

    private fun authHeadersWithIdempotencyKey(idempotencyKey: String): HttpHeaders {
        val headers = authHeaders()
        headers.set("Idempotency-Key", idempotencyKey)
        return headers
    }

    private fun waitForProcessing() {
        Thread.sleep(1000)
    }

    @Test
    fun `Escenario 0 - full data all at once`() {
        // Given: Full payload with booking, order, container, and invoice
        val request =
            EmailIngestRequest(
                booking = "BK001",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO001",
                            invoices = listOf(InvoiceRequest(invoice = "INV001")),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639871"),
                    ),
            )

        // When: Submit full data
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify order was created with booking and invoice
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, ordersResponse.statusCode)
        val orders = ordersResponse.body as List<*>
        assertTrue(orders.isNotEmpty())
        val order = orders[0] as Map<*, *>
        assertEquals("PO001", order["purchase"])
        assertEquals("BK001", order["booking"])
        val invoices = order["invoices"] as List<*>
        assertTrue(invoices.isNotEmpty())

        // Verify container was created with booking
        val containersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containersResponse.statusCode)
        val containers = containersResponse.body as List<*>
        assertTrue(containers.isNotEmpty())
        val container = containers[0] as Map<*, *>
        assertEquals("MSCU6639871", container["container"])
        assertEquals("BK001", container["booking"])

        // Verify linkage between order and container
        val linkResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO001/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, linkResponse.statusCode)
        val linkedContainers = linkResponse.body as List<*>
        assertEquals(1, linkedContainers.size)
    }

    @Test
    fun `Escenario 1 - only booking`() {
        // Given: Only booking data
        val request =
            EmailIngestRequest(
                booking = "BK002",
                orders = null,
                containers = null,
            )

        // When: Submit booking only
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify no orders or containers created yet
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders = ordersResponse.body as List<*>
        assertTrue(orders.none { (it as Map<*, *>)["booking"] == "BK002" })
    }

    @Test
    fun `Escenario 2 - booking plus order with invoice`() {
        // Given: Booking + order with invoice (no container yet)
        val request =
            EmailIngestRequest(
                booking = "BK003",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO003",
                            invoices = listOf(InvoiceRequest(invoice = "INV003")),
                        ),
                    ),
                containers = null,
            )

        // When: Submit booking + order
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify order created with booking and invoice
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders = ordersResponse.body as List<*>
        val order = orders.find { (it as Map<*, *>)["purchase"] == "PO003" } as Map<*, *>
        assertNotNull(order)
        assertEquals("BK003", order["booking"])
        val invoices = order["invoices"] as List<*>
        assertTrue(invoices.isNotEmpty())
    }

    @Test
    fun `Escenario 3 - container arrives later and gets reconciled`() {
        // First: Submit booking + order (as in escenario 2)
        val orderRequest =
            EmailIngestRequest(
                booking = "BK004",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO004",
                            invoices = listOf(InvoiceRequest(invoice = "INV004")),
                        ),
                    ),
                containers = null,
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(orderRequest, authHeaders()),
            Map::class.java,
        )
        waitForProcessing()

        // Then: Submit container with same booking (should trigger reconciliation)
        val containerRequest =
            EmailIngestRequest(
                booking = "BK004",
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639872"),
                    ),
            )

        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(containerRequest, authHeaders()),
                Map::class.java,
            )

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify automatic reconciliation: order and container should be linked
        val linkResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO004/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, linkResponse.statusCode)
        val linkedContainers = linkResponse.body as List<*>
        assertEquals(1, linkedContainers.size)

        val container = linkedContainers[0] as Map<*, *>
        assertEquals("MSCU6639872", container["container"])
        assertEquals("BK004", container["booking"])
    }

    @Test
    fun `Escenario 4 - only container first`() {
        // Given: Only container data
        val request =
            EmailIngestRequest(
                booking = "BK005",
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639873"),
                    ),
            )

        // When: Submit container only
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify container created with booking
        val containersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val containers = containersResponse.body as List<*>
        val container = containers.find { (it as Map<*, *>)["container"] == "MSCU6639873" } as Map<*, *>
        assertNotNull(container)
        assertEquals("BK005", container["booking"])
    }

    @Test
    fun `Escenario 5 - order arrives later and gets reconciled with invoice display`() {
        // First: Submit container (as in escenario 4)
        val containerRequest =
            EmailIngestRequest(
                booking = "BK006",
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639874"),
                    ),
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(containerRequest, authHeaders()),
            Map::class.java,
        )
        waitForProcessing()

        // Then: Submit order with invoice (should trigger reconciliation)
        val orderRequest =
            EmailIngestRequest(
                booking = "BK006",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO006",
                            invoices = listOf(InvoiceRequest(invoice = "INV006")),
                        ),
                    ),
                containers = null,
            )

        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(orderRequest, authHeaders()),
                Map::class.java,
            )

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify automatic reconciliation and invoice visibility
        val linkResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MSCU6639874/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, linkResponse.statusCode)
        val linkedOrders = linkResponse.body as List<*>
        assertEquals(1, linkedOrders.size)

        val order = linkedOrders[0] as Map<*, *>
        assertEquals("PO006", order["purchase"])
        assertEquals("BK006", order["booking"])
        val invoices = order["invoices"] as List<*>
        assertTrue(invoices.isNotEmpty())
        val invoice = invoices[0] as Map<*, *>
        assertEquals("INV006", invoice["invoice"])
    }

    @Test
    fun `Escenario 6 - idempotency with Idempotency-Key header`() {
        // Given: Request with explicit idempotency key
        val request =
            EmailIngestRequest(
                booking = "BK007",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO007",
                            invoices = listOf(InvoiceRequest(invoice = "INV007")),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639875"),
                    ),
            )

        val idempotencyKey = "TEST-IDEMPOTENCY-KEY-007"

        // When: Submit request with idempotency key
        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeadersWithIdempotencyKey(idempotencyKey)),
                Map::class.java,
            )

        // Then: First request accepted
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        assertEquals(idempotencyKey, response1.body?.get("idempotencyKey"))
        waitForProcessing()

        // When: Submit same request with same idempotency key
        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeadersWithIdempotencyKey(idempotencyKey)),
                Map::class.java,
            )

        // Then: Second request also accepted (idempotent)
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        assertEquals(idempotencyKey, response2.body?.get("idempotencyKey"))
    }

    @Test
    fun `Escenario 7 - validation errors for invalid data`() {
        // Given: Request with invalid container format
        val invalidRequest =
            EmailIngestRequest(
                booking = "BK008",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "",
                            invoices = emptyList(),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "INVALID"),
                    ),
            )

        // When: Submit invalid request
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(invalidRequest, authHeaders()),
                Map::class.java,
            )

        // Then: Validation error returned
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals("Validation failed", body["error"])
        assertEquals(422, body["status"])
        assertNotNull(body["details"])
    }

    @Test
    fun `Escenario 8 - mixed valid and corrected data`() {
        // Given: Request with some valid data
        val request =
            EmailIngestRequest(
                booking = "BK009",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO009",
                            invoices = listOf(InvoiceRequest(invoice = "INV009")),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639876"),
                    ),
            )

        // When: Submit valid request
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify proper null handling (not string "null")
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders = ordersResponse.body as List<*>
        val order = orders.find { (it as Map<*, *>)["purchase"] == "PO009" } as Map<*, *>
        assertNotNull(order)
        assertEquals("BK009", order["booking"])
    }

    @Test
    fun `Escenario 9 - multiple orders and containers in single payload`() {
        // Given: Multiple orders and containers with same booking
        val request =
            EmailIngestRequest(
                booking = "BK010",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO010A",
                            invoices = listOf(InvoiceRequest(invoice = "INV010A")),
                        ),
                        OrderRequest(
                            purchase = "PO010B",
                            invoices = listOf(InvoiceRequest(invoice = "INV010B")),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639877"),
                        ContainerRequest(container = "MSCU6639878"),
                    ),
            )

        // When: Submit multiple items
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Verify all orders linked to all containers (M:N relationship)
        val linkResponse1 =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO010A/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, linkResponse1.statusCode)
        val containers1 = linkResponse1.body as List<*>
        assertEquals(2, containers1.size)

        val linkResponse2 =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO010B/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, linkResponse2.statusCode)
        val containers2 = linkResponse2.body as List<*>
        assertEquals(2, containers2.size)
    }

    @Test
    fun `Escenario 10 - response format consistency across all endpoints`() {
        // Given: Some test data
        val request =
            EmailIngestRequest(
                booking = "BK011",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO011",
                            invoices = listOf(InvoiceRequest(invoice = "INV011")),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU6639879"),
                    ),
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(request, authHeaders()),
            Map::class.java,
        )
        waitForProcessing()

        // Verify all endpoints return plain arrays (not {"data": [...]})

        // Test orders endpoint
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, ordersResponse.statusCode)
        assertTrue(ordersResponse.body is List<*>)

        // Test containers endpoint
        val containersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containersResponse.statusCode)
        assertTrue(containersResponse.body is List<*>)

        // Test order->containers endpoint
        val orderContainersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO011/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainersResponse.statusCode)
        assertTrue(orderContainersResponse.body is List<*>)

        // Test container->orders endpoint
        val containerOrdersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MSCU6639879/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containerOrdersResponse.statusCode)
        assertTrue(containerOrdersResponse.body is List<*>)
    }
}
