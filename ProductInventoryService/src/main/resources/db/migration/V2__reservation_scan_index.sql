-- B3-下：悬空预占扫描 WHERE status = 10 AND created_at < ? 走这个索引，不全表扫描
ALTER TABLE inventory_reservation
    ADD KEY idx_status_created (status, created_at);
