package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.ContainerRepository
import com.nauta.takehome.application.OrderContainerRepository
import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.LinkingReason
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.OrderContainer
import com.nauta.takehome.domain.PurchaseRef
import java.math.BigDecimal
import java.sql.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class JdbcOrderContainerRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val orderRepository: OrderRepository,
    private val containerRepository: ContainerRepository,
) : OrderContainerRepository {
    private val logger = LoggerFactory.getLogger(JdbcOrderContainerRepository::class.java)

    override fun linkOrderAndContainer(
        tenantId: String,
        orderId: Long,
        containerId: Long,
        linkingReason: LinkingReason,
    ): OrderContainer {
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
                BigDecimal("1.00"),
            )!!
        } catch (e: DuplicateKeyException) {
            logger.debug("Relationship already exists between order $orderId and container $containerId")
            // Return existing relationship
            findRelationship(tenantId, orderId, containerId)
                ?: throw IllegalStateException("Failed to create or find relationship")
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
        } catch (e: Exception) {
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