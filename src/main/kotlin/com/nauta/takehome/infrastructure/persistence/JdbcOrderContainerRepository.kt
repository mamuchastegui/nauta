package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.OrderContainerLinkRequest
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.LinkingReason
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.OrderContainer
import com.nauta.takehome.domain.PurchaseRef
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class JdbcOrderContainerRepository(
    private val jdbcTemplate: JdbcTemplate,
) : OrderContainerRepository {
    private val logger = LoggerFactory.getLogger(JdbcOrderContainerRepository::class.java)

    override fun linkOrderAndContainer(
        tenantId: String,
        orderId: Long,
        containerId: Long,
        linkingReason: LinkingReason,
        confidenceScore: BigDecimal,
    ): OrderContainer {
        // Validate that both order and container belong to the specified tenant
        validateTenantOwnership(tenantId, orderId, containerId)
        val sql =
            """
            INSERT INTO order_containers 
            (order_id, container_id, tenant_id, linking_reason, confidence_score)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (order_id, container_id) 
            DO UPDATE SET
                linking_reason = EXCLUDED.linking_reason,
                confidence_score = EXCLUDED.confidence_score
            RETURNING *
            """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(
                sql,
                orderContainerRowMapper,
                orderId,
                containerId,
                tenantId,
                linkingReason.name.lowercase(),
                confidenceScore,
            )!!
        } catch (e: DuplicateKeyException) {
            logger.debug("Relationship already exists between order $orderId and container $containerId", e)
            val existingRelationship = findRelationship(tenantId, orderId, containerId)
            checkNotNull(existingRelationship) { "Failed to create or find relationship" }
            existingRelationship
        }
    }

    override fun batchLinkOrdersAndContainers(
        tenantId: String,
        linkRequests: List<OrderContainerLinkRequest>,
    ): List<OrderContainer> {
        if (linkRequests.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<OrderContainer>()

        // Process in chunks to avoid parameter limits and improve performance
        linkRequests.chunked(50).forEach { chunk ->
            chunk.forEach { request ->
                try {
                    val result = linkOrderAndContainer(
                        tenantId,
                        request.orderId,
                        request.containerId,
                        request.linkingReason,
                        request.confidenceScore
                    )
                    results.add(result)
                } catch (e: DuplicateKeyException) {
                    logger.debug("Relationship already exists between order ${request.orderId} and container ${request.containerId}", e)
                } catch (e: SecurityException) {
                    logger.warn("Security validation failed for order ${request.orderId} and container ${request.containerId}: ${e.message}")
                }
            }
        }

        logger.debug("Batch processed ${linkRequests.size} link requests, created ${results.size} relationships for tenant: $tenantId")
        return results
    }

    private fun validateTenantOwnership(
        tenantId: String,
        orderId: Long,
        containerId: Long,
    ) {
        // Validate order belongs to tenant
        val orderValidationSql = "SELECT COUNT(*) FROM orders WHERE id = ? AND tenant_id = ?"
        val orderCount = jdbcTemplate.queryForObject(orderValidationSql, Int::class.java, orderId, tenantId) ?: 0
        if (orderCount == 0) {
            throw SecurityException("Order $orderId does not belong to tenant $tenantId")
        }

        // Validate container belongs to tenant
        val containerValidationSql = "SELECT COUNT(*) FROM containers WHERE id = ? AND tenant_id = ?"
        val containerCount =
            jdbcTemplate.queryForObject(
                containerValidationSql,
                Int::class.java,
                containerId,
                tenantId,
            ) ?: 0
        if (containerCount == 0) {
            throw SecurityException("Container $containerId does not belong to tenant $tenantId")
        }
    }

    override fun findContainersByOrderId(
        tenantId: String,
        orderId: Long,
    ): List<Container> {
        val sql =
            """
            SELECT c.* FROM containers c
            JOIN order_containers oc ON c.id = oc.container_id
            WHERE oc.tenant_id = ? AND oc.order_id = ?
            ORDER BY oc.linked_at DESC
            """.trimIndent()

        return jdbcTemplate.query(sql, containerRowMapper, tenantId, orderId)
    }

    override fun findOrdersByContainerId(
        tenantId: String,
        containerId: Long,
    ): List<Order> {
        val sql =
            """
            SELECT o.* FROM orders o
            JOIN order_containers oc ON o.id = oc.order_id
            WHERE oc.tenant_id = ? AND oc.container_id = ?
            ORDER BY oc.linked_at DESC
            """.trimIndent()

        return jdbcTemplate.query(sql, orderRowMapper, tenantId, containerId)
    }

    override fun findContainersByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): List<Container> {
        val sql =
            """
            SELECT c.* FROM containers c
            JOIN order_containers oc ON c.id = oc.container_id
            JOIN orders o ON oc.order_id = o.id
            WHERE oc.tenant_id = ? AND o.purchase_ref = ?
            ORDER BY oc.linked_at DESC
            """.trimIndent()

        return jdbcTemplate.query(sql, containerRowMapper, tenantId, purchaseRef.value)
    }

    override fun findOrdersByContainerRef(
        tenantId: String,
        containerRef: ContainerRef,
    ): List<Order> {
        val sql =
            """
            SELECT o.* FROM orders o
            JOIN order_containers oc ON o.id = oc.order_id
            JOIN containers c ON oc.container_id = c.id
            WHERE oc.tenant_id = ? AND c.container_ref = ?
            ORDER BY oc.linked_at DESC
            """.trimIndent()

        return jdbcTemplate.query(sql, orderRowMapper, tenantId, containerRef.value)
    }

    override fun unlinkOrderAndContainer(
        tenantId: String,
        orderId: Long,
        containerId: Long,
    ): Boolean {
        val sql =
            """
            DELETE FROM order_containers 
            WHERE tenant_id = ? AND order_id = ? AND container_id = ?
            """.trimIndent()

        val rowsAffected = jdbcTemplate.update(sql, tenantId, orderId, containerId)
        return rowsAffected > 0
    }

    override fun findAllRelationships(tenantId: String): List<OrderContainer> {
        val sql =
            """
            SELECT * FROM order_containers 
            WHERE tenant_id = ? 
            ORDER BY linked_at DESC
            """.trimIndent()

        return jdbcTemplate.query(sql, orderContainerRowMapper, tenantId)
    }

    private fun findRelationship(
        tenantId: String,
        orderId: Long,
        containerId: Long,
    ): OrderContainer? {
        val sql =
            """
            SELECT * FROM order_containers 
            WHERE tenant_id = ? AND order_id = ? AND container_id = ?
            """.trimIndent()

        return try {
            jdbcTemplate.queryForObject(sql, orderContainerRowMapper, tenantId, orderId, containerId)
        } catch (e: org.springframework.dao.EmptyResultDataAccessException) {
            logger.debug("No relationship found for order $orderId and container $containerId in tenant $tenantId", e)
            null
        } catch (e: org.springframework.dao.DataAccessException) {
            logger.warn("Database error finding relationship for order $orderId and container $containerId", e)
            null
        }
    }

    private val orderContainerRowMapper =
        RowMapper<OrderContainer> { rs, _ ->
            OrderContainer(
                id = rs.getLong("id"),
                orderId = rs.getLong("order_id"),
                containerId = rs.getLong("container_id"),
                tenantId = rs.getString("tenant_id"),
                linkedAt = rs.getTimestamp("linked_at").toInstant(),
                linkingReason = LinkingReason.valueOf(rs.getString("linking_reason").uppercase()),
                confidenceScore = rs.getBigDecimal("confidence_score"),
                createdBy = rs.getString("created_by"),
            )
        }

    // Reuse existing row mappers from other repositories
    private val orderRowMapper =
        RowMapper<Order> { rs, _ ->
            Order(
                id = rs.getLong("id"),
                purchaseRef = com.nauta.takehome.domain.PurchaseRef(rs.getString("purchase_ref")),
                tenantId = rs.getString("tenant_id"),
                bookingRef = rs.getString("booking_ref")?.let { com.nauta.takehome.domain.BookingRef(it) },
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }

    private val containerRowMapper =
        RowMapper<Container> { rs, _ ->
            Container(
                id = rs.getLong("id"),
                containerRef = ContainerRef(rs.getString("container_ref")),
                tenantId = rs.getString("tenant_id"),
                bookingRef = rs.getString("booking_ref")?.let { com.nauta.takehome.domain.BookingRef(it) },
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
}
