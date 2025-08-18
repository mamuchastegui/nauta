package com.nauta.takehome.integration

import com.nauta.takehome.application.ContainerRepository
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.config.TestConfiguration
import com.nauta.takehome.config.TestJwtTokenGenerator
import com.nauta.takehome.domain.BookingRef
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.LinkingReason
import com.nauta.takehome.domain.PurchaseRef
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureWebMvc
@Import(TestConfiguration::class)
class LogisticsIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var containerRepository: ContainerRepository

    @Autowired
    private lateinit var orderContainerRepository: OrderContainerRepository

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

        private const val TENANT_ID = "tenant-123"
    }

    private fun baseUrl() = "http://localhost:$port"

    private fun authHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(testJwtTokenGenerator.getTenant123Token())
        return headers
    }

    @BeforeEach
    fun setUp() {
        // Clean up database before each test
        cleanupDatabase()
    }

    private fun cleanupDatabase() {
        // Clean up in correct order due to foreign key constraints
        orderContainerRepository.findAllRelationships(TENANT_ID).forEach { relationship ->
            orderContainerRepository.unlinkOrderAndContainer(
                TENANT_ID,
                relationship.orderId,
                relationship.containerId,
            )
        }
        // Additional cleanup would be done here for orders, containers, etc.
    }

    @Test
    fun `should handle complete logistics flow with M-N relationships`() {
        // Given: Email ingestion request with booking, orders, and containers
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
                        ContainerRequest(container = "ABCD1234567"),
                    ),
            )

        // When: Submit email ingestion
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(request, authHeaders()),
                Map::class.java,
            )

        // Then: Email accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertNotNull(response.body?.get("idempotencyKey"))

        // Wait for async processing (in real tests, use proper async verification)
        Thread.sleep(2000)

        // Verify: Order was created
        val order = orderRepository.findByPurchaseRef(TENANT_ID, PurchaseRef("PO789012"))
        assertNotNull(order)
        assertEquals(BookingRef("BK123456"), order!!.bookingRef)

        // Verify: Container was created
        val container = containerRepository.findByContainerRef(TENANT_ID, ContainerRef("ABCD1234567"))
        assertNotNull(container)
        assertEquals(BookingRef("BK123456"), container!!.bookingRef)

        // Verify: M:N relationship was created
        val linkedContainers = orderContainerRepository.findContainersByOrderId(TENANT_ID, order.id!!)
        assertEquals(1, linkedContainers.size)
        assertEquals(container.id, linkedContainers[0].id)

        // Verify: Reverse relationship works
        val linkedOrders = orderContainerRepository.findOrdersByContainerId(TENANT_ID, container.id!!)
        assertEquals(1, linkedOrders.size)
        assertEquals(order.id, linkedOrders[0].id)
    }

    @Test
    fun `should create relationships with correct linking reason`() {
        // Given: Create order and container manually
        val order = orderRepository.upsertByRef(TENANT_ID, PurchaseRef("PO123"), BookingRef("BK456"))
        val container = containerRepository.upsertByRef(TENANT_ID, ContainerRef("MEDU1234567"), BookingRef("BK456"))

        // When: Link them explicitly
        val relationship =
            orderContainerRepository.linkOrderAndContainer(
                tenantId = TENANT_ID,
                orderId = order.id!!,
                containerId = container.id!!,
                linkingReason = LinkingReason.BOOKING_MATCH,
            )

        // Then: Relationship created with correct reason
        assertEquals(LinkingReason.BOOKING_MATCH, relationship.linkingReason)
        assertEquals(TENANT_ID, relationship.tenantId)
        assertTrue(relationship.confidenceScore.toDouble() > 0.0)
    }

    @Test
    fun `should handle API queries correctly`() {
        // Given: Setup test data with relationships
        val order = orderRepository.upsertByRef(TENANT_ID, PurchaseRef("PO999"), BookingRef("BK999"))
        val container = containerRepository.upsertByRef(TENANT_ID, ContainerRef("MSKU9876543"), BookingRef("BK999"))

        orderContainerRepository.linkOrderAndContainer(
            tenantId = TENANT_ID,
            orderId = order.id!!,
            containerId = container.id!!,
        )

        // When: Query containers for order
        val containersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO999/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                Map::class.java,
            )

        // Then: Container found
        assertEquals(HttpStatus.OK, containersResponse.statusCode)
        val containers = (containersResponse.body as Map<*, *>)["data"] as List<*>
        assertEquals(1, containers.size)

        // When: Query orders for container
        val ordersResponse =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/MSKU9876543/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                Map::class.java,
            )

        // Then: Order found
        assertEquals(HttpStatus.OK, ordersResponse.statusCode)
        val orders = (ordersResponse.body as Map<*, *>)["data"] as List<*>
        assertEquals(1, orders.size)
    }

    @Test
    fun `should prevent duplicate relationships`() {
        // Given: Order and container
        val order = orderRepository.upsertByRef(TENANT_ID, PurchaseRef("PO777"), BookingRef("BK777"))
        val container = containerRepository.upsertByRef(TENANT_ID, ContainerRef("TCLU1111111"), BookingRef("BK777"))

        // When: Link them twice
        val relationship1 =
            orderContainerRepository.linkOrderAndContainer(
                tenantId = TENANT_ID,
                orderId = order.id!!,
                containerId = container.id!!,
            )

        val relationship2 =
            orderContainerRepository.linkOrderAndContainer(
                tenantId = TENANT_ID,
                orderId = order.id!!,
                containerId = container.id!!,
            )

        // Then: Same relationship returned (no duplicates)
        assertEquals(relationship1.orderId, relationship2.orderId)
        assertEquals(relationship1.containerId, relationship2.containerId)

        // Verify: Only one relationship exists
        val allRelationships = orderContainerRepository.findAllRelationships(TENANT_ID)
        val matchingRelationships =
            allRelationships.filter {
                it.orderId == order.id && it.containerId == container.id
            }
        assertEquals(1, matchingRelationships.size)
    }
}
