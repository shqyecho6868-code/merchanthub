-- Register MerchantHub's executors and jobs (run after the official schema script).
-- Executor address_type = 0: each service registers its address automatically on startup, nothing to fill in by hand.
-- Job routing FIRST: always call the first online instance; block strategy SERIAL_EXECUTION: if the previous run isn't done, the next one queues (a job never runs concurrently with itself).
-- Exception: the outbox relay uses DISCARD_LATER. In catch-up mode one run drains for up to 45s; with a 5s cron, SERIAL_EXECUTION
-- would pile ~9 queued triggers behind it that then run back to back for nothing. Dropping triggers while one is running loses nothing.
-- Reconciliation jobs are "stopped" by default and triggered manually with "run once" in the console for demos; start them there afterwards to run on their cron.
USE xxl_job;

INSERT INTO xxl_job_group (id, app_name, title, address_type, address_list, update_time) VALUES
    (10, 'order', 'order', 0, NULL, NOW()),
    (11, 'payment', 'payment', 0, NULL, NOW()),
    (12, 'product-inventory', 'product-inventory', 0, NULL, NOW()),
    (13, 'user-tenant', 'user-tenant (approvals)', 0, NULL, NOW());

INSERT INTO xxl_job_info (id, job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf,
                          misfire_strategy, executor_route_strategy, executor_handler, executor_param,
                          executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source,
                          glue_remark, glue_updatetime, child_jobid, trigger_status) VALUES
    (100, 10, 'Order outbox compensation relay', NOW(), NOW(), 'merchanthub', '', 'CRON', '0/5 * * * * ?', 'DO_NOTHING', 'FIRST',
     'orderOutboxRelayJob', '', 'DISCARD_LATER', 60, 0, 'BEAN', '', '', NOW(), '', 1),
    (101, 10, 'Timeout order close (safety net)', NOW(), NOW(), 'merchanthub', '', 'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
     'orderTimeoutScanJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 1),
    (102, 10, 'Order-side reconciliation (flag payment anomalies for refund / outbox failure alerts)', NOW(), NOW(), 'merchanthub', '', 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FIRST',
     'orderReconJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 0),
    (103, 10, 'Archive sent outbox messages', NOW(), NOW(), 'merchanthub', '', 'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FIRST',
     'orderOutboxArchiveJob', '', 'SERIAL_EXECUTION', 600, 0, 'BEAN', '', '', NOW(), '', 1),
    (110, 11, 'Payment <-> order reconciliation', NOW(), NOW(), 'merchanthub', '', 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FIRST',
     'paymentReconJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 0),
    (120, 12, 'Inventory reservation <-> order reconciliation', NOW(), NOW(), 'merchanthub', '', 'CRON', '0 0/5 * * * ?', 'DO_NOTHING', 'FIRST',
     'reservationReconJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 0),
    (121, 12, 'Listing approval compensation', NOW(), NOW(), 'merchanthub', '', 'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
     'listingCompensateJob', '', 'SERIAL_EXECUTION', 120, 0, 'BEAN', '', '', NOW(), '', 1),
    (130, 13, 'Approval result outbox compensation relay', NOW(), NOW(), 'merchanthub', '', 'CRON', '0/5 * * * * ?', 'DO_NOTHING', 'FIRST',
     'approvalOutboxRelayJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1);
