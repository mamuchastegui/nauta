-- Performance optimization indexes for common query patterns
-- These indexes improve query performance for the most frequent access patterns

-- Composite indexes for orders - optimize list queries with tenant filtering and sorting
CREATE INDEX idx_orders_tenant_created ON orders(tenant_id, created_at DESC);

-- Composite indexes for containers - optimize list queries with tenant filtering and sorting  
CREATE INDEX idx_containers_tenant_created ON containers(tenant_id, created_at DESC);

-- Composite indexes for order_containers - optimize relationship queries with confidence scoring
CREATE INDEX idx_order_containers_confidence ON order_containers(tenant_id, confidence_score DESC);

-- Optimize progressive linking queries - find containers by booking within tenant
CREATE INDEX idx_containers_tenant_booking_created ON containers(tenant_id, booking_ref, created_at DESC);

-- Optimize progressive linking queries - find orders by booking within tenant  
CREATE INDEX idx_orders_tenant_booking_created ON orders(tenant_id, booking_ref, created_at DESC);

-- Optimize invoice queries - find invoices by purchase order within tenant
CREATE INDEX idx_invoices_tenant_purchase_created ON invoices(tenant_id, purchase_ref, created_at DESC);

-- Optimize relationship queries - find relationships with reason filtering
CREATE INDEX idx_order_containers_linking_reason ON order_containers(tenant_id, linking_reason, linked_at DESC);

-- Comments for documentation
COMMENT ON INDEX idx_orders_tenant_created IS 'Optimizes GET /api/orders queries with tenant filtering and chronological ordering';
COMMENT ON INDEX idx_containers_tenant_created IS 'Optimizes GET /api/containers queries with tenant filtering and chronological ordering';
COMMENT ON INDEX idx_order_containers_confidence IS 'Optimizes relationship queries by confidence score for ML-based linking';
COMMENT ON INDEX idx_containers_tenant_booking_created IS 'Optimizes progressive linking by booking reference with recency priority';
COMMENT ON INDEX idx_orders_tenant_booking_created IS 'Optimizes progressive linking by booking reference with recency priority';
COMMENT ON INDEX idx_invoices_tenant_purchase_created IS 'Optimizes invoice lookup by purchase order with recency priority';
COMMENT ON INDEX idx_order_containers_linking_reason IS 'Optimizes relationship analysis by linking strategy';