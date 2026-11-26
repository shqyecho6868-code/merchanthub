-- Tracing: the outbox stores the traceId of the request that wrote it and puts it back into message properties on delivery, so consumer logs link to the order request.
-- Sending happens on another thread (post-commit pool / XXL-Job scan) after the request's MDC is cleared, so it must be persisted with the row (same reason as tenant_id).
-- Nullable: rows written before V6 don't have one; a new one is generated on delivery
ALTER TABLE order_outbox ADD COLUMN trace_id VARCHAR(64) NULL COMMENT 'traceId of the request that wrote the row';

-- The archive table must get the column too, also as the last column: archiving uses INSERT ... SELECT *, so both tables need identical column count and order
ALTER TABLE order_outbox_archive ADD COLUMN trace_id VARCHAR(64) NULL COMMENT 'traceId of the request that wrote the row';
