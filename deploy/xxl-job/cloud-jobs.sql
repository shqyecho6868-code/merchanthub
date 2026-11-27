-- Cloud-only adjustments, imported after seed-jobs.sql by the middleware EC2 (infra/env/middleware-init.sh.tftpl).
-- Locally the reconciliation jobs stay stopped for demos; in the cloud they are the safety nets that were missing during the first cloud load test:
--   100 outbox relay (already on) / 101 timeout close (already on) / 120 reservation <-> order reconciliation (turned on here)
USE xxl_job;

UPDATE xxl_job_info SET trigger_status = 1 WHERE id = 120;
