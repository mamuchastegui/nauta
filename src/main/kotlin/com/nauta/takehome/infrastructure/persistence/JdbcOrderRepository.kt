package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.domain.BookingRef
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.PurchaseRef
import java.sql.Timestamp
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class JdbcOrderRepository(private val jdbcTemplate: JdbcTemplate) : OrderRepository {
    private val logger = LoggerFactory.getLogger(JdbcOrderRepository::class.java)

    override fun upsertByRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
        bookingRef: BookingRef?,
    ): Order {
        val now = Instant.now()

        val sql =
            """
            INSERT INTO orders (purchase_ref, tenant_id, booking_ref, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (purchase_ref, tenant_id)
            DO UPDATE SET
                booking_ref = EXCLUDED.booking_ref,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent()

        return jdbcTemplate.queryForObject(
            sql,
            orderRowMapper,
            purchaseRef.value,
            tenantId,
            bookingRef?.value,
            Timestamp.from(now),
            Timestamp.from(now),
        )!!
    }

    override fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): Order? {
        val sql = "SELECT * FROM orders WHERE tenant_id = ? AND purchase_ref = ?"

        return try {
            jdbcTemplate.queryForObject(sql, orderRowMapper, tenantId, purchaseRef.value)
        } catch (e: EmptyResultDataAccessException) {
            logger.debug("No order found for purchase ref: ${purchaseRef.value} and tenant: $tenantId", e)
            null
        }
    }

    override fun findAll(tenantId: String): List<Order> {
        val sql = "SELECT * FROM orders WHERE tenant_id = ? ORDER BY created_at DESC"
        return jdbcTemplate.query(sql, orderRowMapper, tenantId)
    }

    private val orderRowMapper =
        RowMapper<Order> { rs, _ ->
            Order(
                id = rs.getLong("id"),
                purchaseRef = PurchaseRef(rs.getString("purchase_ref")),
                tenantId = rs.getString("tenant_id"),
                bookingRef = rs.getString("booking_ref")?.let { BookingRef(it) },
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
}
