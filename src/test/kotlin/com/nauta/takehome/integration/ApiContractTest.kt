package com.nauta.takehome.integration

import com.nauta.takehome.config.TestConfiguration
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureWebMvc
@Import(TestConfiguration::class)
class ApiContractTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

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

        private const val TEST_JWT_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJ0ZW5hbnRfaWQiOiJ0ZW5hbnQtMTIzIiwic3ViIjoidXNlci0xMjMiLCJpYXQiOjE2NDA5OTUyMDAsI" +
                "mV4cCI6MTk0MDk5ODgwMH0." +
                "kRxyCcpIYb-EwKiIlVQWwJiWdlqNNUzhq9NkJoP1U8s"
    }

    private fun baseUrl() = "http://localhost:$port"

    private fun authHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(TEST_JWT_TOKEN)
        return headers
    }

    @Test
    fun `POST api email should accept full data`() {
        // Given: Full data
        val fullDataRequest =
            EmailIngestRequest(
                booking = "BK123",
                containers =
                    listOf(
                        ContainerRequest(container = "MEDU1234567"),
                    ),
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO123",
                            invoices =
                                listOf(
                                    InvoiceRequest(invoice = "IN123"),
                                ),
                        ),
                    ),
            )

        // When: Submit request
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(fullDataRequest, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertNotNull(response.body?.get("message"))
        assertNotNull(response.body?.get("idempotencyKey"))
        assertEquals("Email queued for processing", response.body?.get("message"))
    }

    @Test
    fun `POST api email should handle partial data - only booking`() {
        // Given: Only booking data
        val partialRequest =
            EmailIngestRequest(
                booking = "BK456",
                containers = null,
                orders = null,
            )

        // When: Submit request
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(partialRequest, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `POST api email should handle partial data - only orders`() {
        // Given: Only orders data
        val partialRequest =
            EmailIngestRequest(
                booking = null,
                containers = null,
                orders =
                    listOf(
                        OrderRequest(
                            purchase = "PO789",
                            invoices =
                                listOf(
                                    InvoiceRequest(invoice = "INV111"),
                                ),
                        ),
                    ),
            )

        // When: Submit request
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(partialRequest, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `POST api email should handle partial data - only containers`() {
        // Given: Only containers data
        val partialRequest =
            EmailIngestRequest(
                booking = null,
                containers =
                    listOf(
                        ContainerRequest(container = "MSKU9876543"),
                        ContainerRequest(container = "TCLU1111111"),
                    ),
                orders = null,
            )

        // When: Submit request
        val response =
            restTemplate.postForEntity(
                "${baseUrl()}/api/email",
                HttpEntity(partialRequest, authHeaders()),
                Map::class.java,
            )

        // Then: Request accepted
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    }

    @Test
    fun `GET api orders should return all orders for client`() {
        // Given: Submit test data first
        val request =
            EmailIngestRequest(
                booking = "BK999",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO001", invoices = emptyList()),
                        OrderRequest(purchase = "PO002", invoices = emptyList()),
                    ),
                containers = null,
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(request, authHeaders()),
            Map::class.java,
        )

        // Wait for processing
        Thread.sleep(1000)

        // When: Query all orders
        val response =
            restTemplate.exchange(
                "${baseUrl()}/api/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )

        // Then: Orders returned
        assertEquals(HttpStatus.OK, response.statusCode)
        val orders = response.body as List<*>
        assertTrue(orders.isNotEmpty())
    }

    @Test
    fun `GET api containers should return all containers for client`() {
        // Given: Submit test data first
        val request =
            EmailIngestRequest(
                booking = "BK888",
                containers =
                    listOf(
                        ContainerRequest(container = "TEST1234567"),
                        ContainerRequest(container = "TEST7654321"),
                    ),
                orders = null,
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(request, authHeaders()),
            Map::class.java,
        )

        // Wait for processing
        Thread.sleep(1000)

        // When: Query all containers
        val response =
            restTemplate.exchange(
                "${baseUrl()}/api/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )

        // Then: Containers returned
        assertEquals(HttpStatus.OK, response.statusCode)
        val containers = response.body as List<*>
        assertTrue(containers.isNotEmpty())
    }

    @Test
    fun `GET api orders purchaseId containers should return linked containers`() {
        // Given: Submit linked data
        val request =
            EmailIngestRequest(
                booking = "BK777",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO777", invoices = emptyList()),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "LINK1234567"),
                    ),
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(request, authHeaders()),
            Map::class.java,
        )

        // Wait for processing
        Thread.sleep(1000)

        // When: Query containers for order
        val response =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/PO777/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )

        // Then: Linked containers returned
        assertEquals(HttpStatus.OK, response.statusCode)
        val containers = response.body as List<*>
        assertEquals(1, containers.size)
    }

    @Test
    fun `GET api containers containerId orders should return linked orders`() {
        // Given: Submit linked data
        val request =
            EmailIngestRequest(
                booking = "BK666",
                orders =
                    listOf(
                        OrderRequest(purchase = "PO666", invoices = emptyList()),
                    ),
                containers =
                    listOf(
                        ContainerRequest(container = "LINK7654321"),
                    ),
            )

        restTemplate.postForEntity(
            "${baseUrl()}/api/email",
            HttpEntity(request, authHeaders()),
            Map::class.java,
        )

        // Wait for processing
        Thread.sleep(1000)

        // When: Query orders for container
        val response =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/LINK7654321/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                List::class.java,
            )

        // Then: Linked orders returned
        assertEquals(HttpStatus.OK, response.statusCode)
        val orders = response.body as List<*>
        assertEquals(1, orders.size)
    }

    @Test
    fun `should reject requests without authentication`() {
        // Given: Request without auth token
        val request =
            EmailIngestRequest(
                booking = "BK123",
                containers = null,
                orders = null,
            )

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        // No authorization header

        // When: Submit request and expect authentication error
        try {
            val response =
                restTemplate.exchange(
                    "${baseUrl()}/api/email",
                    HttpMethod.POST,
                    HttpEntity(request, headers),
                    String::class.java,
                )
            
            // If we get here, check if it's 401
            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            // Then: Should be unauthorized
            assertEquals(HttpStatus.UNAUTHORIZED, e.statusCode)
        } catch (e: org.springframework.web.client.ResourceAccessException) {
            // HTTP retry exception due to 401 - this is expected for unauthenticated requests
            // The underlying cause should be related to authentication
            assertTrue(
                e.message?.contains("cannot retry due to server authentication") == true,
                "Expected authentication-related error, got: ${e.message}"
            )
        }
    }

    @Test
    fun `should handle invalid container ID format gracefully`() {
        // When: Query with invalid container ID
        val response =
            restTemplate.exchange(
                "${baseUrl()}/api/containers/INVALID/orders",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                Map::class.java,
            )

        // Then: Bad request
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `should handle invalid purchase ID format gracefully`() {
        // When: Query with invalid purchase ID
        val response =
            restTemplate.exchange(
                "${baseUrl()}/api/orders/INVALID_PURCHASE_REF/containers",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders()),
                Map::class.java,
            )

        // Then: Bad request
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
