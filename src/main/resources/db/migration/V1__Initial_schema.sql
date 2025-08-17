-- Nauta Takehome Initial Schema
-- Creates tables for orders, containers, bookings, and invoices with proper constraints

-- Bookings table
CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    booking_ref VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bookings_ref_tenant UNIQUE (booking_ref, tenant_id)
);

-- Orders table
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    purchase_ref VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    booking_ref VARCHAR(255),
    container_ref VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_orders_ref_tenant UNIQUE (purchase_ref, tenant_id)
);

-- Containers table
CREATE TABLE containers (
    id BIGSERIAL PRIMARY KEY,
    container_ref VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    booking_ref VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_containers_ref_tenant UNIQUE (container_ref, tenant_id),
    CONSTRAINT chk_container_ref_format CHECK (container_ref ~ '^[A-Z]{4}[0-9]{6}[0-9]$')
);

-- Invoices table
CREATE TABLE invoices (
    id BIGSERIAL PRIMARY KEY,
    invoice_ref VARCHAR(255) NOT NULL,
    purchase_ref VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_invoices_ref_tenant UNIQUE (invoice_ref, tenant_id)
);

-- Indexes for performance
CREATE INDEX idx_orders_tenant_booking ON orders(tenant_id, booking_ref);
CREATE INDEX idx_orders_tenant_container ON orders(tenant_id, container_ref);
CREATE INDEX idx_containers_tenant_booking ON containers(tenant_id, booking_ref);
CREATE INDEX idx_invoices_tenant_purchase ON invoices(tenant_id, purchase_ref);

-- Comments for documentation
COMMENT ON TABLE bookings IS 'Booking references for grouping orders and containers';
COMMENT ON TABLE orders IS 'Purchase orders with optional linking to bookings and containers';
COMMENT ON TABLE containers IS 'Shipping containers with ISO 6346 reference format';
COMMENT ON TABLE invoices IS 'Invoices linked to purchase orders';

COMMENT ON CONSTRAINT chk_container_ref_format ON containers IS 'Enforces basic ISO 6346 format: 4 letters + 6 digits + 1 check digit';