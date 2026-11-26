-- The dangling-reservation scan WHERE status = 10 AND created_at < ? uses this index instead of a full table scan
ALTER TABLE inventory_reservation
    ADD KEY idx_status_created (status, created_at);
