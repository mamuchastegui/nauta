package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.BookingRepository
import com.nauta.takehome.domain.Booking
import com.nauta.takehome.domain.BookingRef
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcBookingRepository(private val jdbcTemplate: JdbcTemplate) : BookingRepository {
    private val logger = LoggerFactory.getLogger(JdbcBookingRepository::class.java)

    override fun upsertByRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): Booking {
        val now = Instant.now()

        val sql =
            """
            INSERT INTO bookings (booking_ref, tenant_id, created_at, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (booking_ref, tenant_id)
            DO UPDATE SET updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent()

        return jdbcTemplate.queryForObject(
            sql,
            bookingRowMapper,
            bookingRef.value,
            tenantId,
            Timestamp.from(now),
            Timestamp.from(now),
        )!!
    }

    override fun findByBookingRef(
        tenantId: String,
        bookingRef: BookingRef,
    ): Booking? {
        val sql = "SELECT * FROM bookings WHERE tenant_id = ? AND booking_ref = ?"

        return try {
            jdbcTemplate.queryForObject(sql, bookingRowMapper, tenantId, bookingRef.value)
        } catch (e: EmptyResultDataAccessException) {
            logger.debug("No booking found for ref: ${bookingRef.value} and tenant: $tenantId", e)
            null
        }
    }

    private val bookingRowMapper =
        RowMapper<Booking> { rs, _ ->
            Booking(
                id = rs.getLong("id"),
                bookingRef = BookingRef(rs.getString("booking_ref")),
                tenantId = rs.getString("tenant_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
}
