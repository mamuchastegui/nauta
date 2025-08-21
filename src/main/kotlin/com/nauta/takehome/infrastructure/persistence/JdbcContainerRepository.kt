package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.ContainerRepository
import com.nauta.takehome.application.ContainerUpsertRequest
import com.nauta.takehome.domain.BookingRef
import com.nauta.takehome.domain.Container
import com.nauta.takehome.domain.ContainerRef
import com.nauta.takehome.domain.PurchaseRef
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcContainerRepository(private val jdbcTemplate: JdbcTemplate) : ContainerRepository {
    private val logger = LoggerFactory.getLogger(JdbcContainerRepository::class.java)

    override fun upsertByRef(
        tenantId: String,
        containerRef: ContainerRef,
        bookingRef: BookingRef?,
    ): Container {
        val now = Instant.now()

        val sql =
            """
            INSERT INTO containers (container_ref, tenant_id, booking_ref, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (container_ref, tenant_id)
            DO UPDATE SET
                booking_ref = EXCLUDED.booking_ref,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent()

        return jdbcTemplate.queryForObject(
            sql,
            containerRowMapper,
            containerRef.value,
            tenantId,
            bookingRef?.value,
            Timestamp.from(now),
            Timestamp.from(now),
        )!!
    }

    override fun batchUpsertByRef(
        tenantId: String,
        containerRequests: List<ContainerUpsertRequest>,
    ): List<Container> {
        if (containerRequests.isEmpty()) {
            return emptyList()
        }

        val now = Instant.now()
        val sql =
            """
            INSERT INTO containers (container_ref, tenant_id, booking_ref, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (container_ref, tenant_id)
            DO UPDATE SET
                booking_ref = EXCLUDED.booking_ref,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent()

        val results = mutableListOf<Container>()

        // Process in chunks to avoid parameter limits and improve performance
        containerRequests.chunked(100).forEach { chunk ->
            chunk.forEach { request ->
                val container = jdbcTemplate.queryForObject(
                    sql,
                    containerRowMapper,
                    request.containerRef.value,
                    tenantId,
                    request.bookingRef?.value,
                    Timestamp.from(now),
                    Timestamp.from(now),
                )!!
                results.add(container)
            }
        }

        logger.debug("Batch upserted ${containerRequests.size} containers for tenant: $tenantId")
        return results
    }

    override fun findByContainerRef(
        tenantId: String,
        containerRef: ContainerRef,
    ): Container? {
        val sql = "SELECT * FROM containers WHERE tenant_id = ? AND container_ref = ?"

        return try {
            jdbcTemplate.queryForObject(sql, containerRowMapper, tenantId, containerRef.value)
        } catch (e: EmptyResultDataAccessException) {
            logger.debug("No container found for ref: ${containerRef.value} and tenant: $tenantId", e)
            null
        }
    }

    override fun findAll(tenantId: String): List<Container> {
        val sql = "SELECT * FROM containers WHERE tenant_id = ? ORDER BY created_at DESC"
        return jdbcTemplate.query(sql, containerRowMapper, tenantId)
    }

    override fun findByPurchaseRef(
        tenantId: String,
        purchaseRef: PurchaseRef,
    ): List<Container> {
        // Link containers to orders through booking reference
        val sql =
            """
            SELECT c.* FROM containers c
            JOIN orders o ON c.booking_ref = o.booking_ref AND c.tenant_id = o.tenant_id
            WHERE o.tenant_id = ? AND o.purchase_ref = ?
            """.trimIndent()
        return jdbcTemplate.query(sql, containerRowMapper, tenantId, purchaseRef.value)
    }

    override fun findByBookingRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): List<Container> {
        val sql = "SELECT * FROM containers WHERE tenant_id = ? AND booking_ref = ? ORDER BY created_at DESC"
        return jdbcTemplate.query(sql, containerRowMapper, tenantId, bookingRef.value)
    }

    private val containerRowMapper =
        RowMapper<Container> { rs, _ ->
            Container(
                id = rs.getLong("id"),
                containerRef = ContainerRef(rs.getString("container_ref")),
                tenantId = rs.getString("tenant_id"),
                bookingRef = rs.getString("booking_ref")?.let { BookingRef(it) },
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
}
