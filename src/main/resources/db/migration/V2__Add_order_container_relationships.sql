-- Add many-to-many relationship table for orders and containers
-- This enables flexible linking beyond booking_ref constraints

-- Order-Container relationships table
CREATE TABLE order_containers (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    container_id BIGINT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
    linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    linking_reason VARCHAR(50) NOT NULL DEFAULT 'booking_match',
    confidence_score DECIMAL(3,2) DEFAULT 1.00,
    created_by VARCHAR(255) DEFAULT 'system',
    tenant_id VARCHAR(255) NOT NULL,
    
    -- Ensure no duplicate relationships
    CONSTRAINT uk_order_containers UNIQUE (order_id, container_id),
    
    -- Ensure tenant isolation
    CONSTRAINT chk_order_containers_tenant_consistency 
        CHECK (tenant_id IS NOT NULL)
);

-- Indexes for performance
CREATE INDEX idx_order_containers_order ON order_containers(order_id);
CREATE INDEX idx_order_containers_container ON order_containers(container_id);
CREATE INDEX idx_order_containers_tenant ON order_containers(tenant_id);
CREATE INDEX idx_order_containers_tenant_order ON order_containers(tenant_id, order_id);
CREATE INDEX idx_order_containers_tenant_container ON order_containers(tenant_id, container_id);

-- Comments for documentation
COMMENT ON TABLE order_containers IS 'Many-to-many relationships between orders and containers with metadata';
COMMENT ON COLUMN order_containers.linking_reason IS 'Reason for linking: booking_match, manual, ai_inference, temporal_correlation';
COMMENT ON COLUMN order_containers.confidence_score IS 'Confidence level of the relationship (0.00-1.00)';

-- Remove container_ref from orders table since we now use M:N relationship
-- Note: We keep booking_ref as a primary linking mechanism
ALTER TABLE orders DROP COLUMN IF EXISTS container_ref;