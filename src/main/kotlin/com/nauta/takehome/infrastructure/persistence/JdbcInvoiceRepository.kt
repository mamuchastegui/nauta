package com.nauta.takehome.infrastructure.persistence

import com.nauta.takehome.application.InvoiceRepository
import com.nauta.takehome.domain.Invoice
import com.nauta.takehome.domain.InvoiceRef
import com.nauta.takehome.domain.PurchaseRef
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class JdbcInvoiceRepository(private val jdbcTemplate: JdbcTemplate) : InvoiceRepository {
    private val logger = LoggerFactory.getLogger(JdbcInvoiceRepository::class.java)

    override fun upsertByRef(
        tenantId: String,
        invoiceRef: InvoiceRef,
        purchaseRef: PurchaseRef,
    ): Invoice {
        val now = Instant.now()

        val sql =
            """
            INSERT INTO invoices (invoice_ref, purchase_ref, tenant_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (invoice_ref, tenant_id)
            DO UPDATE SET
                purchase_ref = EXCLUDED.purchase_ref,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """.trimIndent()

        return jdbcTemplate.queryForObject(
            sql,
            invoiceRowMapper,
            invoiceRef.value,
            purchaseRef.value,
            tenantId,
            now,
            now,
        )!!
    }

    override fun findByInvoiceRef(
        tenantId: String,
        invoiceRef: InvoiceRef,
    ): Invoice? {
        val sql = "SELECT * FROM invoices WHERE tenant_id = ? AND invoice_ref = ?"

        return try {
            jdbcTemplate.queryForObject(sql, invoiceRowMapper, tenantId, invoiceRef.value)
        } catch (e: EmptyResultDataAccessException) {
            logger.debug("No invoice found for ref: ${invoiceRef.value} and tenant: $tenantId")
            null
        }
    }

    private val invoiceRowMapper =
        RowMapper<Invoice> { rs, _ ->
            Invoice(
                id = rs.getLong("id"),
                invoiceRef = InvoiceRef(rs.getString("invoice_ref")),
                purchaseRef = PurchaseRef(rs.getString("purchase_ref")),
                tenantId = rs.getString("tenant_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
}
