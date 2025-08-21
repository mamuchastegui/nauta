package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.OrderRepository
import com.nauta.takehome.application.OrderUpsertRequest
import com.nauta.takehome.domain.BookingRef
import com.nauta.takehome.domain.Order
import com.nauta.takehome.domain.PurchaseRef
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcOrderRepository(private val jdbcTemplate: JdbcTemplate) : OrderRepository {
    private val logger = LoggerFactory.getLogger(JdbcOrderRepository::class.java)

    companion object {
        private const val BATCH_CHUNK_SIZE = 100
    }

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

    override fun batchUpsertByRef(
        tenantId: String,
        orderRequests: List<OrderUpsertRequest>,
    ): List<Order> {
        if (orderRequests.isEmpty()) {
            return emptyList()
        }

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

        val results = mutableListOf<Order>()

        // Process in chunks to avoid parameter limits and improve performance
        orderRequests.chunked(BATCH_CHUNK_SIZE).forEach { chunk ->
            chunk.forEach { request ->
                val order =
                    jdbcTemplate.queryForObject(
                        sql,
                        orderRowMapper,
                        request.purchaseRef.value,
                        tenantId,
                        request.bookingRef?.value,
                        Timestamp.from(now),
                        Timestamp.from(now),
                    )!!
                results.add(order)
            }
        }

        logger.debug("Batch upserted ${orderRequests.size} orders for tenant: $tenantId")
        return results
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

    override fun findByBookingRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): List<Order> {
        val sql = "SELECT * FROM orders WHERE tenant_id = ? AND booking_ref = ? ORDER BY created_at DESC"
        return jdbcTemplate.query(sql, orderRowMapper, tenantId, bookingRef.value)
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
