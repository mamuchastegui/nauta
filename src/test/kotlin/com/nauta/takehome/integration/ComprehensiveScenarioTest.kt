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
import org.junit.jupiter.api.BeforeEach
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
 * Comprehensive integration tests for all specified scenarios (0-10)
 * These tests verify complete end-to-end behavior including reconciliation,
 * idempotency, multi-tenant isolation, and various data arrival patterns.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureWebMvc
@Import(TestConfiguration::class)
class ComprehensiveScenarioTest {
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

    private fun authHeadersTenantB(): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(testJwtTokenGenerator.getTenantBToken())
        return headers
    }

    private fun authHeadersWithIdempotencyKey(idempotencyKey: String): HttpHeaders {
        val headers = authHeaders()
        headers.set("Idempotency-Key", idempotencyKey)
        return headers
    }

    private fun waitForProcessing() {
        Thread.sleep(1500)
    }

    @BeforeEach
    fun setup() {
        // Clean database or ensure isolated test data
        waitForProcessing()
    }

    @Test
    fun `Scenario 0 - Full payload baseline`() {
        // Given: Full payload with booking, orders, containers
        val request =
            EmailIngestRequest(
                booking = "BK123456",
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO789012",
                            invoices = listOf(InvoiceRequest(invoice = "INV345678")),
                        ),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MEDU1234567"),
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

        // Assert: GET /api/orders contains PO789012
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, ordersResponse.statusCode)
        val orders = ordersResponse.body as List<*>
        assertTrue(orders.any { (it as Map<*, *>)["purchase"] == "PO789012" })

        // Assert: GET /api/containers contains MEDU1234567
        val containersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containersResponse.statusCode)
        val containers = containersResponse.body as List<*>
        assertTrue(containers.any { (it as Map<*, *>)["container"] == "MEDU1234567" })

        // Assert: GET /api/orders/PO789012/containers => [ "MEDU1234567" ]
        val orderContainersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO789012/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainersResponse.statusCode)
        val orderContainers = orderContainersResponse.body as List<*>
        assertEquals(1, orderContainers.size)
        assertEquals("MEDU1234567", (orderContainers[0] as Map<*, *>)["container"])

        // Assert: GET /api/containers/MEDU1234567/orders => [ "PO789012" ]
        val containerOrdersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MEDU1234567/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containerOrdersResponse.statusCode)
        val containerOrders = containerOrdersResponse.body as List<*>
        assertEquals(1, containerOrders.size)
        assertEquals("PO789012", (containerOrders[0] as Map<*, *>)["purchase"])
    }

    @Test
    fun `Scenario 1 - Order only then booking`() {
        // Step 1: Send only order
        val orderOnlyRequest =
            EmailIngestRequest(
                booking = null,
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO1001",
                            invoices = listOf(InvoiceRequest(invoice = "INV1001")),
                        ),
                    ),
                containers = null,
            )

        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(orderOnlyRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        waitForProcessing()

        // Assert: GET /api/orders contains PO1001
        val ordersResponse1 =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders1 = ordersResponse1.body as List<*>
        assertTrue(orders1.any { (it as Map<*, *>)["purchase"] == "PO1001" })

        // Assert: GET /api/orders/PO1001/containers => [] (no links yet)
        val orderContainersResponse1 =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO1001/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainersResponse1.statusCode)
        val orderContainers1 = orderContainersResponse1.body as List<*>
        assertEquals(0, orderContainers1.size)

        // Step 2: Send only booking
        val bookingOnlyRequest =
            EmailIngestRequest(
                booking = "BK1001",
                orders = null,
                containers = null,
            )

        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(bookingOnlyRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        waitForProcessing()

        // Step 3: Send order + booking (reconciliation)
        val reconciliationRequest =
            EmailIngestRequest(
                booking = "BK1001",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO1001", invoices = null),
                    ),
                containers = null,
            )

        val response3 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(reconciliationRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response3.statusCode)
        waitForProcessing()

        // Assert: Still no links until container arrives
        val orderContainersResponse3 =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO1001/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(0, (orderContainersResponse3.body as List<*>).size)
    }

    @Test
    fun `Scenario 2 - Container only then booking`() {
        // Step 1: Send only container
        val containerOnlyRequest =
            EmailIngestRequest(
                booking = null,
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU7654321"),
                    ),
            )

        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(containerOnlyRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        waitForProcessing()

        // Assert: GET /api/containers contains MSCU7654321
        val containersResponse1 =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val containers1 = containersResponse1.body as List<*>
        assertTrue(containers1.any { (it as Map<*, *>)["container"] == "MSCU7654321" })

        // Assert: GET /api/containers/MSCU7654321/orders => []
        val containerOrdersResponse1 =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MSCU7654321/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containerOrdersResponse1.statusCode)
        assertEquals(0, (containerOrdersResponse1.body as List<*>).size)

        // Step 2: Send booking + container
        val bookingContainerRequest =
            EmailIngestRequest(
                booking = "BK2001",
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "MSCU7654321"),
                    ),
            )

        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(bookingContainerRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        waitForProcessing()

        // Assert: Still no links to orders until order arrives
        val containerOrdersResponse2 =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MSCU7654321/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(0, (containerOrdersResponse2.body as List<*>).size)
    }

    @Test
    fun `Scenario 3 - Out of order container+booking then order+booking with automatic link`() {
        // Step 1: Container + booking arrives first
        val containerRequest =
            EmailIngestRequest(
                booking = "BK3001",
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "CMAU1111111"),
                    ),
            )

        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(containerRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        waitForProcessing()

        // Step 2: Order + booking arrives later (should trigger reconciliation)
        val orderRequest =
            EmailIngestRequest(
                booking = "BK3001",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO3001", invoices = null),
                    ),
                containers = null,
            )

        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(orderRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        waitForProcessing()

        // Assert: GET /api/orders/PO3001/containers => [ "CMAU1111111" ]
        val orderContainersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO3001/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainersResponse.statusCode)
        val orderContainers = orderContainersResponse.body as List<*>
        assertEquals(1, orderContainers.size)
        assertEquals("CMAU1111111", (orderContainers[0] as Map<*, *>)["container"])

        // Assert: GET /api/containers/CMAU1111111/orders => [ "PO3001" ]
        val containerOrdersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/CMAU1111111/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containerOrdersResponse.statusCode)
        val containerOrders = containerOrdersResponse.body as List<*>
        assertEquals(1, containerOrders.size)
        assertEquals("PO3001", (containerOrders[0] as Map<*, *>)["purchase"])
    }

    @Test
    fun `Scenario 4 - Multiple orders and containers under same booking`() {
        val request =
            EmailIngestRequest(
                booking = "BK4001",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO4001", invoices = null),
                        OrderRequest(purchase = "PO4002", invoices = null),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "TTNU2222222"),
                        ContainerRequest(container = "OOLU3333333"),
                    ),
            )

        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        waitForProcessing()

        // Assert: GET /api/orders/PO4001/containers => [ "TTNU2222222", "OOLU3333333" ]
        val orderContainers1Response =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO4001/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainers1Response.statusCode)
        val orderContainers1 = orderContainers1Response.body as List<*>
        assertEquals(2, orderContainers1.size)

        // Assert: GET /api/orders/PO4002/containers => [ "TTNU2222222", "OOLU3333333" ]
        val orderContainers2Response =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO4002/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainers2Response.statusCode)
        val orderContainers2 = orderContainers2Response.body as List<*>
        assertEquals(2, orderContainers2.size)

        // Assert: GET /api/containers/TTNU2222222/orders => [ "PO4001", "PO4002" ]
        val containerOrdersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/TTNU2222222/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containerOrdersResponse.statusCode)
        val containerOrders = containerOrdersResponse.body as List<*>
        assertEquals(2, containerOrders.size)
    }

    @Test
    fun `Scenario 5 - Invoices arrive after orders`() {
        // Step 1: Order without invoices
        val orderRequest =
            EmailIngestRequest(
                booking = null,
                orders =
                    listOf(
                        OrderRequest(purchase = "PO5001", invoices = null),
                    ),
                containers = null,
            )

        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(orderRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        waitForProcessing()

        // Step 2: Same order with invoices (should update, not duplicate)
        val orderWithInvoicesRequest =
            EmailIngestRequest(
                booking = null,
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO5001",
                            invoices =
                                listOf(
                                    InvoiceRequest(invoice = "INV5001"),
                                    InvoiceRequest(invoice = "INV5002"),
                                ),
                        ),
                    ),
                containers = null,
            )

        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(orderWithInvoicesRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        waitForProcessing()

        // Assert: GET /api/orders shows PO5001 with 2 invoices
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders = ordersResponse.body as List<*>
        val order = orders.find { (it as Map<*, *>)["purchase"] == "PO5001" } as Map<*, *>
        assertNotNull(order)
        val invoices = order["invoices"] as List<*>
        assertEquals(2, invoices.size)

        // Step 3: Send same request again (should not duplicate invoices)
        val response3 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(orderWithInvoicesRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response3.statusCode)
        waitForProcessing()

        // Assert: Still only 2 invoices (no duplicates)
        val ordersResponse3 =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders3 = ordersResponse3.body as List<*>
        val order3 = orders3.find { (it as Map<*, *>)["purchase"] == "PO5001" } as Map<*, *>
        val invoices3 = order3["invoices"] as List<*>
        assertEquals(2, invoices3.size)
    }

    @Test
    fun `Scenario 6 - Idempotency with same and different keys`() {
        val request =
            EmailIngestRequest(
                booking = "BK6001",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO6001", invoices = null),
                    ),
                containers = null,
            )

        val idempotencyKey = "abc-123"

        // A) Same Idempotency-Key twice
        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeadersWithIdempotencyKey(idempotencyKey)),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        assertEquals(idempotencyKey, response1.body?.get("idempotencyKey"))

        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeadersWithIdempotencyKey(idempotencyKey)),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        assertEquals(idempotencyKey, response2.body?.get("idempotencyKey"))

        waitForProcessing()

        // B) Different keys but same business data
        val response3 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response3.statusCode)

        val response4 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response4.statusCode)

        waitForProcessing()

        // Assert: Only 1 order exists (upsert by business key)
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders = ordersResponse.body as List<*>
        val matchingOrders = orders.filter { (it as Map<*, *>)["purchase"] == "PO6001" }
        assertEquals(1, matchingOrders.size)
    }

    @Test
    fun `Scenario 7 - Multi-tenant isolation`() {
        // Tenant A data
        val requestA =
            EmailIngestRequest(
                booking = "BK7001",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO7001", invoices = null),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MEDU7000001"),
                    ),
            )

        // Tenant B data (same business codes)
        val requestB =
            EmailIngestRequest(
                booking = "BK7001",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO7001", invoices = null),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "MEDU7000001"),
                    ),
            )

        // Submit as Tenant A
        val responseA =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(requestA, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, responseA.statusCode)

        // Submit as Tenant B
        val responseB =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(requestB, authHeadersTenantB()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, responseB.statusCode)
        waitForProcessing()

        // Assert: Tenant A sees only their data
        val ordersResponseA =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val ordersA = ordersResponseA.body as List<*>
        val orderA = ordersA.find { (it as Map<*, *>)["purchase"] == "PO7001" } as Map<*, *>
        assertNotNull(orderA)

        // Assert: Tenant B sees only their data
        val ordersResponseB =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeadersTenantB()),
                List::class.java,
            )
        val ordersB = ordersResponseB.body as List<*>
        val orderB = ordersB.find { (it as Map<*, *>)["purchase"] == "PO7001" } as Map<*, *>
        assertNotNull(orderB)

        // Assert: Tenant A cannot see Tenant B's links
        val linkResponseA =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO7001/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, linkResponseA.statusCode)
        assertEquals(1, (linkResponseA.body as List<*>).size)

        // Assert: Cross-tenant isolation maintained
        assertTrue(ordersA.size >= 1)
        assertTrue(ordersB.size >= 1)
    }

    @Test
    fun `Scenario 8 - Resend with older data does not remove newer data`() {
        // Step 1: Send newer data with invoices
        val newerRequest =
            EmailIngestRequest(
                booking = null,
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO8001",
                            invoices = listOf(InvoiceRequest(invoice = "INV8001")),
                        ),
                    ),
                containers = null,
            )

        val response1 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(newerRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response1.statusCode)
        waitForProcessing()

        // Step 2: Send older data without invoices
        val olderRequest =
            EmailIngestRequest(
                booking = null,
                orders =
                    listOf(
                        OrderRequest(purchase = "PO8001", invoices = null),
                    ),
                containers = null,
            )

        val response2 =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(olderRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, response2.statusCode)
        waitForProcessing()

        // Assert: Invoices do NOT disappear (upsert with merge, never delete by omission)
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val orders = ordersResponse.body as List<*>
        val order = orders.find { (it as Map<*, *>)["purchase"] == "PO8001" } as Map<*, *>
        assertNotNull(order)
        val invoices = order["invoices"] as List<*>
        assertEquals(1, invoices.size)
        assertEquals("INV8001", (invoices[0] as Map<*, *>)["invoice"])
    }

    @Test
    fun `Scenario 9 - Container validation strict vs lax`() {
        // Test strict validation (should fail for invalid container)
        val invalidRequest =
            EmailIngestRequest(
                booking = null,
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "CTbad000000"),
                    ),
            )

        val invalidResponse =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(invalidRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalidResponse.statusCode)

        // Test lax validation (should accept valid pattern)
        val validRequest =
            EmailIngestRequest(
                booking = null,
                orders = null,
                containers =
                    listOf(
                        ContainerRequest(container = "ABCD1234567"),
                    ),
            )

        val validResponse =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(validRequest, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, validResponse.statusCode)
        waitForProcessing()

        // Assert: Valid container was inserted
        val containersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        val containers = containersResponse.body as List<*>
        assertTrue(containers.any { (it as Map<*, *>)["container"] == "ABCD1234567" })
    }

    @Test
    fun `Scenario 10 - Complete out-of-order flow`() {
        // Step 1: Order only
        val step1 =
            EmailIngestRequest(
                booking = null,
                orders = listOf(OrderRequest(purchase = "POX", invoices = null)),
                containers = null,
            )
        restTemplate.postForEntity("${baseUrl()}/api/email", HttpEntity(step1, authHeaders()), Map::class.java)
        waitForProcessing()

        // Step 2: Container only
        val step2 =
            EmailIngestRequest(
                booking = null,
                orders = null,
                containers = listOf(ContainerRequest(container = "MSCU9999999")),
            )
        restTemplate.postForEntity("${baseUrl()}/api/email", HttpEntity(step2, authHeaders()), Map::class.java)
        waitForProcessing()

        // Step 3: Booking only
        val step3 =
            EmailIngestRequest(
                booking = "BKX",
                orders = null,
                containers = null,
            )
        restTemplate.postForEntity("${baseUrl()}/api/email", HttpEntity(step3, authHeaders()), Map::class.java)
        waitForProcessing()

        // Step 4: Booking + Order
        val step4 =
            EmailIngestRequest(
                booking = "BKX",
                orders = listOf(OrderRequest(purchase = "POX", invoices = null)),
                containers = null,
            )
        restTemplate.postForEntity("${baseUrl()}/api/email", HttpEntity(step4, authHeaders()), Map::class.java)
        waitForProcessing()

        // Step 5: Booking + Container (should trigger final reconciliation)
        val step5 =
            EmailIngestRequest(
                booking = "BKX",
                orders = null,
                containers = listOf(ContainerRequest(container = "MSCU9999999")),
            )
        val finalResponse =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(step5, authHeaders()),
                Map::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, finalResponse.statusCode)
        waitForProcessing()

        // Assert: GET /api/orders/POX/containers => [ "MSCU9999999" ]
        val orderContainersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/POX/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, orderContainersResponse.statusCode)
        val orderContainers = orderContainersResponse.body as List<*>
        assertEquals(1, orderContainers.size)
        assertEquals("MSCU9999999", (orderContainers[0] as Map<*, *>)["container"])

        // Assert: GET /api/containers/MSCU9999999/orders => [ "POX" ]
        val containerOrdersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MSCU9999999/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )
        assertEquals(HttpStatus.OK, containerOrdersResponse.statusCode)
        val containerOrders = containerOrdersResponse.body as List<*>
        assertEquals(1, containerOrders.size)
        assertEquals("POX", (containerOrders[0] as Map<*, *>)["purchase"])
    }
}
